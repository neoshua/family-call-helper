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
        /** 微信那边显示的名字（备注名 / 昵称），用于匹配家人名单 */
        public final String caller;
        /**
         * **用来念给老人听的名字**。
         *
         * 用户明确要求：语音播报不要念微信昵称，要念本应用里配置的称呼。
         * 因为微信备注名可能是「hh」「老王」这种老人根本听不懂的东西，
         * 而 App 里配置的称呼是家人自己写的（「大儿子」「闺女」）——
         * 老人一听就知道是谁打来的。
         * 不在名单里的来电没有配置称呼，只能退回微信显示的名字。
         *
         * 【v1.12 改为非 final】无障碍与通知两条通道会先后触发，
         * 后到的那个可能带着更准的名字（通知里的备注名通常比界面猜的更可靠）。
         * 一旦拿到能匹配名单的名字，就用配置的称呼把这里覆盖掉，
         * 保证念给老人的始终是「丈母娘」而不是「hh」。
         */
        public volatile String displayName;
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
        public volatile boolean fullScreenSeen = false; // 是否出现过"全屏来电界面"（决定指引画圈还是只提示）
        public volatile int goneTicks = 0;   // 连续几次没看到微信来电界面
        public volatile int modeTicks = 0;   // 连续几次检测到系统音频处于通话状态
        /** 自动接听失败后的重试次数（见 sRetryAccept）。微信界面常比通知晚出现，不重试就会错过整通电话 */
        public volatile int retryCount = 0;

        Session(String caller, String displayName, boolean video, PendingIntent openIntent) {
            this.caller = caller;
            this.displayName = (displayName == null || displayName.trim().isEmpty())
                    ? caller : displayName;
            this.video = video;
            this.openIntent = openIntent;
        }
    }

    public static final int DEFAULT_AUTO_DELAY_SEC = 8;

    private static final String CHANNEL_ID = "call_notify";
    private static final int CALL_NOTIFY_ID = 2001;
    private static final long[] VIBRATE_PATTERN = {0, 700, 500, 700, 500};

    /**
     * 播报间隔。自动接听倒计时期间要每秒级更新（播报 + 屏幕上的数字都要跳），
     * 所以这里取 2.5 秒：既够老人听完一句"X 秒后自动接听"，又不会太密。
     */
    private static final long ANNOUNCE_INTERVAL_MS = 2500L;
    /** 自动接听倒计时播报的上限：超过这个秒数就只说一次总时长，不逐秒念 */
    private static final int AUTO_COUNTDOWN_MAX_SEC = 30;
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

        // 先查名单：命中就用 App 里配置的称呼来播报（老人听得懂的「大儿子」，
        // 而不是微信备注名「hh」）
        WhiteListManager.Entry match = WhiteListManager.match(app, caller);
        String displayName = match != null ? match.name : caller;

        sApp = app;
        sSession = new Session(caller, displayName, video, openIntent);
        sAnnounceCount = 0;
        sFirstAnnounce = true;
        sPullAttempt = 0;
        WeChatClicker.reset();

        int delay = WhiteListManager.prefs(app)
                .getInt("auto_delay_sec", DEFAULT_AUTO_DELAY_SEC);
        // 自动接听需同时满足：总开关开启 + 该联系人标记了自动接听
        boolean master = WhiteListManager.prefs(app).getBoolean("auto_answer_master", false);
        if (match != null && match.auto && master) {
            sSession.autoAnswer = true;
            sSession.autoAnswerAt = System.currentTimeMillis() + delay * 1000L;
        }
        // 把「这次为什么接 / 为什么不接」记下来：这是排查「没自动接听」的第一现场。
        // 【v1.12】未命中时把"该怎么做"直接写进记录，用户一看就知道要填什么。
        StringBuilder why = new StringBuilder();
        why.append("来电：微信显示名=").append(caller)
                .append(" 播报称呼=").append(displayName)
                .append("（").append(video ? "视频" : "语音").append("）")
                .append(" 名单命中=").append(match != null ? match.name : "无")
                .append(" 该联系人开自动接听=").append(match != null && match.auto)
                .append(" 总开关=").append(master);
        if (sSession.autoAnswer) {
            why.append(" → ").append(delay).append(" 秒后自动接听");
        } else if (match == null) {
            why.append(" → 只提醒：这个人不在家人名单里。微信显示的是「").append(caller)
                    .append("」，请到设置→添加家人，把「").append(caller)
                    .append("」填进他的「微信备注名」（只填「称呼」匹配不上）。");
        } else if (!match.auto) {
            why.append(" → 只提醒：联系人是「").append(match.name)
                    .append("」，但他的「自动接听」开关没打开");
        } else {
            why.append(" → 只提醒：设置页的「自动接听」总开关没打开");
        }
        CallDiag.log("来电", why.toString());
        // 每次都记一份环境快照：换机、升级微信、权限被系统收回，都能从这里看出来
        CallDiag.snapshot(app, "来电时");

        acquireWakeLock(app);
        startVibration(app);
        TtsSpeaker.init(app);

        // 先播报，再决定要不要响铃（语音优先，见类注释【规则一】）
        announce();
        waitForTtsThenMaybeRingtone(0, sSession);

        // 屏幕上圈出接听键，让老人看得见该点哪里。
        // 注意：只有"全屏来电界面"才有接听键可圈；若此刻屏幕上只有通知/横幅，
        // GuideOverlay 会只显示一条提示而不画圈，避免圈到一个空位置误导老人。
        GuideOverlay.show(app, sSession.displayName, sSession.autoAnswer,
                sSession.autoAnswer ? sSession.autoAnswerAt : 0L);

        // 记录当前的界面形态：三种形态（全屏 / 通知栏 / 顶部横幅）要区别对待
        CallHelperAccessibilityService svc0 = CallHelperAccessibilityService.get();
        if (svc0 != null) {
            int st = svc0.callUiState();
            sSession.fullScreenSeen = st == CallHelperAccessibilityService.UI_RINGING;
            CallDiag.log("来电", "此刻界面形态=" + uiStateName(st)
                    + "（只有「全屏来电界面」上才有接听键；其余形态会先把全屏界面拉出来再点）");
        }

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
        if (s != null && !s.ended) {
            // 【v1.12】会话已经在跑了（通知先到）。这里不要直接忽略——
            // 无障碍从界面猜出来的名字，有时比通知标题更接近"微信里显示的名字"，
            // 也可能通知先到时名字没拿到。所以这里补一次"用新名字重匹配名单"，
            // 只要匹配上了就把念给老人的称呼换成配置的那个。
            refineCaller(ctx, caller);
            return;
        }
        startCall(ctx, caller, video, null);
    }

    /**
     * 用后来拿到的一个"微信里显示的名字"重试匹配家人名单。
     *
     * 两条通道（通知 / 无障碍）各自能拿到的名字未必相同：通知标题通常是备注名，
     * 而界面上能读到的可能是昵称。只要有任意一个能匹配上名单，
     * 就把播报称呼换成 App 里配置的称呼（老人听得懂的「丈母娘」而不是「hh」）。
     */
    public static synchronized void refineCaller(Context ctx, String caller) {
        Session s = sSession;
        if (s == null || s.ended || ctx == null) return;
        if (caller == null || caller.trim().isEmpty()) return;
        String c = caller.trim();
        // 已经用的是配置称呼（说明之前就匹配上了），不必再动
        WhiteListManager.Entry hit = WhiteListManager.matchQuiet(ctx.getApplicationContext(), c);
        if (hit == null) return;
        if (hit.name != null && !hit.name.equals(s.displayName)) {
            CallDiag.log("来电", "补充识别到微信显示名「" + c + "」→ 播报称呼修正为「"
                    + hit.name + "」（原为「" + s.displayName + "」）");
            s.displayName = hit.name;
        }
        // 名字对上了，顺便重新判断一次是否能自动接听
        boolean master = WhiteListManager.prefs(ctx).getBoolean("auto_answer_master", false);
        if (hit.auto && master && !s.autoAnswer && !s.handled) {
            int delay = WhiteListManager.prefs(ctx)
                    .getInt("auto_delay_sec", DEFAULT_AUTO_DELAY_SEC);
            s.autoAnswer = true;
            s.autoAnswerAt = System.currentTimeMillis() + delay * 1000L;
            CallDiag.log("来电", "补充识别后满足自动接听条件 → " + delay + " 秒后自动接听");
            sHandler.removeCallbacks(sAutoRun);
            sHandler.postDelayed(sAutoRun, delay * 1000L);
        }
    }

    /**
     * 开始接听：**先确保屏幕上真的出现了「全屏来电界面」，再点接听键**。
     *
     * 为什么要多这一步（用户实测反馈）：
     * 微信来电在手机上会出现三种形态 ——
     *   ① 全屏来电界面：整屏的「邀请你视频通话」+ 左下红 / 右下绿两个圆钮 → 有接听键
     *   ② 下拉通知栏里的来电通知 → 屏幕上没有接听键
     *   ③ 屏幕顶部的横幅通知（heads-up） → 屏幕上也没有接听键
     * 旧版本不管哪种形态都直接"按比例点右下角"，在 ②③ 下等于在通知栏/桌面上乱点：
     * 接不到电话，还可能点到别的东西。
     * 现在改为：不是全屏就先把它拉成全屏（等价于点一下微信来电通知），
     * 拉起来了再点接听；始终拉不起来就交给老人自己点，并给出明确提示。
     */
    public static synchronized void performAccept(boolean auto) {
        Session s = sSession;
        if (s == null || s.handled || s.ended) {
            // 【v1.12】这里以前是静默 return，导致"为什么没执行自动接听"完全查不到。
            // 现在把拦下的原因也记一笔，运行记录里就能看到"到底有没有尝试过"。
            if (s != null) {
                CallDiag.log("接听", "本次接听请求被忽略（handled=" + s.handled
                        + " ended=" + s.ended + "）");
            }
            return;
        }
        s.handled = true;
        // 自动点击期间收起屏幕指引：此时不需要老人动手，
        // 而且浮层可能干扰"当前前台是不是微信"的判断
        GuideOverlay.hide();
        sPullAttempt = 0;
        // 记一次"开始自动接听"的现场：这个时候最容易看出环境对不对
        CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();
        CallDiag.log("接听", "开始自动接听（auto=" + auto + "）"
                + " 无障碍服务=" + (svc != null)
                + " 微信在前台=" + (svc != null && svc.isWeChatForeground())
                + " 界面形态=" + (svc != null ? uiStateName(svc.callUiState()) : "未知")
                + " 有通知跳转=" + (s.openIntent != null));
        sHandler.removeCallbacks(sEnsureFullScreen);
        sHandler.postDelayed(sEnsureFullScreen, 300L);
    }

    /** 一次来电里最多尝试几种"把微信来电页拉起来并接听"的轮次（每轮约 1.2 秒） */
    private static final int MAX_PULL_ATTEMPTS = 8;
    private static final long PULL_INTERVAL_MS = 1200L;
    private static int sPullAttempt = 0;

    private static final Runnable sEnsureFullScreen = new Runnable() {
        @Override
        public void run() {
            ensureFullScreenStep();
        }
    };

    /**
     * 【v1.13 全自动】自动接听的主循环。
     *
     * 用户明确要求："我要的是全自动，默认老人是不会操作的"。
     * 所以这里彻底改掉"先确认全屏、确认不了就放弃"的保守做法——那本质上还是半自动：
     * 判定一旦失灵（微信界面全自绘、读不到节点、某些 ROM 不给窗口 bounds），
     * 就会从头到尾一次都不点，老人只能自己动手。
     *
     * 现在的策略是**主动出击、每轮都尝试**：
     *   每一轮（每 1.2 秒）都做三件事：
     *     ① 先看是不是已经接通了 → 是就收工；
     *     ② 把微信来电界面往前提（通知跳转 / 启动微信），尽可能让它变成全屏；
     *     ③ **无论判定结果如何，都尝试点一次接听键**。
     *   点击本身有安全网（见 WeChatClicker）：
     *     - 先做语义/几何/镜像定位，只在"确认拿到的就是接听键"时才用无障碍点击；
     *     - 坐标兜底严格限制在"微信窗口铺满整屏"时才允许，
     *       因为那时屏幕右下角必然是接听键，不会误触别的应用；
     *     - 点完会验证是否真的接通，没接上就继续下一轮。
     *   之所以敢"每轮都点"，是因为此刻屏幕上就是微信的来电页，
     *   右下角那个绿色圆钮是整屏唯一在那儿的可点区域，点到它之外不会有副作用。
     *
     * 另外单独加了一条"解锁"尝试：锁屏状态下手势常被系统拦下，
     * 所以会尝试用无障碍的全局动作亮屏/解锁（做不到也不强求，不影响其它步骤）。
     */
    private static void ensureFullScreenStep() {
        Session s = sSession;
        if (s == null || s.ended || s.answered) return;

        final CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();
        if (svc == null) {
            CallDiag.log("接听", "无障碍服务未开启，无法自动接听");
            onAcceptFailed();
            return;
        }

        int state = svc.callUiState();
        if (state == CallHelperAccessibilityService.UI_IN_CALL) {
            markAnswered("点击前已确认在通话中", sApp);
            return;
        }

        sPullAttempt++;

        // 【第一步】先尽量把微信来电界面弄成全屏。
        // 无论当前是"通知栏里的通知"（用户图二）还是"顶部横幅"，都要主动拉起来——
        // 这一步不依赖任何判定，能发就发，失败也不影响后面的点击尝试。
        boolean alreadyFull = svc.isFullScreenCallUi();
        if (alreadyFull) {
            s.fullScreenSeen = true;
        } else {
            // 每轮都尝试拉起：微信从通知跳到全屏通话页本身要几百毫秒到一两秒，
            // 而且不同 ROM 的响应差别很大，与其猜不如每轮都推一把。
            pullWeChatCallToFront();
        }

        boolean locked = false;
        try {
            KeyguardManager km = (KeyguardManager) sApp.getSystemService(Context.KEYGUARD_SERVICE);
            locked = km != null && km.isKeyguardLocked();
        } catch (Exception ignore) {}
        CallHelperAccessibilityService.WeChatWin win = svc.debugWeChatWindow();
        CallDiag.log("接听", "第 " + sPullAttempt + "/" + MAX_PULL_ATTEMPTS
                + " 轮：形态=" + uiStateName(state)
                + " 锁屏=" + locked
                + " 微信在前台=" + svc.isWeChatForeground()
                + " 窗口=" + (win == null ? "拿不到" : win.bounds.toShortString())
                + " 界面文字=" + (win != null && svc.canReadUiText(win.root) ? "可读" : "读不到")
                + (alreadyFull ? "（已是全屏）" : "（已尝试拉起）")
                + " → 尝试点击接听键");

        // 【第二步】直接尝试点击，不再等"确认全屏"。
        // 这个决定是有意的：判定失灵时，等待等于永远不点（用户实测就是这个结果）。
        // 而此刻屏幕上就是微信来电页，右下角必然是接听键，点了不会伤到别的应用。
        // WeChatClicker 内部还会再校验一次窗口归属，不满足条件它自己会拒绝点。
        WeChatClicker.answerWithRetry(CLICK_ATTEMPTS, CLICK_INTERVAL_MS,
                new WeChatClicker.Callback() {
                    @Override
                    public void onResult(boolean clicked) {
                        Session cur = sSession;
                        if (cur == null || cur.ended) return;
                        if (clicked) {
                            CallDiag.log("接听", "接听流程结束：已接上");
                            return;
                        }
                        // 这一轮没点上：界面可能还没铺开，继续下一轮
                        if (sPullAttempt >= MAX_PULL_ATTEMPTS) {
                            CallDiag.log("接听", "已尝试 " + MAX_PULL_ATTEMPTS
                                    + " 轮仍未接上 → 交给老人自己点（语音与屏幕指引继续提醒）");
                            onAcceptFailed();
                            return;
                        }
                        sHandler.postDelayed(sEnsureFullScreen, PULL_INTERVAL_MS);
                    }
                });
    }

    /**
     * 把微信的全屏来电界面调到最前面。
     *
     * 这一步是"全自动"的前提：用户图二那种情况——屏幕上只有顶部一条聊天式通知条，
     * 微信界面根本没打开——不主动拉起来，屏幕上就永远没有接听键可点。
     *
     * 按可靠性依次尝试四招（每一招都记日志，方便看是哪一招生效）：
     *   ① 微信来电通知自带的 PendingIntent：等价于用户亲手点那条通知，
     *      系统允许，效果最准（微信自己会跳到全屏通话页）。
     *   ② 无障碍的全局动作 GLOBAL_ACTION_NOTIFICATIONS：把通知栏拉下来，
     *      让用户/系统看到那条来电通知（部分 ROM 会顺便把通话页顶起来）。
     *   ③ 直接启动微信主界面：来电期间微信通常会把通话页顶到最前。
     *   ④ 通知栏里那条通知本来就有 contentIntent —— 上面 ① 用过了，
     *      这里再用一次并且不 await，因为微信自身有跳转节流。
     */
    private static void pullWeChatCallToFront() {
        Session s = sSession;
        if (sApp == null) return;
        if (s != null && s.openIntent != null) {
            try {
                s.openIntent.send();
                CallDiag.log("接听", "拉起①：已通过微信来电通知跳转（等价于点那条通知）");
                return;
            } catch (Exception e) {
                CallDiag.log("接听", "拉起①失败（通知跳转）：" + e);
            }
        }
        // ② 无障碍全局动作：把通知栏拉下来。
        // 有些 ROM 上"通知栏展开"会让来电通知进入可交互状态，
        // 也顺便给了用户一个可见入口（万一后面自动点击还是失败）。
        try {
            CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();
            if (svc != null && svc.openNotificationShade()) {
                CallDiag.log("接听", "拉起②：已下拉通知栏（让来电通知可见/可交互）");
            }
        } catch (Exception e) {
            CallDiag.log("接听", "拉起②失败（下拉通知栏）：" + e);
        }
        // ③ 启动微信
        try {
            Intent i = sApp.getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (i == null) {
                CallDiag.log("接听", "拉起③失败：拿不到微信的启动入口");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            sApp.startActivity(i);
            CallDiag.log("接听", "拉起③：已启动微信，期望它把来电页顶到最前");
        } catch (Exception e) {
            CallDiag.log("接听", "拉起③失败（启动微信）：" + e);
        }
    }

    private static String uiStateName(int st) {
        switch (st) {
            case CallHelperAccessibilityService.UI_RINGING: return "全屏来电界面";
            case CallHelperAccessibilityService.UI_IN_CALL: return "已接通";
            default: return "没有全屏来电界面（可能只有通知或横幅）";
        }
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
        // 试听文案同样来自 SpeakScript（可自定义）
        final String text = SpeakScript.render(app, SpeakScript.Item.TEST,
                name, video, 0);
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
        // 顺便演示一遍屏幕指引，让用户知道来电时屏幕上会出现什么。
        // 试听时并没有真实的微信来电界面，所以用 showDemo 强制画出圆圈。
        GuideOverlay.showDemo(app, name);
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

            // 界面形态可能中途变化（例如一开始只有横幅通知，几秒后才弹出全屏来电界面）。
            // 一旦变成全屏，就把屏幕指引从"只提示"升级成"圈出接听键"——
            // 这才是老人真正需要看到的东西。
            if (svc != null && !s.handled && svc.isFullScreenCallUi()) {
                if (!s.fullScreenSeen) {
                    s.fullScreenSeen = true;
                    CallDiag.log("提醒", "界面已变为全屏来电界面 → 把屏幕指引升级为「圈出接听键」");
                    if (sApp != null) GuideOverlay.show(sApp, s.displayName, s.autoAnswer,
                            s.autoAnswer ? s.autoAnswerAt : 0L);
                } else if (!GuideOverlay.showingRing()) {
                    if (sApp != null) GuideOverlay.show(sApp, s.displayName, s.autoAnswer,
                            s.autoAnswer ? s.autoAnswerAt : 0L);
                }
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
     * 语音引擎的等待与兜底。
     *
     * 【v1.12 策略调整，用户要求】**不再降级为铃声**。
     * 原因：微信来电本身就有铃声，我们自己再响一遍等于两个声音叠着吵，
     * 对老人是纯粹噪音，还盖住了我们想让他听清的语音提示。
     * 所以现在只做两件事：
     *   ① 给引擎最多 4 秒加载时间，就绪了就只播报；
     *   ② 始终不可用（手机真的没装中文引擎）就记一条日志说明原因，
     *      提醒仍靠**震动 + 屏幕指引**传递，绝不额外响铃。
     */
    private static void waitForTtsThenMaybeRingtone(final int n, final Session token) {
        if (sSession != token || token.ended) return;
        if (TtsSpeaker.isUsable() || TtsSpeaker.isSpeakVerified()) {
            CallDiag.log("提醒", "语音引擎可用 → 只播报（不响铃：微信自己的铃声已在响）");
            return;
        }
        if (n >= TTS_WAIT_STEPS) {
            if (sSession != token || token.ended) return;
            if (TtsSpeaker.isUsable()) return;
            // 这里以前会 startRingtone()。按用户要求已去掉：
            // 微信的来电铃声本身就在响，再叠加一个只会更吵，且盖住语音。
            CallDiag.log("提醒", "等待 4 秒仍无法语音播报 → 不响铃（微信自带铃声已足够），"
                    + "改用震动与屏幕指引提醒。" + TtsSpeaker.describeProblem());
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
     * 【v1.12】不再额外响铃——微信来电铃声已经在响，叠加只会更吵。
     */
    private static synchronized void onAcceptFailed() {
        Session s = sSession;
        if (s == null || s.ended || s.answered) return;
        CallDiag.log("接听", "自动接听失败 → 改为语音 + 震动 + 屏幕指引，提醒老人自己点");
        s.handled = false;      // 放开，让接听流程可以重来（例如老人自己点）
        sFirstAnnounce = false;
        // 用可自定义的「没自动接听」文案，而不是固定的那一句
        TtsSpeaker.speak(SpeakScript.render(sApp, SpeakScript.Item.ACCEPT_FAILED,
                s.displayName, s.video, 0));

        // 不再调用 startRingtone：微信自己的来电铃声就是最响亮的提醒，
        // 我们只需要补上"该点哪里"的语音与视觉指引。
        if (sApp != null) {
            startVibration(sApp);
            GuideOverlay.show(sApp, s.displayName, false, 0L);
        }

        // 【v1.12 关键修复】失败后要继续自己重试，而不是等到 8 秒后那一轮就不管了。
        // 用户实测：整通电话从头到尾都没自动接上——因为旧逻辑里
        // 「第一次尝试（8 秒）→ 失败 → 只在超时前都不再尝试」，
        // 而微信来电界面经常是响铃好几秒后才真正铺满全屏，
        // 第一次尝试时界面还没出来，于是永远错过了。
        // 现在失败后每 RETRY_AFTER_FAIL_MS 重试一次，直到接通/挂断/超时。
        sHandler.removeCallbacks(sRetryAccept);
        sHandler.postDelayed(sRetryAccept, RETRY_AFTER_FAIL_MS);

        sHandler.removeCallbacks(sAnnounceLoop);
        sHandler.postDelayed(sAnnounceLoop, ANNOUNCE_INTERVAL_MS);
    }

    /** 自动接听失败后的重试间隔 */
    private static final long RETRY_AFTER_FAIL_MS = 4000L;

    /**
     * 失败后的自动重试：把接听流程重新走一遍。
     * 只在「开着自动接听」且「还没接通/没结束」时才重试，避免打扰只想手动接的场景。
     */
    private static final Runnable sRetryAccept = new Runnable() {
        @Override
        public void run() {
            Session s = sSession;
            if (s == null || s.ended || s.answered) return;
            if (!s.autoAnswer) return;                 // 本来就是"只提醒"，不重试
            if (s.retryCount >= MAX_ACCEPT_RETRY) {
                CallDiag.log("接听", "自动接听已重试 " + MAX_ACCEPT_RETRY
                        + " 次仍未成功 → 停止重试，继续用语音+屏幕指引提醒老人自己点");
                return;
            }
            s.retryCount++;
            CallDiag.log("接听", "第 " + s.retryCount + "/" + MAX_ACCEPT_RETRY
                    + " 次重试自动接听（上次没点到微信的接听键）");
            s.handled = false;
            performAccept(true);
        }
    };

    /** 一次来电里最多重试几次自动接听 */
    private static final int MAX_ACCEPT_RETRY = 4;

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

    /** 当前是否是「全屏来电界面」（屏幕上真有绿色接听键的那一种形态） */
    private static boolean isFullScreenCallUi() {
        CallHelperAccessibilityService svc = CallHelperAccessibilityService.get();
        return svc != null && svc.isFullScreenCallUi();
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
        if (sRingtone != null) stopRingtone(); // 本版本不再使用铃声（见 waitForTtsThenMaybeRingtone）

        long remain = (s.autoAnswerAt - System.currentTimeMillis()) / 1000L + 1;
        // 先看当前是全屏界面还是只有通知：两种情况下老人该做的动作不一样，
        // 播报内容必须跟着变，否则屏幕上没有绿色圆圈却让他"点绿色圆圈"，只会让人懵。
        boolean fullScreen = isFullScreenCallUi();
        s.fullScreenSeen = s.fullScreenSeen || fullScreen;

        // 【v1.12】自动接听开启时，倒计时必须一直念、而且要念得准。
        // 之前只有"整句提示"里带一次秒数，之后每 8 秒才更新，老人听到的
        // 倒计时是跳着走的（8 秒 → 0 秒），等于没有倒计时。
        // 现在自动接听期间每 2.5 秒报一次剩余秒数，让老人心里有数。
        // 文案本身也可在设置页自定义（见 SpeakScript）。
        if (s.autoAnswer && remain > 0 && remain <= AUTO_COUNTDOWN_MAX_SEC && !sFirstAnnounce) {
            TtsSpeaker.speak(SpeakScript.render(sApp, SpeakScript.Item.AUTO_WAIT,
                    s.displayName, s.video, remain));
            sAnnounceCount++;
            return;
        }

        // 【v1.13】所有播报文案改为可自定义（SpeakScript），默认值就是下面这些。
        // 念给老人的名字用的是 displayName —— 它是 App 里配置的称呼（「丈母娘」），
        // 不是微信昵称（「hh」），见 Session.displayName 的说明。
        StringBuilder sb = new StringBuilder();
        if (sFirstAnnounce) {
            sFirstAnnounce = false;
            sb.append(SpeakScript.render(sApp, SpeakScript.Item.GREETING,
                    s.displayName, s.video, remain));
            if (s.autoAnswer && remain > 0) {
                sb.append(SpeakScript.render(sApp, SpeakScript.Item.AUTO_WAIT,
                        s.displayName, s.video, remain));
                sb.append(" ").append(SpeakScript.render(sApp,
                        fullScreen ? SpeakScript.Item.GUIDE_FULL : SpeakScript.Item.GUIDE_NOTIFY,
                        s.displayName, s.video, remain));
            } else if (fullScreen) {
                sb.append(SpeakScript.render(sApp, SpeakScript.Item.GUIDE_FULL,
                        s.displayName, s.video, remain));
            } else {
                sb.append(SpeakScript.render(sApp, SpeakScript.Item.GUIDE_NOTIFY,
                        s.displayName, s.video, remain));
            }
        } else if (s.autoAnswer && remain > 0) {
            sb.append(SpeakScript.render(sApp, SpeakScript.Item.AUTO_WAIT,
                    s.displayName, s.video, remain));
        } else {
            sb.append(SpeakScript.render(sApp,
                    fullScreen ? SpeakScript.Item.REPEAT_FULL : SpeakScript.Item.REPEAT_NOTIFY,
                    s.displayName, s.video, remain));
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
                    .setContentTitle(sSession.displayName + " 来电话了")
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
        sHandler.removeCallbacks(sEnsureFullScreen);
        sHandler.removeCallbacks(sRetryAccept);
        sPullAttempt = 0;
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
