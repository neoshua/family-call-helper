package com.jia.callhelper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * 【v1.22】真·"看一眼屏幕"：从截图里把微信来电页底部那两个圆钮找出来。
 *
 * <h3>为什么要有这个类</h3>
 * 到 v1.21 为止，接听键的位置归根到底是**猜**出来的：
 * <ol>
 *   <li>默认比例（横向 80.2%、距底部 11.4%）——按一台实机的截图量出来，跨机型必然有偏差；</li>
 *   <li>用户手动校准——解决了"这台机偏差多大"，但仍是"上次量到的一个数"。</li>
 * </ol>
 * 只要屏幕上的东西发生变化（微信改版、换成视频来电而不是语音来电、
 * 换了导航方式、屏幕矮了一截），存下来的比例就不再是按钮的真实位置，
 * 于是出现"明明有圈、也点了、就是接不通"。
 *
 * <p>而且微信 8.0.78 的来电页是**整页自绘**的，无障碍一个节点都读不到，
 * 连"对了 Cooper 一次机会"都没有。
 *
 * <h3>这条路为什么稳</h3>
 * 微信来电页底部那两个圆钮的颜色是写死的：左边红色「挂断」、右边绿色「接听」。
 * 无障碍服务本身有截屏能力（{@code AccessibilityService.takeScreenshot}，Android 11+），
 * 全程本地看图、不联网、不落盘 —— 正好符合这个 App "不出设备"的底线。
 * 找到绿色圆钮，接听键的位置就不再是估算值，而是**亲眼看到的圆心**。
 *
 * <h3>坐标系</h3>
 * 传进来的 {@code Bitmap} 会被统一缩到 420px 宽以内再分析（省内存、也快），
 * 算出来的结果再按原比例放大回**物理屏坐标**（{@code getRealMetrics} 的那套）。
 */
public final class ScreenLook {

    /** 分析用的最大宽度：再宽对精度也没额外好处，徒增耗时 */
    private static final int MAX_WORK_W = 420;

    /** 只看屏幕下半部分：来电页的接听/挂断钮从来都在下面 */
    private static final float SCAN_TOP_RATIO = 0.42f;

    private ScreenLook() {}

    /** 一个色块的识别结果（物理屏坐标） */
    public static final class Disc {
        public final int cx;
        public final int cy;
        /** 半径（外接矩形宽高的均值的一半） */
        public final int r;
        /** 外接矩形的填充率（0~100）。实心圆约 78，空心圆环会低得多 */
        public final int fillPct;

        Disc(int cx, int cy, int r, int fillPct) {
            this.cx = cx;
            this.cy = cy;
            this.r = r;
            this.fillPct = fillPct;
        }

        @Override
        public String toString() {
            return "中心=(" + cx + "," + cy + ") 半径=" + r + " 填充率=" + fillPct + "%";
        }
    }

    /** 一次"看"的结果 */
    public static final class Result {
        /** 绿色接听钮，没找到就是 null */
        public final Disc green;
        /** 红色挂断钮，没找到就是 null */
        public final Disc red;
        public final int screenW;
        public final int screenH;
        /** 给「运行记录」用的一句话 */
        public final String note;

        Result(Disc green, Disc red, int w, int h, String note) {
            this.green = green;
            this.red = red;
            this.screenW = w;
            this.screenH = h;
            this.note = note;
        }

        /**
         * 是否像是"真的来电在响"的全屏界面。
         *
         * <p>底部同时看到一个绿色实心大圆钮和一个红色实心大圆钮 ——
         * 微信全屏来电页就长这样。只是"打开微信""打开聊天框"时屏幕上不存在这两个钮，
         * 于是"打开微信就被误报来电"的问题从根上消失了。
         */
        public boolean looksLikeRinging() {
            return green != null && red != null;
        }
    }

    /**
     * 行数据源：只有 {@code Bitmap} 一种实现，单独抽出来是为了能在电脑上
     * 用一张合成图跑一遍识别逻辑（见 {@link #lookRows}）。
     */
    interface RowSource {
        int width();

        int height();

        /** 把第 y 行 ARGB 数据填进 out（长度 = width） */
        void row(int y, int[] out);
    }

    /**
     * 把一张截图分析成 {@link Result}。
     *
     * @param shot     截图（可以是 hardware bitmap，内部会先转成可读取的软件位图）
     * @param screenW  这台机器的物理屏宽（{@code getRealMetrics} 的值）
     * @param screenH  这台机器的物理屏高
     */
    public static Result look(Bitmap shot, int screenW, int screenH) {
        Bitmap work = null;
        try {
            if (shot == null || screenW <= 0 || screenH <= 0) return null;
            int sw = shot.getWidth(), sh = shot.getHeight();
            if (sw <= 0 || sh <= 0) return null;

            int dw = Math.min(sw, MAX_WORK_W);
            int dh = Math.max(1, Math.round(sh * (dw / (float) sw)));
            // 即便源图是 hardware bitmap（不能直接 getPixels），画到软件位图上就能读了，
            // 顺带完成缩放，避免为了一张 1220×2712 的图去分配十几 MB。
            work = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(work);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(shot, new Rect(0, 0, sw, sh), new Rect(0, 0, dw, dh), p);

            return lookRows(new BitmapRowSource(work), screenW, screenH);
        } catch (Throwable t) {
            CallDiag.log("看图", "截图识别异常：" + t);
            return null;
        } finally {
            if (work != null && work != shot) {
                try { work.recycle(); } catch (Throwable ignore) {}
            }
        }
    }

    /** 对一张已经缩好尺寸、可以直接读像素的图做分析 */
    static Result lookRows(final RowSource src, int screenW, int screenH) {
        try {
            int w = src.width(), h = src.height();
            if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return null;
            float sx = screenW / (float) w;
            float sy = screenH / (float) h;
            Acc g = new Acc(h);
            Acc rr = new Acc(h);
            scan(src, g, rr);
            Disc green = extract(g, sx, sy, screenW, screenH, "绿");
            Disc red = extract(rr, sx, sy, screenW, screenH, "红");
            return new Result(green, red, screenW, screenH, describe(green, red, screenW, screenH));
        } catch (Throwable t) {
            CallDiag.log("看图", "截图识别异常：" + t);
            return null;
        }
    }

    private static final class BitmapRowSource implements RowSource {
        private final Bitmap bmp;

        BitmapRowSource(Bitmap bmp) {
            this.bmp = bmp;
        }

        @Override
        public int width() {
            return bmp.getWidth();
        }

        @Override
        public int height() {
            return bmp.getHeight();
        }

        @Override
        public void row(int y, int[] out) {
            bmp.getPixels(out, 0, bmp.getWidth(), 0, y, bmp.getWidth(), 1);
        }
    }

    // ---------------- 扫描 ----------------

    /** 逐行统计：每行最长的一段连续同色，以及该行命中了多少个点 */
    private static final class Acc {
        final int h;
        final int[] len;   // 该行最长连续段的长度（工作坐标）
        final int[] st;    // 该段的起始 x
        final int[] en;    // 该段的结束 x
        final int[] cnt;   // 该行命中点数（按 xStep 抽样后的个数）

        Acc(int h) {
            this.h = h;
            this.len = new int[h];
            this.st = new int[h];
            this.en = new int[h];
            this.cnt = new int[h];
        }
    }

    static void scan(RowSource src, Acc g, Acc rr) {
        int w = src.width();
        int h = src.height();
        int yStart = (int) (h * SCAN_TOP_RATIO);
        int[] row = new int[w];
        int xStep = 2;
        for (int y = yStart; y < h; y++) {
            src.row(y, row);
            int gSt = -1, gLast = -1, gBest = 0, gBestSt = 0, gBestEn = 0, gCnt = 0;
            int rSt = -1, rLast = -1, rBest = 0, rBestSt = 0, rBestEn = 0, rCnt = 0;
            for (int x = 0; x < w; x += xStep) {
                int c = row[x];
                boolean isG = isAnswerGreen(c);
                boolean isR = isDeclineRed(c);
                if (isG) {
                    gCnt++;
                    if (gSt < 0) gSt = x;
                    gLast = x;
                } else if (gSt >= 0) {
                    int len = gLast - gSt + xStep;
                    if (len > gBest) { gBest = len; gBestSt = gSt; gBestEn = gLast + xStep; }
                    gSt = -1;
                }
                if (isR) {
                    rCnt++;
                    if (rSt < 0) rSt = x;
                    rLast = x;
                } else if (rSt >= 0) {
                    int len = rLast - rSt + xStep;
                    if (len > rBest) { rBest = len; rBestSt = rSt; rBestEn = rLast + xStep; }
                    rSt = -1;
                }
            }
            if (gSt >= 0) {
                int len = gLast - gSt + xStep;
                if (len > gBest) { gBest = len; gBestSt = gSt; gBestEn = gLast + xStep; }
            }
            if (rSt >= 0) {
                int len = rLast - rSt + xStep;
                if (len > rBest) { rBest = len; rBestSt = rSt; rBestEn = rLast + xStep; }
            }
            g.len[y] = gBest; g.st[y] = gBestSt; g.en[y] = gBestEn; g.cnt[y] = gCnt;
            rr.len[y] = rBest; rr.st[y] = rBestSt; rr.en[y] = rBestEn; rr.cnt[y] = rCnt;
        }
    }

    /**
     * 微信接听键的绿（#07C160 系）：绿色为主导通道，且明显压过红蓝。
     * 不用 HSV 转换，纯整数比较 —— 每行几百个点，转换开销没必要。
     */
    private static boolean isAnswerGreen(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return g >= 130 && g >= r + 90 && g >= b + 30;
    }

    /** 微信挂断键的红（#FA5151 系）：红色为主导通道，且明显压过绿蓝 */
    private static boolean isDeclineRed(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r >= 120 && r >= g + 70 && r >= b + 70;
    }

    // ---------------- 从行统计里还原出一个圆钮 ----------------

    /**
     * 找出这一色最大的那坨：以"最长的一横"作为直径所在的行，
     * 上下各走一段距离把整个圆包进来，取外接矩形的中心。
     *
     * <p>用"最长横段"而不是"全部命中点的包围盒"，是为了不被下面的文字带偏 ——
     * 圆钮下方如果还有一行字，它那一行的连续段很短，抢不到"最长"的位置。
     */
    private static Disc extract(Acc a, float sx, float sy, int screenW, int screenH, String name) {
        int bestY = -1, bestLen = 0;
        int yStart = (int) (a.h * SCAN_TOP_RATIO);
        for (int y = yStart; y < a.h; y++) {
            if (a.len[y] > bestLen) { bestLen = a.len[y]; bestY = y; }
        }
        if (bestY < 0 || bestLen <= 0) return null;

        float centerX = (a.st[bestY] + a.en[bestY]) / 2f;
        float tolerance = bestLen * 0.4f;

        int yTop = bestY;
        // 圆形垂直方向的高度不可能超过自己的直径，超过 bid 倍就只能说明下面粘了别的东西
        // （典型：按钮下面还跟着一行同色的小字）。走满这个行数就停。
        int maxRows = Math.max(4, Math.round(bestLen * 1.3f));
        while (yTop - 1 >= yStart && bestY - (yTop - 1) <= maxRows
                && isRowOfSameDisc(a, yTop - 1, centerX, tolerance, bestLen)) yTop--;
        int yBot = bestY;
        while (yBot + 1 < a.h && (yBot + 1) - bestY <= maxRows
                && isRowOfSameDisc(a, yBot + 1, centerX, tolerance, bestLen)) yBot++;

        // 在这段行区间里重新取左右边界（只统计够宽的行，排除边缘偶尔的杂色）
        int minX = Integer.MAX_VALUE, maxX = -1;
        int hits = 0;
        int rows = 0;
        for (int y = yTop; y <= yBot; y++) {
            if (a.len[y] < bestLen * 0.35f) continue;
            if (a.st[y] < minX) minX = a.st[y];
            if (a.en[y] > maxX) maxX = a.en[y];
            hits += a.cnt[y];
            rows++;
        }
        if (maxX <= minX || rows < 3) return null;

        float bw = (maxX - minX) * sx;
        float bh = (yBot - yTop + 1) * sy;
        float cx = (minX + maxX) / 2f * sx;
        float cy = (yTop + yBot) / 2f * sy;
        int r = Math.round((bw + bh) / 4f);

        // ① 尺寸：微信来电页的圆钮直径大约是屏宽的 20%（1220 宽屏上约 250px）
        if (bw < screenW * 0.06f || bw > screenW * 0.45f) return null;
        // ② 形状：接近正圆，细长的一条肯定是别的东西
        float aspect = bw / bh;
        if (aspect < 0.55f || aspect > 1.8f) return null;
        // ③ 位置：必须在下半屏。被误伤的其它绿色元素大多不在那儿
        if (cy < screenH * 0.45f || cy > screenH * 0.99f) return null;

        float boxArea = (bw / sx) * (bh / sy);
        int fill = boxArea > 0 ? Math.round(hits * 2f / boxArea * 100f) : 0;
        // ④ 实心度：一个实心圆的填充率约 78%，空心圆环通常低于 35%
        if (fill < 45) return null;

        return new Disc(Math.round(cx), Math.round(cy), r, Math.min(fill, 100));
    }

    private static boolean isRowOfSameDisc(Acc a, int y, float centerX, float tol, float bestLen) {
        if (a.len[y] <= 0) return false;
        if (a.len[y] < bestLen * 0.35f) return false;
        float c = (a.st[y] + a.en[y]) / 2f;
        return Math.abs(c - centerX) <= tol;
    }

    private static String describe(Disc green, Disc red, int w, int h) {
        StringBuilder sb = new StringBuilder();
        if (green == null && red == null) {
            return "截图里没找到接听/挂断圆钮（屏幕=" + w + "x" + h + "）";
        }
        if (green != null) sb.append("绿色接听钮 ").append(green);
        else sb.append("未找到绿色接听钮");
        if (red != null) sb.append("；红色挂断钮 ").append(red);
        else sb.append("；未找到红色挂断钮");
        return sb.toString();
    }
}
