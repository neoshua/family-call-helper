package com.jia.callhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
 * 2. 循环语音播报 + 震动 + 弹出全屏大按钮界面（含锁屏亮屏）
 * 3. 白名单家人 + 自动接听 → 倒计时后自动点击微信「接听」
 * 4. 用户按绿色大按钮 → performAccept() → 自动点微信「接听」
 * 5. 用户按红色按钮 → performDecline() → 自动点微信「挂断」
 * 6. 无障碍检测到已接通 / 通话结束 / 超时 → 清理一切
 */
public class CallSessionManager {

    public static class Session {
        public final String caller;
        public final boolean video;
        public final boolean test;
        public final PendingIntent openIntent; // 微信来电通知的 contentIntent，可拉起微信通话界面
        public final long startAt = System.currentTimeMillis();
        public volatile boolean autoAnswer = false;
        public volatile long autoAnswerAt;
        public volatile boolean ended = false;   // 整个会话结束
        public volatile boolean handled = false; // 已接听或已挂断

        Session(String caller, boolean video, PendingIntent openIntent, boolean test) {
            this.caller = caller;
            this.video = video;
            this.openIntent = openIntent;
            this.test = test;
        }
    }

    public static final int DEFAULT_AUTO_DELAY_SEC = 8;

    private static final String CHANNEL_ID = "call_alert";
    private static final int FSI_NOTIFY_ID = 2001;
    private static final long[] VIBRATE_PATTERN = {0, 700, 500, 700, 500};

    private static Session sSession;
    private static Context sApp;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Vibrator sVibrator;
    private static MediaPlayer sRingtone;
    private static PowerManager.WakeLock sWakeLock;
    private static CallAlertActivity sAlertActivity;
    private static int sAnnounceCount = 0;

    // ---------------- 对外接口 ----------------

    public static synchronized Session current() {
        return sSession;
    }

    /** 通知监听 / 无障碍发现微信来电时调用 */
    public static synchronized void startCall(Context ctx, String caller, boolean video,
                                              PendingIntent openIntent, boolean test) {
        Context app = ctx.getApplicationContext();
        Session old = sSession;
        // 同一个人 10 秒内的重复通知（通知刷新）直接忽略
        if (old != null && !old.ended && !old.handled && old.caller != null
                && old.caller.equals(caller)
                && System.currentTimeMillis() - old.startAt < 10_000L) {
            return;
        }
        cleanup(app);

        sApp = app;
        sSession = new Session(caller, video, openIntent, test);
        sAnnounceCount = 0;

        WhiteListManager.Entry match = test ? null : WhiteListManager.match(app, caller);
        int delay = WhiteListManager.prefs(app)
                .getInt("auto_delay_sec", DEFAULT_AUTO_DELAY_SEC);
        if (match != null && match.auto) {
            sSession.autoAnswer = true;
            sSession.autoAnswerAt = System.currentTimeMillis() + delay * 1000L;
        }

        acquireWakeLock(app);
        startVibration(app);
        TtsSpeaker.init(app);
        announce();
        if (!TtsSpeaker.isUsable()) {
            startRingtone(app); // 手机没有中文语音引擎时，退回响铃
        }
        showOverlay(app);

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
        startCall(ctx, caller, video, null, false);
    }

    /** 用户按下绿色大按钮 / 自动接听到时 */
    public static synchronized void performAccept(boolean auto) {
        Session s = sSession;
        if (s == null || s.handled || s.ended) return;
        s.handled = true;
        stopSoundsAndVibration();
        finishAlertActivity();
        cancelNotification();

        final PendingIntent pi = s.openIntent;
        final Context app = sApp;
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (app == null) return;
                // 先尝试拉起微信的来电界面（锁屏场景下它可能还没显示）
                if (pi != null) {
                    try { pi.send(); } catch (Exception ignore) {}
                }
                // 再自动点微信的「接听」按钮（带重试，等界面就绪）
                WeChatClicker.retryClick("接听", 4, 700);
            }
        }, 500L);
        // 接通后由无障碍检测到「静音/免提」按钮 → onWeChatCallAnswered 清理；另有 3 分钟超时兜底
    }

    /** 用户按下红色挂断按钮 */
    public static synchronized void performDecline() {
        Session s = sSession;
        if (s == null || s.handled || s.ended) return;
        s.handled = true;
        stopSoundsAndVibration();
        finishAlertActivity();
        cancelNotification();

        final PendingIntent pi = s.openIntent;
        final Context app = sApp;
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (app == null) return;
                if (pi != null) {
                    try { pi.send(); } catch (Exception ignore) {}
                }
                WeChatClicker.retryClick("挂断", 3, 600);
            }
        }, 400L);
    }

    /** 无障碍检测到通话已接通（出现静音/免提按钮） */
    public static synchronized void onWeChatCallAnswered(Context ctx) {
        Session s = sSession;
        if (s == null || s.ended) return;
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

    /** 设置页「试听」：只播报一次 + 短震动，不弹真界面逻辑 */
    public static void startTestCall(Context ctx, String name, boolean video) {
        Context app = ctx.getApplicationContext();
        TtsSpeaker.init(app);
        TtsSpeaker.speak(name + "来" + (video ? "视频" : "语音") + "电话了。请点击绿色大按钮接听。");
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

    // ---------------- 界面引用 ----------------

    public static void setAlertActivity(CallAlertActivity a) {
        sAlertActivity = a;
    }

    public static void clearAlertActivity(CallAlertActivity a) {
        if (sAlertActivity == a) sAlertActivity = null;
    }

    private static void finishAlertActivity() {
        if (sAlertActivity != null) {
            try { sAlertActivity.finish(); } catch (Exception ignore) {}
            sAlertActivity = null;
        }
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

    private static void announce() {
        Session s = sSession;
        if (s == null || s.ended || s.handled) return;
        if (sRingtone != null && TtsSpeaker.isUsable()) {
            stopRingtone(); // 语音引擎就绪后停掉兜底铃声
        }
        StringBuilder sb = new StringBuilder();
        sb.append(s.caller).append("来").append(s.video ? "视频" : "语音").append("电话了。");
        if (s.autoAnswer) {
            long remain = (s.autoAnswerAt - System.currentTimeMillis()) / 1000L + 1;
            sb.append(Math.max(1, remain)).append("秒后自动接听。不想接听，请按红色挂断。");
        } else {
            sb.append("请点击绿色大按钮接听。");
        }
        TtsSpeaker.speak(sb.toString());
        sAnnounceCount++;
    }

    private static void showOverlay(Context app) {
        Session s = sSession;
        if (s == null) return;
        Intent it = new Intent(app, CallAlertActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        it.putExtra("caller_name", s.caller);
        it.putExtra("is_video", s.video);
        it.putExtra("test_mode", s.test);
        try {
            app.startActivity(it);
        } catch (Exception ignore) {
            // 无后台弹界面权限时，靠下面的全屏通知兜底
        }
        postFullScreenNotification(app, it);
    }

    private static void postFullScreenNotification(Context app, Intent it) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || sSession == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "来电大按钮提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("微信来电时弹出大按钮接听界面");
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(app, 3, it, piFlags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL_ID)
                    : new Notification.Builder(app);
            b.setSmallIcon(R.drawable.ic_call)
                    .setContentTitle(sSession.caller + " 来电")
                    .setContentText((sSession.video ? "视频" : "语音") + "通话 · 点击接听")
                    .setPriority(Notification.PRIORITY_MAX)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true);
            nm.notify(FSI_NOTIFY_ID, b.build());
        } catch (Exception ignore) {}
    }

    private static void cancelNotification() {
        if (sApp == null) return;
        try {
            NotificationManager nm = (NotificationManager) sApp.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(FSI_NOTIFY_ID);
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

    /** TTS 不可用时的兜底：循环响系统铃声 */
    private static void startRingtone(Context app) {
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
        finishAlertActivity();
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
