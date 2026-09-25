package com.jia.callhelper;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
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
 * 来电会话管理（全局单例状态机）+ 声音调度。
 *
 * 一次微信来电的处理流程：
 * 1. 通知监听或无障碍发现来电 → startCall()
 * 2. 语音播报「谁打来的 + 该怎么操作」，同时在屏幕上圈出绿色接听键（见 GuideOverlay）
 * 3. 白名单家人 + 自动接听开关都满足 → 等 N 秒后自动点微信的接听键；
 *    其余来电只提醒，老人在微信界面自己点（本来就只有一个真按钮，不再有多余界面）
 * 4. 接通 / 挂断 / 对方取消 / 用户点「停止提醒」/ 超过 90 秒 → 立即停掉一切声音
 *
 * ⚠️ 关于声音的三条硬规则（都是踩过坑之后加的）：
 *
 * 【规则一】有语音播报就绝不响铃。
 * 语音引擎是异步加载的，刚来电时还没就绪。老版本在来电瞬间就判断「没有语音」
 * 并启动铃声，等引擎就绪后只在下一轮播报时才去关铃声 —— 中间这几秒
 * 语音和铃声是叠在一起的。现在改为：先等最多 4 秒给引擎机会，
 * 确认真的不可用（STATE_INIT_FAILED / NO_CHINESE）才响铃；
 * 任何时刻只要语音可用就立刻把铃声停掉。
 *
 * 【规则二】播报必须持续到真的接听或挂断。
 * 不再因为「自动接听流程已启动」就停止播报（旧版把 handled 当作停止条件，
 * 结果自动接听等待期间一片安静）。
 *
 * 【规则三】必须能被真正停下来。
 * 之前的停止只依赖「微信通知文字变成已取消/已结束」，但实测微信挂断来电时
 * 通知是**直接消失**的，不是改文字。所以挂断后 App 根本不知道，
 * 铃声要一直响到硬超时。现在有五个独立的停止源：
 *   ① 无障碍看到微信通话中界面（接通）
 *   ② 系统音频进入通话状态（接通）
 *   ③ 微信来电通知被移除（挂断/接听/取消都会触发）
 *   ④ 微信来电界面从屏幕上消失（连续 3 次确认）
 *   ⑤ 用户自己点「停止提醒」（通知按钮 / 屏幕浮层按钮）
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
        public volatile boolean ended = false;    // 整个会话结束
        public volatile boolean handled = false;  // 接听流程已启动（去重用，不再作为"停止播报"的条件）
        public volatile boolean answered = false; // 已确认接通
        public volatile boolean stoppedByUser = false;
        public volatile boolean wechatUiSeen = false; // 是否读到过微信来电界面（判断"界面消失"的前提）
        public volatile int goneTicks = 0;   // 连续几次没看到微信来电界面
        public volatile int modeTicks = 0;   // 连续几次检测到系统音频处于通话状态

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

    /** 播报间隔：一句完整提示大约念 5~7 秒，留出余量，避免上一句被下一句打断 */
    private static final long ANNOUNCE_INTERVAL_MS = 8000L;
    /** 守护检测间隔：负责"接通了就停、挂断了也停" */
    private static final long WATCH_INTERVAL_MS = 1500L;
    /** 给语音引擎的等待窗口：8 × 500ms = 4 秒，确认不可用才改响铃 */
    private static final long TTS_WAIT_STEP_MS = 500L;
    private static final int TTS_WAIT_STEPS = 8;
    /** 提醒最长时长：再久对方也早挂了，不能一直吵着老人 */
    private static final long HARD_TIMEOUT_MS = 90_000L;
    private static final int MAX_ANNOUNCE = 30;

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
    private static boolean sFirstAnnounce = true;

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
        sFirstAnnounce = true;
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
            why.append(" → 只提醒：这个人不在家人名单里");
        } else if (!match.auto) {
            why.append(" → 只提醒：该联系人的「自动接听」没打开");
        } else {
            why.append(" → 只提醒：设置页的「自动接听」总开关没打开");
        }
        CallDiag.log("来电", why.toString());

        acquireWakeLock(app);
        startVibration(app);
        TtsSpeaker.init(app);

        // 先播报，再决定要不要响铃（语音优先，见类注释【规则一】）
        announce();
        waitForTtsThenMaybeRingtone(0, sSession);

        // 屏幕上圈出接听键，让老人看得见该点哪里
        GuideOverlay.show(app, caller, sSession.autoAnswer);

        // 只在拿得到微信通知时发：目的不是"弹我们的界面"，
        // 而是亮屏 + 把微信真实的通话界面带到前台，好让模拟点击能落到微信上；
        // 同时它带一个「停止提醒」按钮，是随时能关掉声音的入口。
        if (openIntent != null) {
            postCallNotification(app, openIntent);
        }

        sHandler.postDelayed(sAnnounceLoop, ANNOUNCE_INTERVAL_MS);
        sHandler.postDelayed(sWatchdog, WATCH_INTERVAL_MS);
        if (sSession.autoAnswer) {
            sHandler.postDelayed(sAutoRun, delay * 1000L);
        }
        sHandler.postDelayed(sTimeout, HARD_TIMEOUT_MS);
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
        // 自动点击期间收起屏幕指引：此时不需要老人动手，
        // 而且浮层可能干扰"当前前台是不是微信"的判断
        GuideOverlay.hide();

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
        // 接通后由守护检测（音频状态/无障碍界面）清理；另有 90 秒超时兜底
    }

    /** 无障碍检测到通话已接通（出现静音/免提按钮） */
    public static synchronized void onWeChatCallAnswered(Context ctx) {
        markAnswered("无障碍看到微信通话中界面", ctx);
    }

    /** 微信通知显示「已取消/已结束」或界面显示通话结束时 */
    public static synchronized void onWeChatCallEnded(Context ctx, String reason) {
        markEnded("来电已结束（" + reason + "）", ctx);
    }

    /**
     * 微信来电通知消失了。
     *
     * 这是「挂断后铃声还在响」的根因修复点：实测微信在对方挂断 / 自己接听 /
     * 对方取消时，都是把来电通知**直接移除**，而不是把文字改成「已取消」。
     * 旧版本只处理通知内容变化，于是挂断后 App 完全不知道，铃声一直响到超时。
     */
    public static synchronized void onWeChatCallNotificationGone(Context ctx) {
        Session s = sSession;
        if (s == null || s.ended) return;
        markEnded("微信来电通知已消失（挂断/已接听/已取消）", ctx);
    }

    /**
     * 用户主动停止提醒（通知上的按钮 / 屏幕浮层上的按钮）。
     * 停掉全部声音并撤下浮层与通知，但不会去挂断微信通话——
     * 只是"别吵了"，通话本身仍由微信界面控制。
     */
    public static synchronized void stopByUser(Context ctx) {
        Session s = sSession;
        if (s != null && !s.ended) {
            s.stoppedByUser = true;
            CallDiag.log("会话", "用户点了「停止提醒」→ 立即停掉语音、铃声与震动");
        } else {
            CallDiag.log("会话", "收到「停止提醒」，但没有进行中的会话（只做一次彻底清理）");
        }
        cleanup(ctx != null ? ctx.getApplicationContext() : sApp);
    }

    /** 设置页「试听」：只播报一句 + 短震动 + 演示一次屏幕指引，不涉及任何接听 */
    public static void startTestCall(Context ctx, String name, boolean video) {
        final Context app = ctx.getApplicationContext();
        sApp = app;
        final String text = name + "来" + (video ? "视频" : "语音")
                + "电话了。想接就点屏幕右下角绿色的接听按钮，不想接就点左边的红色按钮。";
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
        // 顺便演示一遍屏幕指引，让用户知道来电时屏幕上会出现什么
        GuideOverlay.show(app, name, false);
        final Object token = GuideOverlay.token();
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 只收起自己这次显示的浮层，不要误伤后来真实来电的指引
                GuideOverlay.hideIf(token);
            }
        }, 7000L);
    }

    // ---------------- 内部实现 ----------------

    private static final Runnable sAnnounceLoop = new Runnable() {
        @Override
        public void run() {
            Session s = sSession;
            if (s == null || s.ended || s.answered || sAnnounceCount >= MAX_ANNOUNCE) return;
            announce();
            sHandler.postDelayed(this, ANNOUNCE_INTERVAL_MS);
        }
    };

    private static final Runnable sAutoRun = new Runnable() {
        @Override
        public void run() {
            performAccept(true);
        }
    };

    /**
     * 守护检测：负责"接通了就停、挂断了也停、有语音就别响铃"。
     * 每 1.5 秒跑一次，是本版本最关键的可靠性保障。
     */
    private static final Runnable sWatchdog = new Runnable() {
        @Override
        public void run() {
            Session s = sSession;
            if (s == null || s.ended) return;
            CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();

            boolean ringing = svc != null && svc.isRinging();
            if (ringing) {
                s.wechatUiSeen = true;
                s.goneTicks = 0;
            }
            if (svc != null && svc.isInCall()) {
                markAnswered("无障碍看到微信通话中界面", sApp);
                return;
            }

            // 系统音频进入通话状态 = 已经接上了（微信 VoIP 接通后会占用通话音频通道）。
            // 要求连续两次命中，并且无障碍没看到"仍在响铃"，避免误判
            // （个别机型在响铃期间就会占用音频通道）。
            boolean modeInCall = isPhoneInCall();
            if (modeInCall) s.modeTicks++; else s.modeTicks = 0;
            if (modeInCall && s.modeTicks >= 3 && (svc == null || !ringing)) {
                markAnswered("系统音频已进入通话状态", sApp);
                return;
            }

            // 有语音播报就不要铃声（语音引擎可能刚刚才加载好）
            if (sRingtone != null && TtsSpeaker.isUsable()) {
                stopRingtone();
                CallDiag.log("提醒", "语音引擎已就绪 → 停掉兜底铃声");
            }

            // 微信来电界面从屏幕上消失了：对方挂断、已接听、或老人自己处理了。
            // 连续 3 次（约 4.5 秒）确认，避免界面切换的瞬间误判。
            if (svc != null && s.wechatUiSeen && !ringing) {
                s.goneTicks++;
                if (s.goneTicks >= 3) {
                    markEnded("微信来电界面已消失（对方挂断或已接听）", sApp);
                    return;
                }
            }

            sHandler.postDelayed(this, WATCH_INTERVAL_MS);
        }
    };

    private static final Runnable sTimeout = new Runnable() {
        @Override
        public void run() {
            markEnded("提醒已持续 90 秒", sApp);
        }
    };

    /**
     * 语音优先：先给语音引擎最多 4 秒，确认不可用才响铃。
     * 任何时刻只要语音可用，就把铃声停掉（见类注释【规则一】）。
     */
    private static void waitForTtsThenMaybeRingtone(final int n, final Session token) {
        if (sSession != token || token.ended) return;
        if (TtsSpeaker.isUsable() || TtsSpeaker.isSpeakVerified()) {
            if (sRingtone != null) stopRingtone();
            CallDiag.log("提醒", "语音引擎可用 → 只播报，不响铃");
            return;
        }
        if (n >= TTS_WAIT_STEPS) {
            if (sSession != token || token.ended) return;
            if (TtsSpeaker.isUsable()) {
                if (sRingtone != null) stopRingtone();
                return;
            }
            CallDiag.log("提醒", "等待 4 秒仍没有语音播报 → 改用铃声兜底。"
                    + TtsSpeaker.describeProblem());
            startRingtone(sApp);
            return;
        }
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                waitForTtsThenMaybeRingtone(n + 1, token);
            }
        }, TTS_WAIT_STEP_MS);
    }

    /**
     * 自动点击没能落到微信的接听键上（例如微信界面始终没到前台、被系统拦截等）。
     * 这时不能再装死：语音 + 震动 + 屏幕指引一起上，让老人自己点。
     * 注意不要额外加铃声——有语音就不响铃。
     */
    private static synchronized void onAcceptFailed() {
        Session s = sSession;
        if (s == null || s.ended || s.answered) return;
        CallDiag.log("接听", "自动接听失败 → 改为语音 + 屏幕指引，提醒老人自己点");
        s.handled = false;      // 放开，让接听流程可以重来（例如老人自己点）
        sFirstAnnounce = true;  // 重新念一遍完整指引
        announce();

        if (!TtsSpeaker.isUsable() && !TtsSpeaker.isSpeakVerified()) {
            // 连语音都没有：只能用铃声 + 震动，至少能听见
            if (sApp != null) {
                startRingtone(sApp);
                startVibration(sApp);
            }
        }
        if (sApp != null) GuideOverlay.show(sApp, s.caller, false);

        sHandler.removeCallbacks(sAnnounceLoop);
        sHandler.postDelayed(sAnnounceLoop, ANNOUNCE_INTERVAL_MS);
    }

    private static synchronized void markAnswered(String reason, Context ctx) {
        Session s = sSession;
        if (s == null || s.ended) return;
        s.answered = true;
        CallDiag.log("会话", "通话已接通（" + reason + "）→ 停止播报与铃声");
        cleanup(ctx != null ? ctx.getApplicationContext() : sApp);
    }

    private static synchronized void markEnded(String reason, Context ctx) {
        Session s = sSession;
        if (s == null || s.ended) return;
        CallDiag.log("会话", "结束提醒（" + reason + "）");
        cleanup(ctx != null ? ctx.getApplicationContext() : sApp);
    }

    /** 系统音频是否已进入通话状态（微信 VoIP 接通后成立） */
    private static boolean isPhoneInCall() {
        if (sApp == null) return false;
        try {
            AudioManager am = (AudioManager) sApp.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            int mode = am.getMode();
            return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
        } catch (Exception ignore) {
            return false;
        }
    }

    private static void announce() {
        Session s = sSession;
        if (s == null || s.ended || s.answered) return;
        if (sRingtone != null && TtsSpeaker.isUsable()) {
            stopRingtone(); // 语音可用了就别再响铃（见类注释【规则一】）
        }

        long remain = (s.autoAnswerAt - System.currentTimeMillis()) / 1000L + 1;
        StringBuilder sb = new StringBuilder();
        if (sFirstAnnounce) {
            sFirstAnnounce = false;
            sb.append(s.caller).append("来").append(s.video ? "视频" : "语音").append("电话了。");
            if (s.autoAnswer && remain > 0) {
                sb.append(remain).append("秒后自动帮您接听。屏幕上会圈出绿色按钮。不想接就点红色按钮。");
            } else {
                sb.append("想接，就点屏幕上圈出的绿色按钮；不想接，就点左边的红色按钮。");
            }
        } else if (s.autoAnswer && remain > 0) {
            sb.append("还有 ").append(remain).append(" 秒自动接听。");
        } else {
            sb.append("请点屏幕上圈出的绿色按钮接听。");
        }
        TtsSpeaker.speak(sb.toString());
        sAnnounceCount++;
    }

    /**
     * 发一条来电通知。
     *
     * 它不是为了「显示我们的界面」——我们自己已经没有任何界面了——
     * 而是：① 让锁屏亮起来；② 点一下就能进微信真实的通话界面；
     * ③ 把微信通话界面带到前台，模拟点击才落得到微信的接听键上；
     * ④ 提供一个「停止提醒」按钮，任何时候都能把声音关掉。
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

            // 「停止提醒」：老人/家人随时能把声音关掉，不用等系统超时
            Intent stop = new Intent(app, StopAlertReceiver.class)
                    .setAction(StopAlertReceiver.ACTION_STOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent stopPi = PendingIntent.getBroadcast(app, 3001, stop, flags);
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止提醒", stopPi);

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
            if (sVibrator != null) {
                sVibrator.cancel();
                sVibrator = null;
            }
            sVibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (sVibrator == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                sVibrator.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, 0));
            } else {
                sVibrator.vibrate(VIBRATE_PATTERN, 0);
            }
        } catch (Exception ignore) {}
    }

    /** 语音不可用时的兜底：循环响系统铃声 */
    private static void startRingtone(Context app) {
        if (sRingtone != null || app == null) return;
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) return;
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(app, uri);
            if (Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            } else {
                mp.setAudioStreamType(android.media.AudioManager.STREAM_RING);
            }
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            sRingtone = mp;
        } catch (Exception e) {
            CallDiag.log("提醒", "铃声播放失败：" + e);
            sRingtone = null;
        }
    }

    private static void stopRingtone() {
        MediaPlayer mp = sRingtone;
        sRingtone = null;
        if (mp == null) return;
        try {
            if (mp.isPlaying()) mp.stop();
        } catch (Exception ignore) {}
        try {
            mp.release();
        } catch (Exception ignore) {}
    }

    private static void acquireWakeLock(Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (sWakeLock != null) {
                try { if (sWakeLock.isHeld()) sWakeLock.release(); } catch (Exception ignore) {}
                sWakeLock = null;
            }
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

    /** 结束一次会话：停掉一切声音与界面，并撤销所有排队中的定时任务 */
    private static void cleanup(Context app) {
        Session cur = sSession;
        if (cur != null) {
            cur.ended = true;
            cur.handled = true;
        }
        sHandler.removeCallbacks(sAnnounceLoop);
        sHandler.removeCallbacks(sAutoRun);
        sHandler.removeCallbacks(sWatchdog);
        sHandler.removeCallbacks(sTimeout);
        WeChatClicker.cancel();
        stopSoundsAndVibration();
        GuideOverlay.hide();
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
