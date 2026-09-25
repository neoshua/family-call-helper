package com.jia.callhelper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 来电「屏幕指引」浮层。
 *
 * 为什么需要它：微信来电界面上，接听键是一个**没有文字的绿色圆钮**。老人看到满屏
 * 画面，往往不知道该点哪里；光靠语音念「点右下角绿色按钮」，看不见还是白搭。
 * 所以这里在屏幕最上层画一个醒目的绿色圆环，正好套在微信接听键的位置上，
 * 再加一个「点这里接听」的箭头标签。
 *
 * 关键设计（保证不挡老人操作）：
 * - 指示层整层 {@code FLAG_NOT_TOUCHABLE}：触摸直接穿透到下面的微信界面。
 *   圆圈只是"画上去"的，老人在圈上点一下，和点微信那个按钮是同一个坐标，
 *   一样有效——不会像以前的假界面那样"点了没反应"。
 * - 只有一个小巧的「停止提醒」按钮可点，位置在屏幕顶部中央，与微信底部的
 *   接听/挂断键完全不重叠。
 * - 整层 {@code FLAG_NOT_FOCUSABLE}：不抢输入焦点，不会让无障碍读不到微信界面。
 * - 圆圈位置来自真机截图实测：接听键中心在屏幕宽度 80.3%、距底部 11.4% 屏高处
 *   （见 {@link CallHelperAccessibilityService#answerPoint}），任何尺寸的手机都适用。
 *
 * 自动接听期间会先隐藏浮层（避免干扰点击），一旦自动点击失败会重新显示，
 * 这时才是真正需要老人自己动手的时候。
 */
public final class GuideOverlay {

    /** 设置里的开关：来电时是否在屏幕上圈出接听按钮 */
    public static final String PREF_KEY = "guide_overlay";

    private static WindowManager sWm;
    private static View sLayer;   // 指示层（不接收触摸）
    private static View sStopBar; // 停止按钮（唯一可点击的地方）
    private static boolean sShowing;
    /** 本次是否真的画了"圈住接听键"的绿圈（不是全屏来电界面时只提示、不画圈） */
    private static boolean sRingShown;
    /** 当前这次显示的身份标记：用于「只隐藏自己那一次」，避免试听的定时隐藏误伤真实来电 */
    private static Object sToken;

    private GuideOverlay() {}

    // ---------------- 对外接口 ----------------

    public static boolean isEnabled(Context ctx) {
        return WhiteListManager.prefs(ctx).getBoolean(PREF_KEY, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        WhiteListManager.prefs(ctx).edit().putBoolean(PREF_KEY, on).apply();
    }

    /** 是否有「显示在其他应用上层」权限 */
    public static boolean canOverlay(Context ctx) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    /** 来电时显示指引。没有权限 / 用户关掉了开关时静默跳过 */
    public static synchronized void show(Context ctx, String caller, boolean autoAnswer) {
        show(ctx, caller, autoAnswer, false, 0L);
    }

    /**
     * 来电时显示指引，并在顶部提示里画出自动接听倒计时。
     *
     * @param autoDeadlineAt 自动接听的截止时刻（毫秒时间戳）。传 0 表示没有自动接听。
     */
    public static synchronized void show(Context ctx, String caller, boolean autoAnswer,
                                         long autoDeadlineAt) {
        show(ctx, caller, autoAnswer, false, autoDeadlineAt);
    }

    /** 设置页「试听」用：此时并没有真实来电界面，强制画出圆圈只为了演示 */
    public static synchronized void showDemo(Context ctx, String caller) {
        show(ctx, caller, false, true, 0L);
    }

    /**
     * @param forceRing 强制画圆圈。真实来电时不要用：只有屏幕上真有微信接听键
     *                  才该画圈，否则会把老人指向一个空位置（见下面 ringOnly 的说明）。
     */
    private static synchronized void show(Context ctx, String caller, boolean autoAnswer,
                                          boolean forceRing, long autoDeadlineAt) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (!isEnabled(app)) return;
        // 正在校准接听键位置时不再画指引：两套圈叠在一起反而看不清，
        // 而且校准浮层自带工具栏，再叠一个「停止提醒」按钮会互相挡住。
        if (CalibrationOverlay.isShowing()) {
            CallDiag.log("指引", "正在校准接听键位置 → 本次不显示来电指引（避免两套圈重叠）");
            return;
        }
        if (!canOverlay(app)) {
            CallDiag.log("指引", "没有「显示在其他应用上层」权限，本次不显示屏幕指引");
            return;
        }
        // 【关键】只有「全屏来电界面」上才有微信的接听键。
        // 如果此刻屏幕上只有顶部横幅通知或下拉通知栏，右下角根本没有接听键，
        // 这时候画一个绿圈等于是骗人。这种情况只显示一条文字提示。
        boolean fullScreen = forceRing
                || (CallHelperAccessibilityService.get() != null
                    && CallHelperAccessibilityService.get().isFullScreenCallUi());
        if (!fullScreen && !forceRing) {
            CallDiag.log("指引", "当前不是全屏来电界面（只有通知/横幅）→ 本次只提示、不画接听圆圈");
        }
        hide();
        try {
            sWm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (sWm == null) return;
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            int[] p = CallHelperAccessibilityService.answerPoint(app);

            // ① 指示层：整层不接收触摸，事件穿透到微信
            GuideLayerView layerView = new GuideLayerView(
                    app, p[0], p[1], p[2], caller, autoAnswer, fullScreen);
            layerView.setAutoDeadline(autoDeadlineAt);
            View layer = layerView;
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
            sWm.addView(layer, lp1);
            sLayer = layer;
            sRingShown = fullScreen;

            // ② 停止按钮：能让老人/家人随时把声音关掉，不再有"关不掉"的情况
            sStopBar = buildStopBar(app);
            WindowManager.LayoutParams lp2 = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp2.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp2.y = dp(app, 60);
            sWm.addView(sStopBar, lp2);

            sShowing = true;
            sToken = new Object();
            CallDiag.log("指引", (fullScreen
                    ? "已在屏幕上圈出接听键：中心=(" + p[0] + "," + p[1] + ") 半径=" + p[2]
                    : "已显示来电提示（当前不是全屏界面，未画圆圈）")
                    + " 自动接听=" + autoAnswer);
        } catch (Throwable t) {
            CallDiag.log("指引", "显示屏幕指引失败：" + t);
            sShowing = false;
            sRingShown = false;
            sLayer = null;
            sStopBar = null;
            sToken = null;
        }
    }

    /** 取本次显示的身份标记，配合 {@link #hideIf(Object)} 使用 */
    public static synchronized Object token() {
        return sToken;
    }

    /**
     * 只隐藏「自己那一次」显示的浮层。
     * 试听会定时收起指引，若不判断身份，这个定时任务有可能在真实来电时把
     * 刚显示出来的指引一起收掉。
     */
    public static synchronized void hideIf(Object t) {
        if (t == null || t != sToken) return;
        hide();
    }

    /** 来电结束 / 已接通 / 用户关掉提醒时移除浮层 */
    public static synchronized void hide() {
        if (sLayer == null && sStopBar == null && !sShowing) return;
        try {
            if (sLayer != null && sWm != null) sWm.removeViewImmediate(sLayer);
        } catch (Throwable ignore) {}
        try {
            if (sStopBar != null && sWm != null) sWm.removeViewImmediate(sStopBar);
        } catch (Throwable ignore) {}
        sLayer = null;
        sStopBar = null;
        sShowing = false;
        sRingShown = false;
        sToken = null;
    }

    public static boolean isShowing() {
        return sShowing;
    }

    /** 当前屏幕上是否已经画着"圈住接听键"的绿圈（没有就说明只显示了提示） */
    public static boolean showingRing() {
        return sShowing && sRingShown;
    }

    // ---------------- 停止按钮 ----------------

    private static View buildStopBar(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView btn = new TextView(ctx);
        btn.setText("✕ 停止提醒");
        btn.setTextSize(17);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(ctx, 22), dp(ctx, 11), dp(ctx, 22), dp(ctx, 11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6333333);
        bg.setCornerRadius(dp(ctx, 24));
        btn.setBackground(bg);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallSessionManager.stopByUser(v.getContext());
            }
        });
        box.addView(btn);

        TextView tip = new TextView(ctx);
        tip.setText("声音太吵就点这里");
        tip.setTextSize(12);
        tip.setTextColor(0xCCFFFFFF);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(ctx, 4), 0, 0);
        box.addView(tip);
        return box;
    }

    // ---------------- 指示层绘制 ----------------

    private static class GuideLayerView extends View {

        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPulse = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTipText = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final float mCx, mCy, mR;
        private final String mCaller;
        private final boolean mAuto;
        private final boolean mFullScreen; // 是否画圈（只有全屏来电界面才画）
        private final float mDensity;
        /** 自动接听截止时刻（毫秒时间戳，0 = 没有自动接听）。用于在提示里画倒计时 */
        private long mAutoDeadlineAt = 0L;

        GuideLayerView(Context c, int cx, int cy, int r, String caller, boolean auto,
                       boolean fullScreen) {
            super(c);
            mCx = cx;
            mCy = cy;
            mR = r;
            mCaller = caller == null ? "家人" : caller;
            mAuto = auto;
            mFullScreen = fullScreen;
            mDensity = getResources().getDisplayMetrics().density;

            mRing.setStyle(Paint.Style.STROKE);
            mRing.setColor(0xFF22C55E);
            mRing.setStrokeWidth(9 * mDensity);
            mRing.setStrokeCap(Paint.Cap.ROUND);

            mPulse.setStyle(Paint.Style.STROKE);
            mPulse.setColor(0xFF22C55E);
            mPulse.setStrokeWidth(6 * mDensity);

            mLabel.setColor(0xFF16A34A);
            mLabelText.setColor(Color.WHITE);
            mLabelText.setTypeface(Typeface.DEFAULT_BOLD);
            // 【v1.12】适老化：圈上的「点这里接听」放大到 34sp（原 28sp）。
            // 用户反馈"提示文案太小了"，老人戴老花镜也未必看得清，宁可大一点。
            mLabelText.setTextSize(34);
            mLabelText.setTextAlign(Paint.Align.CENTER);

            mArrow.setColor(0xFF16A34A);
            mArrow.setStyle(Paint.Style.FILL);

            mTipBg.setColor(0xE6000000);
            mTipText.setColor(Color.WHITE);
            mTipText.setTypeface(Typeface.DEFAULT_BOLD);
            // 【v1.12】顶部提示同样放大：17sp → 26sp，并加粗。
            // 这行字是老人最先看到的信息（谁打来的），太小等于没说。
            mTipText.setTextSize(26);
            mTipText.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // 极淡压暗，让绿色圆圈更醒目（不影响看微信界面）
            canvas.drawColor(0x12000000);

            if (mFullScreen) {
                float ringR = mR * 1.15f;

                // 脉冲圈：向外扩散，抓注意力
                long t = System.currentTimeMillis() % 1500L;
                float k = t / 1500f;
                int alpha = (int) (230 * (1f - k));
                if (alpha > 0) {
                    mPulse.setAlpha(alpha);
                    canvas.drawCircle(mCx, mCy, ringR + mR * 0.55f * k, mPulse);
                }
                // 主圆环
                canvas.drawCircle(mCx, mCy, ringR, mRing);

                // 「点这里接听」标签 + 指向圆环的箭头
                float arrowTipY = mCy - ringR - 8 * mDensity;
                float arrowBaseY = arrowTipY - 38 * mDensity;
                Path arrow = new Path();
                arrow.moveTo(mCx, arrowTipY);
                arrow.lineTo(mCx - 20 * mDensity, arrowBaseY);
                arrow.lineTo(mCx + 20 * mDensity, arrowBaseY);
                arrow.close();
                canvas.drawPath(arrow, mArrow);

                String label = "点这里接听";
                float labelW = mLabelText.measureText(label) + 44 * mDensity;
                float labelH = 52 * mDensity;
                float labelBottom = arrowBaseY - 6 * mDensity;
                RectF box = new RectF(mCx - labelW / 2, labelBottom - labelH,
                        mCx + labelW / 2, labelBottom);
                float radius = labelH / 2;
                canvas.drawRoundRect(box, radius, radius, mLabel);
                Paint.FontMetrics fm = mLabelText.getFontMetrics();
                float baseline = box.centerY() - (fm.ascent + fm.descent) / 2;
                canvas.drawText(label, mCx, baseline, mLabelText);
            }

            // 顶部提示：谁打来的 + 怎么操作。
            // 画圈时提示跟着圆圈居中；只提示时（屏幕上还没有接听键）居中在屏幕顶部。
            // 【v1.12】字号放大后改成「最多两行」绘制：第一行"谁打来的"，
            // 第二行"该怎么做"。字大 + 分行，远看也清楚。
            float anchorX = mFullScreen ? mCx : getWidth() / 2f;
            String line1 = tipLine1();
            String line2 = tipLine2();
            float padX = 36 * mDensity;
            float lineH = mTipText.getFontMetrics().descent
                    - mTipText.getFontMetrics().ascent + 12 * mDensity;
            float tipW = Math.max(mTipText.measureText(line1),
                    line2.isEmpty() ? 0 : mTipText.measureText(line2)) + padX * 2;
            float tipH = lineH * (line2.isEmpty() ? 1 : 2) + 26 * mDensity;
            float tipTop = 150 * mDensity;
            // 防呆：窄屏上文字可能超出屏幕，收窄并夹回可视区域
            float maxW = getWidth() - 24 * mDensity;
            if (tipW > maxW) tipW = maxW;
            float left = anchorX - tipW / 2;
            if (left < 12 * mDensity) left = 12 * mDensity;
            if (left + tipW > getWidth() - 12 * mDensity) {
                left = Math.max(12 * mDensity, getWidth() - 12 * mDensity - tipW);
            }
            RectF tipBox = new RectF(left, tipTop, left + tipW, tipTop + tipH);
            canvas.drawRoundRect(tipBox, 20 * mDensity, 20 * mDensity, mTipBg);

            Paint.FontMetrics tfm = mTipText.getFontMetrics();
            float firstBaseline = tipBox.top + 13 * mDensity - tfm.ascent;
            canvas.drawText(line1, tipBox.centerX(), firstBaseline, mTipText);
            if (!line2.isEmpty()) {
                canvas.drawText(line2, tipBox.centerX(), firstBaseline + lineH, mTipText);
            }

            // 脉冲动画：每 40ms 重绘一帧（视图移除后自动停止）
            if (isAttachedToWindow()) postInvalidateDelayed(40L);
        }

        /** 第一行：谁打来的（大号、最关键的一行） */
        private String tipLine1() {
            return mCaller + " 来电话了";
        }

        /**
         * 第二行：现在该做什么。
         * 提示语要跟"当前屏幕上到底有没有接听键"对上，不能指着一个不存在的按钮说话。
         * 【v1.12】自动接听时把剩余秒数写在这儿，老人抬眼就能看到倒计时。
         */
        private String tipLine2() {
            if (mAuto) {
                long remain = mAutoDeadlineAt - System.currentTimeMillis();
                int sec = (int) Math.max(0, (remain + 999) / 1000);
                if (mAutoDeadlineAt > 0 && sec > 0) {
                    return "正在自动接听，还剩 " + sec + " 秒";
                }
                return "正在自动接听…";
            }
            return mFullScreen
                    ? "请点绿色圆圈里的按钮"
                    : "请点一下屏幕上的微信来电";
        }

        /** 自动接听截止时刻（0 = 没有自动接听）。由外部设置，用于画倒计时 */
        void setAutoDeadline(long at) {
            mAutoDeadlineAt = at;
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
