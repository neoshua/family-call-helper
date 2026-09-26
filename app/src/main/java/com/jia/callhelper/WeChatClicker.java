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
        sBlindTotal = 0;
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
    /**
     * 【v1.21】本次来电累计盲点次数（只增）。
     *
     * 为什么不能像以前那样"整通只准一次"：本机（微信整页自绘）上，
     * 坐标盲点是**唯一**真正送出点击的路径，而每次盲点后往往拿不到任何证据
     * （既不显示通话中、也读不到文字）。"一次"的配额意味着一通电话只点一下，
     * 万一那一下因为坐标偏差没点上（三键导航、异形屏、用户还没校准），
     * 后面每一轮都被配额挡住 —— 屏幕上的圈一直在，却再也没有新的点击产生。
     *
     * 4 次是怎么定的：间隔至少是 2.5 秒确认 + 5.5 秒复核 + 1.5 秒轮间隔，
     * 4 次约 30 秒量级，仍在微信自己的响铃窗口内；再多的边际收益很小，
     * 而一旦已经接通还继续戳右下角，风险是碰到同一行左边的挂断键。
     */
    private static int sBlindTotal;
    private static final int MAX_BLIND_TOTAL = 4;

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

        // 【v1.22】先"看一眼"屏幕：截图里认得出微信那个绿色接听钮，就点它的真实圆心，
        // 而不是点按比例估算出来的坐标。多截一张图的代价可以忽略，
        // 却能把"位置算歪了、于是点空"这一整类失败消掉。
        svc.requestLook();

        // 【v1.21】整通来电最多允许 4 次坐标盲点（详见 MAX_BLIND_TOTAL 的说明）。
        int r = svc.answerCall(!sBlindUsedThisCall && sBlindTotal < MAX_BLIND_TOTAL);
        CallDiag.log("接听", "第 " + n + "/" + attempts + " 次尝试，结果=" + nameOf(r));

        if (r == CallHelperAccessibilityService.RESULT_CLICKED_BLIND) {
            sBlindUsedThisCall = true;
            sBlindTotal++;
            // 盲点无法确认点中了什么：先等界面切换，再下结论。
            //
            // 【v1.21 修复】这里原本 2.5 秒一到就无条件给结论（callback.onResult），
            // 而 Once 一旦触发就会 cancel() 掉所有排队任务 ——
            // 于是下面那个 5.5 秒的"延时复核"**从来一次都没执行过**，是彻底的死代码。
            // 后果在整页自绘的机器上特别明显：盲点后 2.5 秒判失败，
            // 而微信从响铃切到通话中有时要 2 秒以上 → 白判失败、多耗一轮。
            // 现在改成分两级：2.5 秒只在**能确认**时给结论，拿不到证据就等 5.5 秒那次。
            post(TAG_VERIFY, new Runnable() {
                @Override
                public void run() {
                    if (svc.isInCall()) {
                        CallDiag.log("接听", "盲点 2.5s 确认：已检测到通话中 → 按成功处理");
                        if (callback != null) callback.onResult(true);
                        return;
                    }
                    if (svc.canReadUiText()) {
                        boolean ok = !svc.isRinging();
                        CallDiag.log("接听", "盲点 2.5s 确认：" + (ok ? "已不在响铃" : "仍在响铃")
                                + " → " + (ok ? "按成功处理" : "判定失败"));
                        if (callback != null) callback.onResult(ok);
                        return;
                    }
                    // 界面完全自绘、一个文字节点都没有 —— 此刻**没有资格下结论**。
                    // 保持沉默，把决定权交给 5.5 秒那次复核。
                    CallDiag.log("接听", "盲点 2.5s：界面仍完全读不到内容"
                            + " → 暂不下结论，等 5.5s 复核（避免过早判失败）");
                }
            }, VERIFY_BLIND_MS);
            // 5.5 秒复核：这是自绘界面下真正给结论的地方。
            post(TAG_RECHECK, new Runnable() {
                @Override
                public void run() {
                    boolean inCall = svc.isInCall();
                    boolean readable = svc.canReadUiText();
                    boolean ok;
                    String reason;
                    if (inCall) {
                        ok = true;
                        reason = "已进入通话中";
                    } else if (readable) {
                        ok = !svc.isRinging();
                        reason = ok ? "已不在响铃" : "仍在响铃";
                    } else {
                        ok = false;
                        reason = "仍然一个字都读不到，无法确认是否接上";
                        // 【v1.21】关键：既然压根没证据，就不能占用盲点配额。
                        // 这是本机（整页自绘）最常走的路径 —— 不归还配额的话，
                        // 一通电话**只真正点了一下**，剩下每一轮都因为没有证据而被拒绝再点，
                        // 日志却照常打印「第 N/8 轮 → 尝试点击接听键」，看着像一直在努力。
                        if (sBlindUsedThisCall) {
                            sBlindUsedThisCall = false;
                            CallDiag.log("接听", "无证据 → 归还本次盲点配额（已用 "
                                    + sBlindTotal + "/" + MAX_BLIND_TOTAL + " 次）");
                        }
                    }
                    CallDiag.log("接听", "盲点 5.5s 复核：" + reason
                            + " → " + (ok ? "判定成功" : "判定失败"));
                    if (callback != null) callback.onResult(ok);
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
    // 用 ConcurrentHashMap：cancel() 可能从回调线程被调用（Once 包的是外部回调），
    // HashMap 在这种情况下并发迭代会直接 ConcurrentModificationException。
    private static final java.util.concurrent.ConcurrentHashMap<String, Runnable> sTasks =
            new java.util.concurrent.ConcurrentHashMap<String, Runnable>();

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

    // 【v1.21】这里以前留着两套「按文字找按钮并重试」的公开入口 retryClick()。
    // 全量扫描确认：除了它自己递归调用自己，工程里没有任何地方再用。
    // 而它走的正是"找『接听』两个字"的老路（见本文件开头的 v1.9 说明），
    // 留着只会被后人当成可用方案再捡起来 —— 已经删除。
}
