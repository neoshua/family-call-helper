package com.jia.callhelper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「接听键位置校准」浮层 —— 让用户自己把那个绿圈挪到正确的位置。
 *
 * 为什么要做这个（用户原话：「那个圈出来的绿色按钮位置还是不对，我想了一个办法，
 * 添加一个选项，让用户自己设置需要圈出来的位置」）：
 * 微信的接听键是**纯图标、没有文字**的绿钮，不同机型、不同微信版本、甚至同一台手机
 * 换导航方式，它在屏幕上的位置都会变。按真机截图算出的默认比例对多数机器够用，
 * 但不可能每台都准。与其我继续猜，不如让用的人自己指一下 —— 这个主意是对的。
 *
 * 用法（设置页 →「校准接听键位置」）：
 *   1. 点开后会退到桌面，屏幕上出现一个绿圈，它浮在**所有应用之上**（含微信来电界面）；
 *   2. 让家人打个微信语音/视频电话过来，看到微信那个绿色接听按钮后，
 *      按住圆圈左边的「灰色小球」拖动，把绿圈套到按钮上；
 *   3. 拖不准还能用顶部「← ↑ ↓ →」一格一格微调，用「圈大/圈小」改圆圈大小；
 *   4. 点「✓ 保存这个位置」，以后圈和自动点击都按这个位置来。
 *
 * 两个设计细节（都是踩过坑才这么做的）：
 *   - **拖动的把手放在圆圈左边，不盖住圆圈本身**：否则它会挡住微信的接听键，
 *     自动接听点下去就落到把手上而不是按钮上，等于把"刚修好的自动接听"又弄坏了。
 *   - 绘制层整层 {@code FLAG_NOT_TOUCHABLE}，触摸穿透到微信；只有把手和顶部工具栏收触摸。
 *
 * 这个位置**同时**用于「屏幕指引画的圈」和「自动接听真正点下去的点」，
 * 所以调一次，两个问题一起解决（见 AnswerPointPrefs 的类注释）。
 */
public final class CalibrationOverlay {

    /** 校准最多显示多久：防止用户忘了关，绿圈一直挂在屏幕上 */
    private static final long AUTO_HIDE_MS = 5 * 60 * 1000L;

    private static WindowManager sWm;
    private static RingLayer sLayer;   // 绘制层（不接收触摸）
    private static View sGrip;         // 圆圈左边的拖动把手（唯一接收拖动的地方）
    private static View sBar;          // 顶部工具栏
    private static int sScreenW, sScreenH;
    private static int sGripSize;
    /** 当前圆圈：{中心X, 中心Y, 半径}，三个窗口共用同一份数据 */
    private static final int[] sPos = new int[]{0, 0, 0};
    private static boolean sShowing;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private CalibrationOverlay() {}

    public static synchronized boolean isShowing() {
        return sShowing;
    }

    /** 是否有「显示在其他应用上层」权限（没有就显示不了校准浮层） */
    public static boolean canOverlay(Context ctx) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    public static synchronized void show(Context rawCtx) {
        if (rawCtx == null) return;
        final Context app = rawCtx.getApplicationContext();
        if (!canOverlay(app)) {
            Toast.makeText(app, "需要先开启「显示在其他应用上层」权限，才能校准位置",
                    Toast.LENGTH_LONG).show();
            return;
        }
        hide();

        int[] size = screenSize(app);
        sScreenW = size[0];
        sScreenH = size[1];
        if (sScreenW <= 0 || sScreenH <= 0) {
            Toast.makeText(app, "读不到屏幕尺寸，无法校准", Toast.LENGTH_SHORT).show();
            return;
        }
        sGripSize = dp(app, 72);

        int[] p = AnswerPointPrefs.point(app, sScreenW, sScreenH);
        sPos[0] = p[0];
        sPos[1] = p[1];
        sPos[2] = p[2] <= 0 ? Math.round(sScreenW * AnswerPointPrefs.DEF_RADIUS) : p[2];

        try {
            sWm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (sWm == null) return;
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            // ① 绘制层：整层不接收触摸，手指能穿透到下面的微信界面
            sLayer = new RingLayer(app);
            WindowManager.LayoutParams lp1 = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp1.gravity = Gravity.TOP | Gravity.LEFT;
            sWm.addView(sLayer, lp1);

            // ② 拖动把手：圆圈左边的小球，**故意不压在圆圈上**（见类注释）
            sGrip = buildGrip(app);
            WindowManager.LayoutParams lp2 = new WindowManager.LayoutParams(
                    sGripSize, sGripSize, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp2.gravity = Gravity.TOP | Gravity.LEFT;
            lp2.x = sPos[0] - lp2.width / 2;
            lp2.y = sPos[1] - lp2.height / 2;
            sWm.addView(sGrip, lp2);

            // ③ 顶部工具栏：微调 / 保存 / 恢复默认 / 取消
            sBar = buildBar(app);
            WindowManager.LayoutParams lp3 = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp3.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp3.y = dp(app, 34);
            sWm.addView(sBar, lp3);

            sShowing = true;
            placeGrip(app);

            CallDiag.log("校准", "已进入接听键位置校准：初始中心=(" + sPos[0] + "," + sPos[1]
                    + ") 半径=" + sPos[2] + " 屏幕=" + sScreenW + "x" + sScreenH
                    + " 自定义=" + AnswerPointPrefs.isCustomized(app));
            Toast.makeText(app, "按住圆圈左边的灰色小球拖动，把绿圈套到微信的接听按钮上，再点「保存」",
                    Toast.LENGTH_LONG).show();

            sHandler.removeCallbacks(sAutoHide);
            sHandler.postDelayed(sAutoHide, AUTO_HIDE_MS);
        } catch (Throwable t) {
            CallDiag.log("校准", "显示校准浮层失败：" + t);
            hide();
        }
    }

    private static final Runnable sAutoHide = new Runnable() {
        @Override
        public void run() {
            if (!sShowing) return;
            CallDiag.log("校准", "校准超时（5 分钟）→ 自动收起，未保存");
            hide();
        }
    };

    public static synchronized void hide() {
        sHandler.removeCallbacks(sAutoHide);
        try {
            if (sLayer != null && sWm != null) sWm.removeViewImmediate(sLayer);
        } catch (Throwable ignore) {}
        try {
            if (sGrip != null && sWm != null) sWm.removeViewImmediate(sGrip);
        } catch (Throwable ignore) {}
        try {
            if (sBar != null && sWm != null) sWm.removeViewImmediate(sBar);
        } catch (Throwable ignore) {}
        sLayer = null;
        sGrip = null;
        sBar = null;
        sShowing = false;
    }

    // ---------------- 拖动把手 ----------------

    private static View buildGrip(final Context ctx) {
        View v = new View(ctx);
        v.setOnTouchListener(new View.OnTouchListener() {
            private float lastX, lastY;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = e.getRawX();
                        lastY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - lastX;
                        float dy = e.getRawY() - lastY;
                        lastX = e.getRawX();
                        lastY = e.getRawY();
                        moveBy(ctx, Math.round(dx), Math.round(dy));
                        return true;
                    default:
                        return true;
                }
            }
        });
        return v;
    }

    /** 把手跟着圆圈走：始终在圆圈左边，且始终留在屏幕内 */
    private static void placeGrip(Context ctx) {
        if (sGrip == null || sWm == null) return;
        try {
            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) sGrip.getLayoutParams();
            int gx = sPos[0] - Math.round(sPos[2] * 1.15f) - sGripSize / 2 - dp(ctx, 14);
            int half = sGripSize / 2;
            gx = clamp(gx, half, Math.max(half, sScreenW - half));
            int gy = clamp(sPos[1], half, Math.max(half, sScreenH - half));
            lp.x = gx - half;
            lp.y = gy - half;
            sWm.updateViewLayout(sGrip, lp);
            if (sLayer != null) {
                sLayer.mGripX = gx;
                sLayer.mGripY = gy;
            }
        } catch (Throwable ignore) {}
    }

    /** 拖动/微调后更新位置并重绘 */
    private static synchronized void moveBy(Context ctx, int dx, int dy) {
        if (!sShowing) return;
        int nx = clamp(sPos[0] + dx, sPos[2], sScreenW - sPos[2]);
        int ny = clamp(sPos[1] + dy, sPos[2], sScreenH - sPos[2]);
        if (nx == sPos[0] && ny == sPos[1]) return;
        sPos[0] = nx;
        sPos[1] = ny;
        placeGrip(ctx);
        if (sLayer != null) sLayer.invalidate();
    }

    /** 改圆圈大小（半径） */
    private static synchronized void resizeBy(Context ctx, int dr) {
        if (!sShowing) return;
        int min = Math.round(sScreenW * 0.03f);
        int max = Math.round(sScreenW * 0.20f);
        int nr = clamp(sPos[2] + dr, min, max);
        if (nr == sPos[2]) return;
        sPos[2] = nr;
        sPos[0] = clamp(sPos[0], sPos[2], sScreenW - sPos[2]);
        sPos[1] = clamp(sPos[1], sPos[2], sScreenH - sPos[2]);
        placeGrip(ctx);
        if (sLayer != null) sLayer.invalidate();
    }

    // ---------------- 顶部工具栏 ----------------

    private static View buildBar(final Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE222222);
        bg.setCornerRadius(dp(ctx, 14));
        box.setBackground(bg);
        box.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));

        TextView title = new TextView(ctx);
        title.setText("把绿圈套到微信的接听按钮上");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(15);
        title.setPadding(0, 0, 0, dp(ctx, 6));
        box.addView(title);

        int step = Math.max(4, Math.round(sScreenW * 0.005f));
        box.addView(arrowRow(ctx, step));
        box.addView(sizeRow(ctx));
        box.addView(actionRow(ctx));
        return box;
    }

    private static View arrowRow(final Context ctx, final int step) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(miniBtn(ctx, "←", new View.OnClickListener() {
            @Override public void onClick(View v) { moveBy(ctx, -step, 0); }
        }));
        row.addView(miniBtn(ctx, "→", new View.OnClickListener() {
            @Override public void onClick(View v) { moveBy(ctx, step, 0); }
        }));
        row.addView(miniBtn(ctx, "↑", new View.OnClickListener() {
            @Override public void onClick(View v) { moveBy(ctx, 0, -step); }
        }));
        row.addView(miniBtn(ctx, "↓", new View.OnClickListener() {
            @Override public void onClick(View v) { moveBy(ctx, 0, step); }
        }));
        return row;
    }

    private static View sizeRow(final Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 6), 0, 0);
        final int dr = Math.max(2, Math.round(sScreenW * 0.004f));
        row.addView(miniBtn(ctx, "圈大 +", new View.OnClickListener() {
            @Override public void onClick(View v) { resizeBy(ctx, dr); }
        }));
        row.addView(miniBtn(ctx, "圈小 −", new View.OnClickListener() {
            @Override public void onClick(View v) { resizeBy(ctx, -dr); }
        }));
        return row;
    }

    private static View actionRow(final Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 8), 0, 0);
        row.addView(miniBtn(ctx, "✓ 保存这个位置", 0xFF1B7F3B, new View.OnClickListener() {
            @Override public void onClick(View v) { save(v.getContext()); }
        }));
        row.addView(miniBtn(ctx, "恢复默认", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Context c = v.getContext();
                AnswerPointPrefs.reset(c);
                int[] p = AnswerPointPrefs.point(c, sScreenW, sScreenH);
                sPos[0] = p[0];
                sPos[1] = p[1];
                sPos[2] = p[2];
                placeGrip(c);
                if (sLayer != null) sLayer.invalidate();
                Toast.makeText(c, "已恢复成默认位置", Toast.LENGTH_SHORT).show();
            }
        }));
        row.addView(miniBtn(ctx, "✕ 取消", 0xFFB3261E, new View.OnClickListener() {
            @Override public void onClick(View v) { hide(); }
        }));
        return row;
    }

    /** 保存：把当前像素位置换算回"占屏幕的比例"存起来（换分辨率也不会跑偏） */
    private static synchronized void save(Context rawCtx) {
        final Context ctx = rawCtx.getApplicationContext();
        float xRatio = sPos[0] * 1f / sScreenW;
        float bottomRatio = (sScreenH - sPos[1]) * 1f / sScreenH;
        float radiusRatio = sPos[2] * 1f / sScreenW;
        AnswerPointPrefs.save(ctx, xRatio, bottomRatio, radiusRatio);
        CallDiag.log("校准", "已保存接听键位置：中心=(" + sPos[0] + "," + sPos[1] + ")"
                + " → 比例 横向 " + Math.round(xRatio * 1000) / 10f + "%，距底部 "
                + Math.round(bottomRatio * 1000) / 10f + "%，半径 "
                + Math.round(radiusRatio * 1000) / 10f + "%");
        Toast.makeText(ctx, "已保存：以后圈的位置和自动点击都按这里来", Toast.LENGTH_LONG).show();
        hide();
    }

    private static View miniBtn(Context ctx, String text, View.OnClickListener l) {
        return miniBtn(ctx, text, 0xFF37474F, l);
    }

    private static View miniBtn(Context ctx, String text, int color,
                                final View.OnClickListener l) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(ctx, 10));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ---------------- 绘制层 ----------------

    /** 画出绿圈 + 十字准星 + 拖动小球 + 实时坐标，样子和真实来电的指引一致（所见即所得） */
    private static class RingLayer extends View {

        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGrip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGripText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mDensity;
        private int mGripX, mGripY;

        RingLayer(Context c) {
            super(c);
            mDensity = getResources().getDisplayMetrics().density;
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setColor(0xFF22C55E);
            mRing.setStrokeWidth(9 * mDensity);

            mCross.setStyle(Paint.Style.STROKE);
            mCross.setColor(0xCC22C55E);
            mCross.setStrokeWidth(2 * mDensity);

            mGrip.setStyle(Paint.Style.FILL);
            mGrip.setColor(0xDD9E9E9E);

            mGripText.setColor(0xFFFFFFFF);
            mGripText.setTextSize(14 * mDensity);
            mGripText.setTextAlign(Paint.Align.CENTER);
            mGripText.setTypeface(Typeface.DEFAULT_BOLD);

            mTextBg.setColor(0xEE000000);
            mText.setColor(0xFFFFFFFF);
            mText.setTextSize(15 * mDensity);
            mText.setTextAlign(Paint.Align.CENTER);
            mText.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int cx = sPos[0], cy = sPos[1], r = sPos[2];
            canvas.drawColor(0x1A000000);
            float ringR = r * 1.15f;

            // 十字准星：方便对准圆心
            canvas.drawLine(cx - r * 2.2f, cy, cx + r * 2.2f, cy, mCross);
            canvas.drawLine(cx, cy - r * 2.2f, cx, cy + r * 2.2f, mCross);
            // 主圆环（和真实指引一样放大 1.15 倍，套在按钮外面更好认）
            canvas.drawCircle(cx, cy, ringR, mRing);
            canvas.drawCircle(cx, cy, 4 * mDensity, mCross);

            // 拖动小球：在圆圈**左边**，不压住微信的接听键
            float gr = sGripSize / 2f;
            canvas.drawLine(mGripX + gr, mGripY, cx - ringR, cy, mCross);
            canvas.drawCircle(mGripX, mGripY, gr, mGrip);
            Paint.FontMetrics gfm = mGripText.getFontMetrics();
            canvas.drawText("按住拖", mGripX, mGripY - (gfm.ascent + gfm.descent) / 2, mGripText);

            // 圆圈下方实时显示比例：万一还要反馈给开发，抄这一行就够了
            String info = "横向 " + Math.round(cx * 1000f / sScreenW) / 10f + "%"
                    + " · 距底部 " + Math.round((sScreenH - cy) * 1000f / sScreenH) / 10f + "%";
            float w = mText.measureText(info) + 28 * mDensity;
            float h = 30 * mDensity;
            float top = cy + ringR + 16 * mDensity;
            RectF box = new RectF(cx - w / 2, top, cx + w / 2, top + h);
            canvas.drawRoundRect(box, h / 2, h / 2, mTextBg);
            Paint.FontMetrics fm = mText.getFontMetrics();
            canvas.drawText(info, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2,
                    mText);
        }
    }

    // ---------------- 工具 ----------------

    private static int clamp(int v, int lo, int hi) {
        if (lo > hi) return (lo + hi) / 2;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** 物理屏幕尺寸（含导航栏区域）：和自动点击用的是同一套坐标基准 */
    private static int[] screenSize(Context ctx) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return new int[]{0, 0};
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }
}
