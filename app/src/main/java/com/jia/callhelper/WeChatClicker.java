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
    private static Runnable sPending;
    /** 一次来电里最多允许盲点一次（盲点无法确认点到了什么，重复盲点有误挂断风险） */
    private static boolean sBlindUsed;
    /** 精确点击后等待多久去确认是否接通 */
    private static final long VERIFY_MS = 1200L;
    /** 盲点后等待多久去确认（盲点慢一点，微信界面切换需要时间） */
    private static final long VERIFY_BLIND_MS = 2500L;
    /** 一次来电里最多精确点击几次（界面状态可确认时才允许多点） */
    private static final int MAX_PRECISE_CLICKS = 3;

    private WeChatClicker() {}

    public static boolean isServiceRunning() {
        return CallHelperAccessibilityService.get() != null;
    }

    /** 新的一次来电开始时调用：清掉上一轮的残留状态 */
    public static void reset() {
        cancel();
        sBlindUsed = false;
    }

    /** 取消还在排队中的点击重试（例如对方已经挂断） */
    public static void cancel() {
        if (sPending != null) {
            sHandler.removeCallbacks(sPending);
            sPending = null;
        }
    }

    /**
     * 接听微信来电：反复尝试，直到确认接通 / 用尽次数 / 确认失败。
     * 无论哪种结局都会回调一次。
     */
    public static void answerWithRetry(final int attempts, final long intervalMs,
                                       final Callback callback) {
        reset();
        answerStep(1, attempts, intervalMs, callback);
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

        int r = svc.answerCall(!sBlindUsed);
        CallDiag.log("接听", "第 " + n + "/" + attempts + " 次尝试，结果=" + nameOf(r));

        if (r == CallHelperAccessibilityService.RESULT_CLICKED_BLIND) {
            sBlindUsed = true;
            // 盲点无法确认点中了什么：只等结果，不再重复点
            post(new Runnable() {
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
                        // 所以按「已尽力」处理，真实结果由微信界面本身呈现。
                        ok = true;
                        reason = "界面完全读不到内容，无法确认（已按坐标送出点击）";
                    }
                    CallDiag.log("接听", "坐标盲点后确认：" + reason + " → " + (ok ? "按成功处理" : "判定失败"));
                    if (callback != null) callback.onResult(ok);
                }
            }, VERIFY_BLIND_MS);
            return;
        }

        if (r == CallHelperAccessibilityService.RESULT_CLICKED_PRECISE) {
            post(new Runnable() {
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
        post(new Runnable() {
            @Override
            public void run() {
                answerStep(n + 1, attempts, intervalMs, callback);
            }
        }, intervalMs);
    }

    private static void post(Runnable r, long delayMs) {
        cancel();
        sPending = r;
        sHandler.postDelayed(r, delayMs);
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
