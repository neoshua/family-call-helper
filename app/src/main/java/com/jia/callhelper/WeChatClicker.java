package com.jia.callhelper;

import android.os.Handler;
import android.os.Looper;

/**
 * 负责在微信通话界面里按下「接听 / 挂断」。
 *
 * ⚠️ v1.9 的重要修正：**不能再假设微信的接听键有文字**。
 * 实测微信视频来电全屏界面暴露给无障碍的文字只有
 * 「隐藏 / 邀请你视频通话 / 翻转 / 模糊背景 / 摄像头已开」，
 * 底部那个绿色接听圆钮既没有 text 也没有 contentDescription。
 * 所以旧版「反复找『接听』两个字」的做法必然失败——这正是「来电从不自动接听」的原因。
 * 现在改为：让无障碍服务做三级定位（语义 → 几何 → 坐标兜底），
 * 这里负责重试、确认是否真的接通，以及「失败要吭声」的兜底。
 */
public final class WeChatClicker {

    /** 点击结果回调：clicked=true 表示确认接通，或已尽最大努力 | 失败=false */
    public interface Callback {
        void onResult(boolean clicked);
    }

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    /** 已废弃：见下面的 sTasks（延时任务改成按标签记账，不再用单槽）。保留字段仅为注释引用 */
    /** 精确点击后等待多久去确认是否接通 */
    private static final long VERIFY_MS = 1200L;
    /** 盲点后等待多久去确认（盲点慢一点，微信界面切换需要时间） */
    private static final long VERIFY_BLIND_MS = 2500L;
    /** 一次来电里最多精确点击几次（界面状态可确认时才允许多点） */
    private static final int MAX_PRECISE_CLICKS = 3;

    /** 延时任务的标签：确认 / 复核 / 下一轮重试，三者互不干扰 */
    private static final String TAG_VERIFY = "verify";
    private static final String TAG_RECHECK = "recheck";
    private static final String TAG_NEXT = "next";

    private WeChatClicker() {}

    public static boolean isServiceRunning() {
        return CallHelperAccessibilityService.get() != null;
    }

    /**
     * 新的一次来电开始时调用：清掉上一轮的残留状态。
     * 注意 sBlindUsedThisCall 也在这里重置 —— 它是"整通来电只准盲点一次"的闸门。
     */
    public static void reset() {
        cancel();
        sBlindUsedThisCall = false;
    }

    /**
     * 【v1.13】"整通来电只准盲点一次"的闸门。
     *
     * 【v1.20】历史上这里同时存在 sBlindUsed 和 sBlindUsedThisCall 两个标志，
     * 二者的清除时机完全一样（都只在 reset() 里），差别只有"谁来读"，
     * 结果就是两处语义打架、谁也担不起全部责任，反而成了幽灵开关。
     * 现在只保留这一个：它就是唯一的盲点配额。
     *
     * 背景：v1.13 起自动接听改成"每轮都尝试点击"（最多 8 轮），
     * 若每轮都允许盲点一次，8 轮下来最多往屏幕右下角盲戳 8 次。
     * 盲点无法确认点中了什么，一旦中途已经接通，继续戳右下角就有碰到
     * 「挂断」的风险（挂断键就在同一行的左边一点点）。
     * 所以这里单独立一个**通电话期间不重置**的标志：
     *   全部定位手段都失败时，只允许盲点一次；之后即使再重试，也不再往坐标上戳。
     *
     * 关键：要让这个闸门真的起作用，answerWithRetry 不能调用 reset()
     * （reset 会把它清零）。answerWithRetry 现在只 cancel() 掉还在排队中的回拨，
     * 盲点标志只在「一通新来电开始」时由 reset() 清一次——
     * 这就是本文件 answerWithRetry 里用 cancel() 而非 reset() 的原因。
     */
    private static boolean sBlindUsedThisCall;

    /** 取消还在排队中的点击重试（例如对方已经挂断） */
    public static void cancel() {
        for (Runnable r : sTasks.values()) {
            if (r != null) sHandler.removeCallbacks(r);
        }
        sTasks.clear();
    }

    /**
     * 接听微信来电：反复尝试，直到确认接通 / 用尽次数 / 确认失败。
     * 无论哪种结局都会回调一次。
     */
    public static void answerWithRetry(final int attempts, final long intervalMs,
                                       final Callback callback) {
        // 注意：这里用 cancel() 而不是 reset()。
        // reset() 会把 sBlindUsed / sBlindUsedThisCall 也清零 —— 而 v1.13 的
        // ensureFullScreenStep 每 1.2 秒就调一次 answerWithRetry，若在这里 reset，
        // "整通来电只准盲点一次"的闸门会每轮被重置，等于形同虚设（最多盲戳 8 次）。
        // 我们只取消上一轮还在排队中的回拨，盲点标志只在 CallSessionManager.startCall
        // 调 reset() 时清一次，从而让闸门在一通来电内始终有效。
        // 用 Once 包一层：无论内部多少个延时分支抢着汇报，对外只回调一次，
        // 且结果一旦确定就把还在排队的重试/复核全部作废，避免"已经接上了还继续点"。
        cancel();
        answerStep(1, attempts, intervalMs, new Once(callback));
    }

    /**
     * 回调一次性包装。
     * 盲点分支里有两个延时会分别尝试汇报（2.5s 确认、5.5s 复核），精确点击分支里
     * 还有递归重试 —— 若不加这道闸，上层可能在一个接听流程里被回调好几次，
     * 状态机会被反复推进（这一个 Augeas bug 家族）。
     */
    private static final class Once implements Callback {
        final Callback real;
        boolean fired;

        Once(Callback real) {
            this.real = real;
        }

        @Override
        public synchronized void onResult(boolean clicked) {
            if (fired) return;
            fired = true;
            cancel(); // 胜负已定，剩下的排队任务全部作废
            if (real != null) real.onResult(clicked);
        }
    }

    private static void answerStep(final int n, final int attempts, final long intervalMs,
                                   final Callback callback) {
        final CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();
        if (svc == null) {
            CallDiag.log("接听", "无障碍服务未开启，无法自动接听");
            if (callback != null) callback.onResult(false);
            return;
        }
        if (svc.isInCall()) {
            CallDiag.log("接听", "检测到已经在通话中，无需再接");
            if (callback != null) callback.onResult(true);
            return;
        }

        // 整通来电只允许一次坐标盲点：第一次放行，之后一律要求精确定位。
        int r = svc.answerCall(!sBlindUsedThisCall);
        CallDiag.log("接听", "第 " + n + "/" + attempts + " 次尝试，结果=" + nameOf(r));

        if (r == CallHelperAccessibilityService.RESULT_CLICKED_BLIND) {
            sBlindUsedThisCall = true;
            // 盲点无法确认点中了什么：只等结果，不再重复点
            post(TAG_VERIFY, new Runnable() {
                @Override
                public void run() {
                    boolean inCall = svc.isInCall();
                    boolean readable = svc.canReadUiText();
                    boolean ok;
                    String reason;
                    if (inCall) {
                        ok = true;
                        reason = "已检测到通话中";
                    } else if (readable) {
                        // 界面读得到内容，可以用「还在不在响铃」判断
                        ok = !svc.isRinging();
                        reason = ok ? "已不在响铃" : "仍在响铃";
                    } else {
                        // 微信界面完全自绘、一个文字节点都没有：这里其实无法确认。
                        // 不能谎报「没接上」——万一真接通了却在通话里播报
                        // 「没接上，请自己点接听」，老人会更混乱。
                        // 但也**不能一律当成成功**：v1.13 起自动接听会多轮重试，
                        // 若这里直接"成功"返回，上层就不再重试了，
                        // 万一是真的没点上（坐标偏了几十像素很常见），
                        // 整通电话就白白错过 —— 那正是用户反馈的"一直没自动接听"。
                        // 所以按"未确认"处理：交给上层再试一轮（第二次不会再盲点，
                        // 只会走精确点击，见 sBlindUsedThisCall）。
                        ok = false;
                        reason = "界面完全读不到内容，无法确认（已按坐标送出点击，交由下一轮再试）";
                    }
                    CallDiag.log("接听", "坐标盲点后确认：" + reason + " → " + (ok ? "按成功处理" : "判定失败"));
                    if (callback != null) callback.onResult(ok);
                }
            }, VERIFY_BLIND_MS);
            // 【v1.13】盲点后不确定时，再补一次延时复核：微信从"响铃"切到"通话中"
            // 有时要 2 秒以上，只测一次容易误判成失败，导致上层又白点一轮。
            post(TAG_RECHECK, new Runnable() {
                @Override
                public void run() {
                    if (svc.isInCall()) {
                        CallDiag.log("接听", "盲点延时复核：已进入通话中");
                        if (callback != null) callback.onResult(true);
                    }
                }
            }, VERIFY_BLIND_MS + 3000L);
            return;
        }

        if (r == CallHelperAccessibilityService.RESULT_CLICKED_PRECISE) {
            post(TAG_VERIFY, new Runnable() {
                @Override
                public void run() {
                    if (svc.isInCall()) {
                        CallDiag.log("接听", "已确认接通");
                        if (callback != null) callback.onResult(true);
                        return;
                    }
                    boolean ringing = svc.isRinging();
                    CallDiag.log("接听", "点击后校验：仍在响铃=" + ringing);
                    if (ringing && n < MAX_PRECISE_CLICKS && n < attempts) {
                        answerStep(n + 1, attempts, intervalMs, callback);
                    } else if (ringing) {
                        // 还在响却没接上，明确报告失败，让上层去响铃提醒老人
                        if (callback != null) callback.onResult(false);
                    } else {
                        // 界面已经不是来电界面了：要么接通了，要么被对方挂断。
                        // 无法百分百确认时按成功处理，避免再点一次误碰挂断。
                        CallDiag.log("接听", "界面已离开来电状态，视为已接通");
                        if (callback != null) callback.onResult(true);
                    }
                }
            }, VERIFY_MS);
            return;
        }

        // RESULT_NOT_WECHAT：微信界面还没到前台；RESULT_NO_WINDOW：界面读不到。
        // 都属于「时机未到」，等一会儿再试。
        if (n >= attempts) {
            CallDiag.log("接听", "重试次数用尽仍未成功：" + nameOf(r));
            if (callback != null) callback.onResult(false);
            return;
        }
        post(TAG_NEXT, new Runnable() {
            @Override
            public void run() {
                answerStep(n + 1, attempts, intervalMs, callback);
            }
        }, intervalMs);
    }

    // 【v1.20 重大修复】延时任务改成"按标签记账"，不再互相顶掉。
    //
    // 旧实现是全局单槽 sPending：每 post 一个新任务就 cancel() 掉上一个。
    // 而盲点分支连续排了两个任务（2.5s 确认 + 5.5s 复核），
    // 第二个 post 直接把第一个 removeCallbacks 掉了 —— 于是：
    //   · 盲点确认回调**永远不会执行**（日志里"坐标盲点后确认"从未出现过，可交叉验证）
    //   · CallSessionManager 的 sClickCallbackFired 永远为 false
    //   · 11 秒的 sClickWatchdog 每一轮必然超时代跑
    //   · 结果：每一轮点击白等 11 秒，8 轮下来远超 90 秒硬超时，
    //     整通电话实际只跑得完 1~2 轮 —— 这正是"有时接得上、有时接不上"的机理。
    // 现在用 Map 按任务名分别记账，谁也不顶谁。
    private static final java.util.HashMap<String, Runnable> sTasks =
            new java.util.HashMap<String, Runnable>();

    private static void post(String tag, Runnable r, long delayMs) {
        cancel(tag);
        sTasks.put(tag, r);
        sHandler.postDelayed(r, delayMs);
    }

    private static void cancel(String tag) {
        Runnable old = sTasks.remove(tag);
        if (old != null) sHandler.removeCallbacks(old);
    }

    private static String nameOf(int r) {
        switch (r) {
            case CallHelperAccessibilityService.RESULT_CLICKED_PRECISE: return "已点击(精确定位)";
            case CallHelperAccessibilityService.RESULT_CLICKED_BLIND: return "已点击(坐标兜底)";
            case CallHelperAccessibilityService.RESULT_NOT_WECHAT: return "微信界面不在前台";
            case CallHelperAccessibilityService.RESULT_NOT_RINGING:
                return "不是全屏来电界面（屏幕上还没有接听键）";
            default: return "读不到界面";
        }
    }

    // ---------------- 兼容旧调用 ----------------

    public static void retryClick(String label, int attempts, long intervalMs) {
        retryClick(label, attempts, intervalMs, null);
    }

    public static void retryClick(final String label, final int attempts, final long intervalMs,
                                  final Callback callback) {
        final CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();
        if (svc == null) {
            if (callback != null) callback.onResult(false);
            return;
        }
        if (svc.clickNodeWithText(label)) {
            if (callback != null) callback.onResult(true);
            return;
        }
        if (attempts > 1) {
            sHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    retryClick(label, attempts - 1, intervalMs, callback);
                }
            }, intervalMs);
            return;
        }
        if (callback != null) callback.onResult(false);
    }
}
