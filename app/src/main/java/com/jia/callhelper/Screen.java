package com.jia.callhelper;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * 屏幕尺寸与 dp 换算的**唯一**实现。
 *
 * 为什么要有这个类：以前工程里有 5 份各自写的"拿屏幕尺寸"代码，而且**基准不一致** ——
 *   ① {@code CallHelperAccessibilityService.screenSize()}          getRealMetrics
 *   ② {@code CallHelperAccessibilityService.screenSizeFrom(ctx)}   getRealMetrics
 *   ③ {@code CallSessionManager.screenSizeForPull()}               getRealMetrics
 *   ④ {@code CalibrationOverlay.screenSize(ctx)}                   getRealMetrics
 *   ⑤ {@code CallDiag.snapshot()}                                  getResources().getDisplayMetrics()  ← 不是同一个数
 *
 * 前四份是"含系统栏的物理屏"，第五份是"应用可用区"（不含导航栏，1220×2522）。
 * 而 AutoHide 的代价很具体：用户把运行记录发给开发排查坐标问题时，
 * 日志里写的「屏幕=1220x2522」和自动点击真正用的 1220×2712 对不上，直接误导判断。
 *
 * 【基准的硬规定】一律用 getRealMetrics()（含导航栏的真实物理屏），理由：
 *   · 接听键的比例（横向 80.2%、距底 11.4%）是在 1220×2712 的**整屏截图**上量出来的，
 *     那个 2712 就是 getRealMetrics 给的值；
 *   · 屏幕指引画的圈、校准浮层存的坐标、自动点击的点，三者必须共用同一个数，
 *     否则会出现"圈准了但点不准"这种最难查的现象；
 *   · 用户在设置页校准时看到的也是这块物理屏，用同一个基准才符合直觉。
 *
 * 注意：这返回的是**物理屏**，不代表微信内容区。微信实际可用高度要减去导航栏，
 * 那件事由 {@code CallHelperAccessibilityService.answerPointInternal} 负责（见那里的说明），
 * 不要在这里减，否则调用方会重复扣一次。
 */
public final class Screen {

    private Screen() {}

    /**
     * 返回 {宽度, 高度}（物理像素，含系统栏）。
     * 拿不到时返回 {0,0}，调用方必须判 0 —— 别在这上面假设一定有值。
     */
    public static int[] realSize(Context ctx) {
        if (ctx == null) return new int[]{0, 0};
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return new int[]{0, 0};
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    /** dp → px */
    public static int dp(Context ctx, float v) {
        if (ctx == null) return 0;
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
