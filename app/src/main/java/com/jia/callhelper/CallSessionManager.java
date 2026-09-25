package com.jia.callhelper;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 来电会话管理（全局单例状态机）。
 *
 * 一次微信来电的处理流程：
 * 1. 通知监听或无障碍服务发现来电 → startCall()
 * 2. 语音播报「谁来电了」+ 震动；手机没有中文语音引擎时用铃声兜底
 * 3. 白名单家人 + 自动接听开关都满足 → 等待 N 秒后自动点击微信的「接听」
 * 4. 没点成功 → 响铃 + 震动 + 语音提醒：请自己点微信上的接听
 * 5. 无障碍检测到已接通 / 通话结束 / 超时 → 清理一切
 *
 * ⚠️ 设计要点（v1.8 起）：**本应用不再显示任何自己的界面**。
 *
 * 以前来电时会弹一个我们自己的全屏「接听大按钮」界面，老人在那个界面上按了之后，
 * 我们再去模拟点击微信里真正的接听键。这多出来的一层不仅没用，还有害：
 *   - 那个按钮是假的，按了不能直接接听，只是"请求"我们去点微信，容易误会成 App 没反应；
 *   - 真正的接听按钮只有一个，就在微信里。让老人直接看着微信的真界面反而更清楚；
 *   - 锁屏时两层界面叠在一起，视觉和操作都乱。
 * 所以现在只做两件事：**听**（发现微信来电）和 **点**（模拟点击微信的接听）。
 * 点击时仍会保留一条高优先级通知：它能让锁屏亮屏、并把微信通话界面带到前台，
 * 否则屏幕不亮、微信界面不在最前，点击是落不到微信上的。
 */
public class CallSessionManager {

    public static class Session {
        public final String caller;
        public final boolean video;
        /** 微信来电通知的 contentIntent：可拉起微信真实的通话界面（点击接听要靠它） */
        public final PendingIntent openIntent;
        public final long startAt = System.currentTimeMillis();
        public volatile boolean autoAnswer = false;
        public volatile long autoAnswerAt;
        public volatile boolean ended = false;   // 整个会话结束
        public volatile boolean handled = false; // 接听流程已启动 / 已处理

        Session(String caller, boolean video, PendingIntent openIntent) {
            this.caller = caller;
            this.video = video;
            this.openIntent = openIntent;
        }
    }

    public static final int DEFAULT_AUTO_DELAY_SEC = 8;

    private static final String CHANNEL_ID = "call_notify";
    private static final int CALL_NOTIFY_ID = 2001;
    private static final long[] VIBRATE_PATTERN = {0, 700, 500, 700, 500};
    /** 自动接听重试次数与间隔：微信界面常比通知晚几百毫秒出现，需要重试 */
    private static final int CLICK_ATTEMPTS = 8;
    private static final long CLICK_INTERVAL_MS = 800L;

    private static Session sSession;
    private static Context sApp;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Vibrator sVibrator;
    private static MediaPlayer sRingtone;
    private static PowerManager.WakeLock sWakeLock;
    private static int sAnnounceCount = 0;

    // ---------------- 对外接口 ----------------

    /** 通知监听 / 无障碍发现微信来电时调用 */
    public static synchronized void startCall(Context ctx, String caller, boolean video,
                                              PendingIntent openIntent) {
        Context app = ctx.getApplicationContext();
        CallDiag.init(app);
        Session old = sSession;
        // 同一个人的重复通知（微信会刷新来电通知）直接忽略。
        // 注意这里不排除 handled 的会话：自动接听的点击重试正在进行时，
        // 若因一条刷新通知就重开会话，会把接听流程打断甚至重复点击。
        if (old != null && !old.ended && old.caller != null
                && old.caller.equals(caller)
                && System.currentTimeMillis() - old.startAt < 15_000L) {
            return;
        }
        cleanup(app);

        sApp = app;
        sSession = new Session(caller, video, openIntent);
        sAnnounceCount = 0;
        WeChatClicker.reset();

        WhiteListManager.Entry match = WhiteListManager.match(app, caller);
        int delay = WhiteListManager.prefs(app)
                .getInt("auto_delay_sec", DEFAULT_AUTO_DELAY_SEC);
        // 自动接听需同时满足：总开关开启 + 该联系人标记了自动接听
        boolean master = WhiteListManager.prefs(app).getBoolean("auto_answer_master", false);
        if (match != null && match.auto && master) {
            sSession.autoAnswer = true;
            sSession.autoAnswerAt = System.currentTimeMillis() + delay * 1000L;
        }
        // 把「这次为什么接 / 为什么不接」记下来：这是排查「没自动接听」的第一现场
        StringBuilder why = new StringBuilder();
        why.append("来电：").append(caller).append("（").append(video ? "视频" : "语音").append("）")
                .append(" 名单命中=").append(match != null ? match.name : "无")
                .append(" 该联系人开自动接听=").append(match != null && match.auto)
                .append(" 总开关=").append(master);
        if (sSession.autoAnswer) {
            why.append(" → ").append(delay).append(" 秒后自动接听");
        } else if (match == null) {
            why.append(" → 只播报：这个人不在家人名单里");
        } else if (!match.auto) {
            why.append(" → 只播报：该联系人的「自动接听」没打开");
        } else {
            why.append(" → 只播报：设置页的「自动接听」总开关没打开");
        }
        CallDiag.log("来电", why.toString());

        acquireWakeLock(app);
        startVibration(app);
        TtsSpeaker.init(app);
        announce();
        if (!TtsSpeaker.isUsable()) {
            startRingtone(app); // 手机没有中文语音引擎时，退回响铃
        }
        // 只在拿得到微信通知时发：目的不是"弹我们的界面"，
        // 而是亮屏 + 把微信真实的通话界面带到前台，好让模拟点击能落到微信上
        if (openIntent != null) {
            postCallNotification(app, openIntent);
        }

        sHandler.postDelayed(sAnnounceLoop, 4000L);
        if (sSession.autoAnswer) {
            sHandler.postDelayed(sAutoRun, delay * 1000L);
        }
        sHandler.postDelayed(sTimeout, 180_000L);
    }

    /** 无障碍服务看到微信来电界面时调用（可能与通知重复触发，内部自动去重） */
    public static synchronized void onIncomingViaA11y(Context ctx, String caller, boolean video) {
        Session s = sSession;
        if (s != null && !s.ended) return; // 通知已经先触发了，忽略
        startCall(ctx, caller, video, null);
    }

    /**
     * 开始接听：把微信通话界面带到前台，然后模拟点击微信里真正的「接听」键。
     * 本应用自己的界面已经没有了，这里点的是微信的按钮。
     */
    public static synchronized void performAccept(boolean auto) {
        Session s = sSession;
        if (s == null || s.handled || s.ended) return;
        s.handled = true;
        stopSoundsAndVibration();
        cancelNotification();

        final PendingIntent pi = s.openIntent;
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Session cur = sSession;
                if (cur == null || cur.ended) return;

                // 0) 先记录现场：锁屏状态下模拟点击会被系统拦下，这是「点了没反应」的常见原因
                boolean locked = false;
                try {
                    KeyguardManager km = (KeyguardManager) sApp.getSystemService(Context.KEYGUARD_SERVICE);
                    locked = km != null && km.isKeyguardLocked();
                } catch (Exception ignore) {}
                CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();
                boolean wechatFront = svc != null && svc.isWeChatForeground();
                CallDiag.log("接听", "准备接听：无障碍=" + (svc != null)
                        + " 微信在前台=" + wechatFront + " 锁屏=" + locked);

                // 1) 微信通话界面不在最前面时，用微信自己的通知跳转把它拉起来。
                //    界面不到前台，任何点击都落不到微信的接听键上。
                if (!wechatFront && pi != null) {
                    try {
                        pi.send();
                        CallDiag.log("接听", "微信不在前台，已发送通知跳转尝试拉起微信通话界面");
                    } catch (Exception e) {
                        CallDiag.log("接听", "拉起微信失败：" + e);
                    }
                }

                // 2) 交给点击器：三级定位 + 校验 + 失败兜底
                WeChatClicker.answerWithRetry(CLICK_ATTEMPTS, CLICK_INTERVAL_MS,
                        new WeChatClicker.Callback() {
                            @Override
                            public void onResult(boolean clicked) {
                                if (clicked) {
                                    CallDiag.log("接听", "接听流程结束：已接上或已尽力");
                                } else {
                                    onAcceptFailed();
                                }
                            }
                        });
            }
        }, 500L);
        // 接通后由无障碍检测到「静音/免提」按钮 → onWeChatCallAnswered 清理；另有 3 分钟超时兜底
    }

    /** 无障碍检测到通话已接通（出现静音/免提按钮） */
    public static synchronized void onWeChatCallAnswered(Context ctx) {
        Session s = sSession;
        if (s == null || s.ended) return;
        CallDiag.log("会话", "检测到通话已接通，清理提醒");
        s.ended = true;
        s.handled = true;
        cleanup(ctx != null ? ctx.getApplicationContext() : sApp);
    }

    /** 微信通知显示「已取消/已结束」或界面显示通话结束时 */
    public static synchronized void onWeChatCallEnded(Context ctx, String reason) {
        Session s = sSession;
        if (s == null || s.ended) return;
        s.ended = true;
        s.handled = true;
        cleanup(ctx != null ? ctx.getApplicationContext() : sApp);
    }

    /** 设置页「试听」：只播报一句 + 短震动，不涉及任何界面 */
    public static void startTestCall(Context ctx, String name, boolean video) {
        Context app = ctx.getApplicationContext();
        sApp = app;
        final String text = name + "来" + (video ? "视频" : "语音") + "电话了。请点微信上的接听。";
        TtsSpeaker.init(app);
        TtsSpeaker.speak(text);
        // 说明：试听不响铃。以前响铃是因为有个界面可以关掉它，
        // 现在没有界面了，铃声会一直响没人关，反而成了新问题。
        // 语音确实不可用时，由设置页的状态卡与提示告诉用户原因。
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(800);
                }
            }
        } catch (Exception ignore) {}
    }

    // ---------------- 内部实现 ----------------

    private static final Runnable sAnnounceLoop = new Runnable() {
        @Override
        public void run() {
            Session s = sSession;
            if (s == null || s.ended || s.handled || sAnnounceCount >= 45) return;
            announce();
            sHandler.postDelayed(this, 4000L);
        }
    };

    private static final Runnable sAutoRun = new Runnable() {
        @Override
        public void run() {
            performAccept(true);
        }
    };

    private static final Runnable sTimeout = new Runnable() {
        @Override
        public void run() {
            synchronized (CallSessionManager.class) {
                Session s = sSession;
                if (s == null || s.ended) return;
                s.ended = true;
                cleanup(sApp);
            }
        }
    };

    /**
     * 自动点击没能落到微信的接听键上（例如微信界面始终没到前台、被系统拦截等）。
     * 这时不能再装死：响铃 + 震动 + 语音，把老人叫过来自己点微信上的接听。
     */
    private static synchronized void onAcceptFailed() {
        Session s = sSession;
        if (s == null || s.ended) return;
        CallDiag.log("接听", "自动接听失败，转为响铃提醒老人自己点");
        // 放开 handled，让播报循环继续念「请点微信上的接听」，直到接通/挂断/超时
        s.handled = false;
        TtsSpeaker.speak("没接上。微信来电话了，请自己点一下接听。");
        if (sApp != null) {
            startRingtone(sApp);
            startVibration(sApp);
        }
    }

    private static void announce() {
        Session s = sSession;
        if (s == null || s.ended || s.handled) return;
        if (sRingtone != null && TtsSpeaker.isUsable()) {
            stopRingtone(); // 语音引擎就绪后停掉兜底铃声
        }
        StringBuilder sb = new StringBuilder();
        sb.append(s.caller).append("来").append(s.video ? "视频" : "语音").append("电话了。");
        long remain = (s.autoAnswerAt - System.currentTimeMillis()) / 1000L + 1;
        if (s.autoAnswer && remain > 0) {
            sb.append(remain).append("秒后自动接听。不想接听，请按微信上的挂断。");
        } else {
            sb.append("请点微信上的接听。");
        }
        TtsSpeaker.speak(sb.toString());
        sAnnounceCount++;
    }

    /**
     * 发一条来电通知。
     *
     * 它不是为了「显示我们的界面」——我们自己已经没有任何界面了——
     * 而是：① 让锁屏亮起来；② 点一下就能进微信真实的通话界面；
     * ③ 把微信通话界面带到前台，模拟点击才落得到微信的接听键上。
     */
    private static void postCallNotification(Context app, PendingIntent wechatPi) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || sSession == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "微信来电提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("微信来电时提醒并可直接进入微信通话界面");
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL_ID)
                    : new Notification.Builder(app);
            b.setSmallIcon(R.drawable.ic_call)
                    .setContentTitle(sSession.caller + " 来电话了")
                    .setContentText((sSession.video ? "视频" : "语音") + "通话 · 点这里进微信接听")
                    .setPriority(Notification.PRIORITY_MAX)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    .setContentIntent(wechatPi)
                    .setFullScreenIntent(wechatPi, true);
            nm.notify(CALL_NOTIFY_ID, b.build());
        } catch (Exception ignore) {}
    }

    private static void cancelNotification() {
        if (sApp == null) return;
        try {
            NotificationManager nm = (NotificationManager) sApp.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(CALL_NOTIFY_ID);
        } catch (Exception ignore) {}
    }

    private static void startVibration(Context app) {
        try {
            sVibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (sVibrator == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                sVibrator.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, 0));
            } else {
                sVibrator.vibrate(VIBRATE_PATTERN, 0);
            }
        } catch (Exception ignore) {}
    }

    /** TTS 不可用 / 自动接听失败时的兜底：循环响系统铃声 */
    private static void startRingtone(Context app) {
        if (sRingtone != null) return;
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) return;
            sRingtone = new MediaPlayer();
            sRingtone.setDataSource(app, uri);
            if (Build.VERSION.SDK_INT >= 21) {
                sRingtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            } else {
                sRingtone.setAudioStreamType(android.media.AudioManager.STREAM_RING);
            }
            sRingtone.setLooping(true);
            sRingtone.prepare();
            sRingtone.start();
        } catch (Exception ignore) {
            sRingtone = null;
        }
    }

    private static void stopRingtone() {
        if (sRingtone != null) {
            try {
                sRingtone.stop();
                sRingtone.release();
            } catch (Exception ignore) {}
            sRingtone = null;
        }
    }

    private static void acquireWakeLock(Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callhelper:ringing");
            sWakeLock.acquire(200_000L);
        } catch (Exception ignore) {}
    }

    private static void stopSoundsAndVibration() {
        TtsSpeaker.stop();
        stopRingtone();
        if (sVibrator != null) {
            try { sVibrator.cancel(); } catch (Exception ignore) {}
            sVibrator = null;
        }
    }

    private static void cleanup(Context app) {
        sHandler.removeCallbacks(sAnnounceLoop);
        sHandler.removeCallbacks(sAutoRun);
        sHandler.removeCallbacks(sTimeout);
        stopSoundsAndVibration();
        cancelNotification();
        if (sWakeLock != null) {
            try {
                if (sWakeLock.isHeld()) sWakeLock.release();
            } catch (Exception ignore) {}
            sWakeLock = null;
        }
        if (app != null) sApp = app;
    }
}
