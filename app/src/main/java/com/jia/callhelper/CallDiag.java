package com.jia.callhelper;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 运行记录（诊断日志）。
 *
 * 为什么需要它：
 * 「微信来电没自动接听」这件事，从外面看只有一个结果——没接。但可能的断点有很多：
 *   ① 通知监听根本没收到微信来电通知；
 *   ② 收到了，但被判定成"普通消息"丢掉了；
 *   ③ 判定成来电了，但这个人不在自动接听名单里 / 总开关没开；
 *   ④ 名单也对，可无障碍拿不到微信界面（微信来电界面是自绘的，可能一个节点都没有）；
 *   ⑤ 拿不到界面时的坐标兜底点歪了；
 *   ⑥ 点对了，但屏幕锁着，手势被系统拦下了。
 * 没有记录就只能靠猜。所以这里把每一步的结论都写下来：
 * 用户复现一次，打开「设置 → 运行记录」就能看到卡在哪一环，不用再盲试。
 *
 * 实现上刻意做得极简：内存里保留最近若干条 + 追加写一个文本文件，
 * 不引第三方库、不发网络请求（本应用承诺不联网）。
 */
public final class CallDiag {

    private static final String FILE_NAME = "call_diag.log";
    /** 文件超过这个大小就整体重写，避免无限增长 */
    private static final long MAX_FILE_BYTES = 400 * 1024L;
    /** 内存里保留的行数上限（v1.12 从 500 提到 1500：用户希望记录多一点便于排查） */
    private static final int MAX_MEM_LINES = 1500;

    /**
     * 【v1.21】写文件改成攒批。
     *
     * 以前每 log() 一次就开一次 FileOutputStream + write + close。一次来电判定期
     * 密集的几十条记录会产生几十次系统调用，IO 线程几乎一直在醒着。
     * 现在攒到 {@link #FLUSH_DELAY_MS} 或缓冲区超过 {@link #FLUSH_HARD_BYTES} 才真正落盘，
     * 一次来电通常只剩 2~4 次写。
     *
     * 风险对冲：批写意味着进程被系统杀掉时，最后不到 FLUSH_DELAY_MS 的记录会丢。
     * 所以窗口刻意压到 400ms —— 比"来电判定期内一次决策链"短得多，
     * 既能合并同一次决策的连续日志，又不会丢掉关键几行。
     */
    private static final long FLUSH_DELAY_MS = 400L;
    private static final int FLUSH_HARD_BYTES = 8 * 1024;

    private static final Object LOCK = new Object();
    private static final Object IO_LOCK = new Object();
    private static final Deque<String> LINES = new ArrayDeque<String>();
    private static final StringBuilder SBUF = new StringBuilder(1024);
    private static volatile boolean sDirty;   // loadFromFile 期间是否已经写过日志
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private static volatile Context sApp;
    private static volatile Handler sIO;
    private static boolean sLoaded;

    private CallDiag() {}

    /** 在 Application/任意入口调用一次即可；重复调用无副作用 */
    public static synchronized void init(Context ctx) {
        if (ctx == null) return;
        sApp = ctx.getApplicationContext();
        // 【v1.21】加锁：以前两个线程同时进来可能开出两条 looper 线程，
        // 后面的 flush 就会排到不同的 Handler 上，顺序错乱、甚至互相覆盖文件。
        if (sIO == null) {
            HandlerThread t = new HandlerThread("call-diag");
            t.start();
            sIO = new Handler(t.getLooper());
        }
        if (sLoaded) return;
        sLoaded = true;
        loadFromFile();
    }

    public static void log(String tag, String msg) {
        String line = stamp() + " [" + tag + "] " + msg;
        synchronized (LOCK) {
            sDirty = true;
            LINES.addLast(line);
            while (LINES.size() > MAX_MEM_LINES) LINES.pollFirst();
        }
        appendToFile(line);
    }

    /**
     * 记一条「环境快照」：机型、系统、屏幕、微信版本、各项权限、名单与开关状态。
     *
     * 为什么要在每次来电时都记一次：出问题的大多是"换了个手机/升级了微信/权限被系统收回"，
     * 只看单条日志很难判断是不是环境变了。快照能把"当时这台手机到底什么状态"完整留档，
     * 用户复现一次后把记录发过来，就能直接比对。
     */
    public static void snapshot(Context ctx, String why) {
        if (ctx == null) return;
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("环境快照（").append(why).append("）");
            sb.append(" 机型=").append(android.os.Build.MANUFACTURER)
                    .append(" ").append(android.os.Build.MODEL);
            sb.append(" 系统=Android ").append(android.os.Build.VERSION.RELEASE)
                    .append("(API ").append(android.os.Build.VERSION.SDK_INT).append(")");
            // 【v1.21】以前这里用 getResources().getDisplayMetrics()，给的是"应用可用区"
            // （1220×2522，不含导航栏），而自动点击真正用的是物理屏 1220×2712。
            // 用户把日志发来排查坐标问题时，这两个数对不上，直接把判断带偏。
            int[] sz = Screen.realSize(ctx);
            sb.append(" 屏幕=").append(sz[0]).append("x").append(sz[1])
                    .append(" 密度=").append(ctx.getResources().getDisplayMetrics().density);
            sb.append(" 微信版本=").append(wechatVersion(ctx));
            sb.append(" 通知权限=").append(PermissionStatus.isNotificationListener(ctx));
            sb.append(" 无障碍=").append(PermissionStatus.isAccessibility(ctx));
            sb.append(" 悬浮窗=").append(PermissionStatus.isOverlay(ctx));
            sb.append(" | 家人数=").append(WhiteListManager.load(ctx).size())
                    .append(" 总开关=").append(WhiteListManager.prefs(ctx)
                            .getBoolean("auto_answer_master", false))
                    .append(" 屏幕指引=").append(WhiteListManager.prefs(ctx)
                            .getBoolean("guide_overlay", true));
        } catch (Throwable t) {
            sb.append(" 环境快照生成失败：").append(t);
        }
        log("环境", sb.toString());
    }

    /**
     * {@link #snapshot} 的异步版：把取包名/查权限/读偏好这些 IPC 挪到 IO 线程。
     *
     * 来电判定那条链跑在通知监听的主线程上，环境快照里有 PackageManager、
     * Settings.Secure 查询，全是跨进程调用，同步做会白白占用主线程几十毫秒。
     * 用户触发的手动查看仍走同步的 {@link #snapshot}，结果一致。
     */
    public static void snapshotAsync(final Context ctx, final String why) {
        if (ctx == null) return;
        final Handler io = sIO;
        if (io == null) {
            snapshot(ctx, why);
            return;
        }
        io.post(new Runnable() {
            @Override
            public void run() {
                snapshot(ctx, why);
            }
        });
    }

    /** 读取微信的版本号（拿不到就返回「未知」）。微信改版是自动点击失效的首要原因 */
    public static String wechatVersion(Context ctx) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo("com.tencent.mm", 0);
            return pi.versionName + "(" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 全部记录，按时间正序（最早的在上） */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (String l : LINES) sb.append(l).append('\n');
        }
        if (sb.length() == 0) {
            sb.append("（还没有记录。装好新版本后，接到一次微信电话再回来看这里。）");
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            sDirty = true;
        }
        // 【v1.21】必须同时丢掉待写的缓冲区，并且把已排队的 flush 撤销：
        // 否则 clear 之后那次 flush 会把刚刚删掉的内容又写回一个新文件。
        Handler io = sIO;
        if (io != null) io.removeCallbacks(FLUSH);
        synchronized (IO_LOCK) {
            SBUF.setLength(0);
        }
        if (sApp == null) return;
        try {
            File f = new File(sApp.getFilesDir(), FILE_NAME);
            if (f.exists()) f.delete();
        } catch (Exception ignore) {}
    }

    // ---------------- 内部 ----------------

    private static String stamp() {
        synchronized (FMT) {
            return FMT.format(new Date());
        }
    }

    private static File file() {
        return sApp == null ? null : new File(sApp.getFilesDir(), FILE_NAME);
    }

    /** 攒批缓冲区，只在 IO 线程触碰；由 {@link #IO_LOCK} 保护 */
    private static void appendToFile(final String line) {
        if (sApp == null) return; // 还没初始化：这条只留在内存里
        final Handler io = sIO;
        if (io == null) return;
        final boolean big;
        synchronized (IO_LOCK) {
            if (SBUF.length() == 0) {
                // 第一条：顺手排一个兜底 flush，避免后面没有新日志时这条迟迟不落盘
                io.postDelayed(FLUSH, FLUSH_DELAY_MS);
            }
            SBUF.append(line).append('\n');
            big = SBUF.length() >= FLUSH_HARD_BYTES;
        }
        if (big) {
            io.removeCallbacks(FLUSH);
            io.post(FLUSH);
        }
    }

    private static final Runnable FLUSH = new Runnable() {
        @Override
        public void run() {
            final String payload;
            synchronized (IO_LOCK) {
                if (SBUF.length() == 0) return;
                payload = SBUF.toString();
                SBUF.setLength(0);
            }
            writePayload(payload);
        }
    };

    private static void writePayload(String payload) {
        File f = file();
        if (f == null) return;
        try {
            boolean needRewrite = f.exists() && f.length() > MAX_FILE_BYTES;
            if (needRewrite) {
                // 超上限：不追加，直接用内存里的最新记录整体覆盖，等于保留尾部窗口。
                // 注意先在 LOCK 里拼完再进 IO_LOCK —— 别把两把锁嵌套起来。
                StringBuilder sb = new StringBuilder();
                synchronized (LOCK) {
                    for (String l : LINES) sb.append(l).append('\n');
                }
                payload = sb.toString();
            }
            synchronized (IO_LOCK) {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(f, !needRewrite);
                    fos.write(payload.getBytes("UTF-8"));
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
    }

    private static void loadFromFile() {
        final File f = file();
        if (f == null || !f.exists()) return;
        final Handler io = sIO;
        if (io == null) return;
        io.post(new Runnable() {
            @Override
            public void run() {
                // 【v1.21】如果加载期间已经写过新日志，就不要用旧文件内容去覆盖它们：
                // 以前无条件 LINES.clear() 会把刚记下的几行擦掉。
                if (sDirty) return;
                BufferedReader r = null;
                try {
                    r = new BufferedReader(
                            new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    String l;
                    synchronized (LOCK) {
                        LINES.clear();
                        while ((l = r.readLine()) != null) {
                            LINES.addLast(l);
                            while (LINES.size() > MAX_MEM_LINES) LINES.pollFirst();
                        }
                    }
                } catch (Exception ignore) {
                } finally {
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
        });
    }
}
