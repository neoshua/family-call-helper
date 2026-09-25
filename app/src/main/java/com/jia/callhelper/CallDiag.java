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
    private static final long MAX_FILE_BYTES = 200 * 1024L;
    private static final int MAX_MEM_LINES = 500;

    private static final Object LOCK = new Object();
    private static final Deque<String> LINES = new ArrayDeque<String>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private static Context sApp;
    private static Handler sIO;
    private static boolean sLoaded;

    private CallDiag() {}

    /** 在 Application/任意入口调用一次即可；重复调用无副作用 */
    public static void init(Context ctx) {
        if (ctx == null) return;
        sApp = ctx.getApplicationContext();
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
            LINES.addLast(line);
            while (LINES.size() > MAX_MEM_LINES) LINES.pollFirst();
        }
        appendToFile(line);
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

    private static void appendToFile(final String line) {
        if (sApp == null) return; // 还没初始化：这条只留在内存里
        final Handler io = sIO;
        if (io == null) return;
        io.post(new Runnable() {
            @Override
            public void run() {
                File f = file();
                if (f == null) return;
                try {
                    boolean needRewrite = f.exists() && f.length() > MAX_FILE_BYTES;
                    if (needRewrite) {
                        StringBuilder sb = new StringBuilder();
                        synchronized (LOCK) {
                            for (String l : LINES) sb.append(l).append('\n');
                        }
                        FileOutputStream fos = new FileOutputStream(f, false);
                        fos.write(sb.toString().getBytes("UTF-8"));
                        fos.close();
                        return;
                    }
                    FileOutputStream fos = new FileOutputStream(f, true);
                    fos.write((line + "\n").getBytes("UTF-8"));
                    fos.close();
                } catch (Exception ignore) {}
            }
        });
    }

    private static void loadFromFile() {
        final File f = file();
        if (f == null || !f.exists()) return;
        final Handler io = sIO;
        if (io == null) return;
        io.post(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    String l;
                    synchronized (LOCK) {
                        LINES.clear();
                        while ((l = r.readLine()) != null) {
                            LINES.addLast(l);
                            while (LINES.size() > MAX_MEM_LINES) LINES.pollFirst();
                        }
                    }
                    r.close();
                } catch (Exception ignore) {}
            }
        });
    }
}
