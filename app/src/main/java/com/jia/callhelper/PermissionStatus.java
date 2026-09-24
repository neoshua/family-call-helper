package com.jia.callhelper;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * 权限状态判断工具：首页横幅与设置页共用，避免重复代码。
 * 只判断本应用运行所必需的几项权限。
 */
public class PermissionStatus {

    /** 通知使用权（监听微信来电通知） */
    public static boolean isNotificationListener(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(ctx.getPackageName());
    }

    /** 无障碍服务（自动点微信接听键） */
    public static boolean isAccessibility(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null) return false;
        return flat.contains(ctx.getPackageName() + "/"
                + CallHelperAccessibilityService.class.getName());
    }

    /** 悬浮窗（安卓 10+ 后台弹大按钮界面） */
    public static boolean isOverlay(Context ctx) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    /** 锁屏全屏通知（安卓 13+ 可查询；低于 29 视为无需） */
    public static boolean isFullScreenIntent(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) return true;
        android.app.NotificationManager nm =
                (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.canUseFullScreenIntent();
    }

    /** 当前系统版本下，未开启的必要权限数量（用于首页横幅） */
    public static int countMissing(Context ctx) {
        int n = 0;
        if (!isNotificationListener(ctx)) n++;
        if (!isAccessibility(ctx)) n++;
        if (!isOverlay(ctx)) n++;
        if (!isFullScreenIntent(ctx)) n++;
        return n;
    }
}
