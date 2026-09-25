package com.jia.callhelper;

import android.content.Context;

/**
 * 「接听键位置」的用户自定义值。
 *
 * 为什么需要它（用户反馈）：不同手机、不同微信版本、甚至同一台手机换了导航方式
 * （三键导航 / 手势导航、有没有导航栏），微信那个绿色接听按钮在屏幕上的位置都不一样。
 * 我们按 1220×2712 的实测截图算出来的"横向 80.3%、距底部 11.4%"是个很好的起点，
 * 但不可能对每一台机器都准 —— 与其我继续猜，不如让用的人自己把圈拖到正确的位置。
 *
 * 存的是**占屏幕的比例**而不是像素，这样：
 *   - 换手机、改分辨率、转到横屏，位置会按比例跟着走，不会错得离谱；
 *   - 一个数字就能在「运行记录」里报给开发排查。
 *
 * 这个位置同时被两处使用：
 *   ① 屏幕指引画的那个绿圈（GuideOverlay）
 *   ② 自动接听真正点下去的那个点（CallHelperAccessibilityService.answerPoint）
 * 两者共用同一个坐标，所以"圈准了"就等于"点准了"——调一次解决两个问题。
 */
public final class AnswerPointPrefs {

    /** 横向位置：接听键中心占屏宽的比例 */
    public static final String KEY_X = "answer_x_ratio";
    /** 纵向位置：接听键中心距屏幕底部的比例 */
    public static final String KEY_BOTTOM = "answer_bottom_ratio";
    /** 圆圈大小：半径占屏宽的比例 */
    public static final String KEY_RADIUS = "answer_radius_ratio";
    /** 用户是否自己校准过（false = 用内置默认值） */
    public static final String KEY_CUSTOM = "answer_point_custom";

    // 默认值来自真机实测：1220×2712 截图里接听键中心 (980, 2403)、半径 108px
    public static final float DEF_X = 0.803f;
    public static final float DEF_BOTTOM = 0.114f;
    public static final float DEF_RADIUS = 0.0885f;

    private AnswerPointPrefs() {}

    /** 用户是否自己调过位置 */
    public static boolean isCustomized(Context ctx) {
        return WhiteListManager.prefs(ctx).getBoolean(KEY_CUSTOM, false);
    }

    /**
     * 读出当前生效的比例值。
     *
     * @return {横向比例, 距底部比例, 半径比例}
     */
    public static float[] ratios(Context ctx) {
        android.content.SharedPreferences p = WhiteListManager.prefs(ctx);
        return new float[]{
                p.getFloat(KEY_X, DEF_X),
                p.getFloat(KEY_BOTTOM, DEF_BOTTOM),
                p.getFloat(KEY_RADIUS, DEF_RADIUS)
        };
    }

    /** 把比例换算成当前屏幕上的像素坐标：{中心X, 中心Y, 半径} */
    public static int[] point(Context ctx, int screenW, int screenH) {
        if (screenW <= 0 || screenH <= 0) return new int[]{0, 0, 0};
        float[] r = ratios(ctx);
        return new int[]{
                Math.round(screenW * r[0]),
                Math.round(screenH - screenH * r[1]),
                Math.round(screenW * r[2])
        };
    }

    /**
     * 保存用户校准出来的位置。
     *
     * @param xRatio      接听键中心占屏宽的比例
     * @param bottomRatio 接听键中心距屏底的比例
     * @param radiusRatio 圆圈半径占屏宽的比例
     */
    public static void save(Context ctx, float xRatio, float bottomRatio, float radiusRatio) {
        WhiteListManager.prefs(ctx).edit()
                .putFloat(KEY_X, xRatio)
                .putFloat(KEY_BOTTOM, bottomRatio)
                .putFloat(KEY_RADIUS, radiusRatio)
                .putBoolean(KEY_CUSTOM, true)
                .apply();
    }

    /** 恢复成内置默认值（按真机实测算出来的那个位置） */
    public static void reset(Context ctx) {
        WhiteListManager.prefs(ctx).edit()
                .putFloat(KEY_X, DEF_X)
                .putFloat(KEY_BOTTOM, DEF_BOTTOM)
                .putFloat(KEY_RADIUS, DEF_RADIUS)
                .putBoolean(KEY_CUSTOM, false)
                .apply();
    }

    /** 给设置页看的一句话说明（含"是不是自己调过"） */
    public static String describe(Context ctx) {
        float[] r = ratios(ctx);
        String pos = "横向 " + pct(r[0]) + "，距底部 " + pct(r[1])
                + "，圆圈大小 " + pct(r[2] / DEF_RADIUS);
        if (isCustomized(ctx)) {
            return "已自己校准：" + pos;
        }
        return "使用默认位置（" + pos + "）。如果圈偏了，点下面的按钮对着真实来电界面调一次就好。";
    }

    private static String pct(float v) {
        return Math.round(v * 1000) / 10f + "%";
    }
}
