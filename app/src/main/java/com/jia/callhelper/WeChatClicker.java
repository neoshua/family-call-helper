package com.jia.callhelper;

import android.os.Handler;
import android.os.Looper;

/**
 * 通过无障碍服务在微信通话界面里点击「接听 / 挂断」。
 *
 * 微信界面可能还没加载好（尤其是锁屏刚被点亮时），所以带重试。
 * 带回调的版本会把「到底点没点到」告诉调用方：
 * 点不到时调用方需要退到「响铃提醒用户自己点」的兜底方案。
 */
public final class WeChatClicker {

    /** 点击结果回调：clicked=true 表示成功对微信界面里的按钮派发了点击 */
    public interface Callback {
        void onResult(boolean clicked);
    }

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private WeChatClicker() {}

    public static boolean isServiceRunning() {
        return CallHelperAccessibilityService.get() != null;
    }

    public static void retryClick(String label, int attempts, long intervalMs) {
        retryClick(label, attempts, intervalMs, null);
    }

    /**
     * 反复尝试点击文字为 label 的按钮，直到成功或用完次数。
     * 无论成功还是彻底失败，都会回调一次（传入 null 回调时不回调）。
     */
    public static void retryClick(final String label, final int attempts, final long intervalMs,
                                  final Callback callback) {
        if (!isServiceRunning()) {
            // 无障碍没开启，再等下去也点不到，直接回报失败，让调用方尽快进入兜底提醒
            if (callback != null) callback.onResult(false);
            return;
        }
        if (tryClick(label)) {
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
        // 次数用完：无障碍没开或微信界面一直没到前台
        if (callback != null) callback.onResult(false);
    }

    private static boolean tryClick(String label) {
        CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();
        return svc != null && svc.clickNodeWithText(label);
    }
}
