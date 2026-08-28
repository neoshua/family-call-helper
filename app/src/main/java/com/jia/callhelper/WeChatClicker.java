package com.jia.callhelper;

import android.os.Handler;
import android.os.Looper;

/**
 * 通过无障碍服务在微信通话界面里点击「接听 / 挂断」。
 * 微信界面可能还没加载好，带重试。
 */
public final class WeChatClicker {

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private WeChatClicker() {}

    public static boolean isServiceRunning() {
        return CallHelperAccessibilityService.get() != null;
    }

    public static void retryClick(final String label, final int attempts, final long intervalMs) {
        if (!isServiceRunning()) return;
        if (tryClick(label)) return;
        if (attempts > 1) {
            sHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    retryClick(label, attempts - 1, intervalMs);
                }
            }, intervalMs);
        }
    }

    private static boolean tryClick(String label) {
        CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();
        return svc != null && svc.clickNodeWithText(label);
    }
}
