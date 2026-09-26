package com.jia.callhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 微信专用无障碍服务。
 *
 * 作用：
 * 1. 看到微信来电界面（「邀请你视频/语音通话」）→ 通知 CallSessionManager 播报来电人
 * 2. 看到通话已接通（出现静音/免提）→ 停止播报
 * 3. 提供接听能力：替用户按下微信里的接听键（本应用自己不显示任何界面）
 *
 * 只监听 com.tencent.mm 一个包，其他应用零开销。
 *
 * ⚠️ v1.9 的重要修正：**微信的接听键不一定有文字**。
 * 实测（微信视频来电全屏界面）能看到的文字只有「隐藏 / 邀请你视频通话 / 翻转 /
 * 模糊背景 / 摄像头已开」，底部那两个圆钮（红挂断、绿接听）在无障碍树里
 * 可能只有一个图标，既没有 text 也没有 contentDescription。
 * 旧版本只按「接听」两个字找节点，于是必然找不到 → 永远点不到 → 不会自动接听。
 * 现在改成四级定位：语义（文字/描述）→ 几何（屏幕右下角那个可点击圆钮）→
 * 镜像（由左下角挂断键左右对称推出）→ 比例坐标兜底（兜底只点一次）。
 *
 * ⚠️ v1.11 的两处重要修正（都来自用户实测）：
 *
 * 【一】先判形态，再点击。
 * 微信来电在屏幕上会出现三种形态：① 全屏来电界面（有绿/红按钮）
 * ② 下拉通知栏里的通知 ③ 屏幕顶部的横幅通知。只有 ① 上才有接听键。
 * 旧版本不管哪种形态都去"按比例点右下角"，在 ②③ 下等于在通知栏/桌面上乱点。
 * 现在 {@link #isFullScreenCallUi()} 先判形态，只有 ① 才允许点；
 * 其余形态返回 {@link #RESULT_NOT_RINGING}，由上层先把全屏界面拉出来（见
 * CallSessionManager.ensureFullScreenStep）。
 *
 * 【二】定位基准从"物理屏幕"改成"微信窗口"。
 * 有些手机底部有三键虚拟导航栏，微信窗口的底部比物理屏幕底部高一个导航栏
 * （约 48dp / 144px）。仍然按整屏比例算，接听键的点会偏低 128px ——
 * 而按钮半径只有 109px，于是每一下都点进导航栏里。
 * 现在基准取微信窗口的实际区域（三键导航时它自动不含导航栏），
 * 拿不到窗口时退化为「屏幕高度 − 导航栏高度」（导航栏高度从无障碍窗口实测）。
 */
public class CallHelperAccessibilityService extends AccessibilityService {

    /** 只认微信。用于事件过滤，也用于「只在微信界面里点击」的防呆校验 */
    private static final String WECHAT_PKG = "com.tencent.mm";

    /** 接听结果 */
    public static final int RESULT_CLICKED_PRECISE = 1; // 精确点到（有节点树可读，可靠）
    public static final int RESULT_CLICKED_BLIND = 2;   // 盲点坐标（不确定，不要再点第二次）
    public static final int RESULT_NOT_WECHAT = 3;      // 微信界面不在前台，需要先把它拉起来
    public static final int RESULT_NO_WINDOW = 4;       // 连界面都拿不到，无法操作
    /**
     * 微信在，但**当前不是全屏来电界面** —— 例如只显示了顶部横幅通知（heads-up）
     * 或下拉通知栏里的通知。这时屏幕上根本没有接听键，绝不能盲点坐标
     * （会点到通知、状态栏或别的东西）。必须先把它拉成全屏来电界面再点。
     */
    public static final int RESULT_NOT_RINGING = 5;

    /** 微信通话界面形态 */
    public static final int UI_NONE = 0;       // 没有全屏来电界面（只有通知/横幅，或已结束）
    public static final int UI_RINGING = 1;    // 全屏来电界面（有绿色接听键）
    public static final int UI_IN_CALL = 2;    // 已接通

    /**
     * 来电界面的特征词（**松散**版本）。
     *
     * 只用于「还站在响铃页上吗」这类**会话已经在进行中**的判断
     * （比如 WeChatClicker 点击后判断"是不是还在响"）。不可以用它来判定"这是一通新来电"，
     * 理由见下面的 {@link #INVITE_PHRASES}。
     *
     * 注意**不能放「挂断」**：通话中的界面也有挂断键，放进去会把"已接通"误判成"正在响铃"
     * （v1.16 之前正是因此出现「接听后还在提示」）。判断"是不是响铃中"只看
     * 「邀请你…」和「接听」这两个只在响铃阶段存在的证据。
     */
    private static final String[] RINGING_KEYS = {"邀请你", "邀请对方", "接听"};

    /**
     * 【v1.22】**完整**的来电邀请话术 —— 判定"这是一通真来电"的唯一文字证据。
     *
     * ## 为什么不能只用松散的「邀请你」去判定新来电
     *
     * 用户实测反馈：打开微信聊天框就会开始播报「XXX 来电话了」，而根本没人打过。
     *
     * 根因就在旧代码只用 `RINGING_KEYS = {"邀请你", ...}` 这一个条件 ——
     * **微信会把通话留痕以灰色小字留在聊天记录里**，内容恰好就是
     * 「XXX 邀请你语音通话」/「XXX 邀请你视频通话」。
     * 无障碍一扫描聊天页面就能读到这句话，于是把它当成"此刻正在响铃"。
     *
     * 单看文字，聊天记录里的这句话和真来电页上那句话**长得完全一样**，
     * 靠措辞是区分不开的，必须引入结构性证据（见 {@link #looksLikeRealIncoming}）。
     *
     * 这里列的是完整话术（而不是"邀请你"三个字），并补了几种官方/常见变体。
     * 仍然可能存在没覆盖到的写法，但漏检（这通没提醒）远比误报（没人打却播报）可接受。
     */
    private static final String[] INVITE_PHRASES = {
            "邀请你视频通话", "邀请你语音通话", "邀请你通话",
            "邀请你进行视频通话", "邀请你进行语音通话",
            "邀请你视频", "邀请你语音",
            "邀请您视频通话", "邀请您语音通话", "邀请您通话",
            "向您发起视频通话", "向您发起语音通话",
            "发起视频通话", "发起语音通话"
    };

    /**
     * 真来电页底部那两个圆钮的文案（拒绝/挂断 与 接听）。
     *
     * 这是区分"聊天记录里的历史通话留痕"和"此刻正在响铃的来电页"的**关键**：
     * 聊天页那句灰字旁边既没有拒绝键也没有接听键。
     */
    private static final String[] RING_ACTIONS = {"挂断", "拒绝", "接听"};

    /** 关系最紧密的一个额外佐证：最近一次微信窗口变化是不是 VoIP 通话页（Activity 类名） */
    private volatile String mLastWinClass;
    /** 接听键可能的文字（少数版本/语言下存在） */
    private static final String[] ANSWER_KEYS = {"接听", "接听电话", "Answer", "Accept", "answer", "accept"};
    /** 通话已接通的特征（接通后才会出现静音/免提这类按钮） */
    private static final String[] IN_CALL_KEYS = {"静音", "免提", "扬声器", "切换摄像头", "摄像头已关"};

    /**
     * 接听键的位置，用「占屏幕的百分比」表示 —— 这样任何品牌、任何尺寸、
     * 任何分辨率的手机都通用，不需要为每种机型单独适配。
     *
     * 数据来源：实测微信视频来电界面截图（1220 × 2712），
     * 用像素分析找出底部两个圆钮的中心：
     *   红色挂断键中心 (240, 2403)，直径 216px
     *   绿色接听键中心 (980, 2403)（界面左右对称推出）
     * 于是：
     *   接听键中心横坐标 = 980 / 1220 = 80.3% 屏宽
     *   接听键中心距底部 = (2712 - 2403) / 2712 = 11.4% 屏高
     *   按钮半径         = 108 / 1220 = 8.85% 屏宽
     * （挂断键就在同一行的左侧 19.7% 处。）
     *
     * 水平方向还能进一步校准——视频来电界面上方的「摄像头已开 / 模糊背景 / 翻转」
     * 与底部的绿色接听键在同一竖列，用它们的横坐标替换经验值会准得多（见 tapAnswerByRatio）。
     */
    private static final float ANSWER_X_RATIO = AnswerPointPrefs.DEF_X;
    /** 接听键中心距屏幕底部的比例（原点取左下角，见 answerPoint） */
    private static final float ANSWER_BOTTOM_RATIO = AnswerPointPrefs.DEF_BOTTOM;
    /** 接听键半径占屏幕宽度的比例（屏幕指引画圈时用） */
    public static final float ANSWER_RADIUS_RATIO = AnswerPointPrefs.DEF_RADIUS;
    /** 用于校准接听键横坐标的上方按钮文案 */
    private static final String[] X_ANCHOR_KEYS = {"摄像头已开", "摄像头已关", "模糊背景", "翻转"};
    /**
     * 同列校准的最大容差（占屏宽比例）。
     * 实测微信把「翻转 / 模糊背景 / 摄像头已开」三个功能键按"平均分布"排布，
     * 而接听键是按屏幕左右对称分列在两端的——两者并非严格同列，实测差约 69px
     * （5.7% 屏宽）。所以只有偏差在 2% 屏宽以内才敢用它微调，否则一律相信实测比例。
     */
    private static final float X_ANCHOR_TOLERANCE = 0.02f;

    private static volatile CallHelperAccessibilityService sInstance;
    /**
     * 应用上下文（服务还没连上时也要能读到「用户校准过的接听键位置」，
     * 否则屏幕指引会先按默认位置画一帧，再跳到用户位置，看着像"圈自己跑了"）。
     */
    private static volatile Context sAppCtx;
    private static final long SCAN_INTERVAL_MS = 1200;
    /** 【v1.21】窗口状态变化的限流窗口，见 onAccessibilityEvent 里的说明 */
    private static final long WIN_STATE_THROTTLE_MS = 300L;
    private volatile boolean mWindowScanPending;
    /** 主线程 Handler：用于限流尾扫描 */
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private volatile long mLastScanAt = 0;
    private volatile long mLastTreeDumpAt = 0;

    /** {@link #findWeChatWindow} / {@link #navigationBarHeight} 的短缓存（见各自注释） */
    private static final long WIN_CACHE_TTL_MS = 150L;
    private static final long NAV_CACHE_TTL_MS = 2000L;
    private static final Object WIN_LOCK = new Object();
    private WeChatWin mWinCache;
    private volatile long mWinCacheAt = 0;
    private int mNavCache = -1;
    private volatile long mNavCacheAt = 0;

    // ---------------- 【v1.22】看屏幕（截图识别） ----------------
    //
    // 到 v1.21 为止，接听键的位置只有两种来源：内置默认比例、用户手动校准。
    // 两者本质都是"上一次量到的一个数"，屏幕上一有变化（微信改版、视频来电而非语音、
    // 换了导航方式）就不再是真实位置 —— 表现为"有圈、也点了、就是接不通"。
    // 微信 8.0.78 的来电页又是整页自绘的，连"读节点校正一次"的机会都没有。
    //
    // 所以这里加一条不看运气的路：无障碍服务本身可以截屏（Android 11+，
    // 且已在 accessibility_service_config 里声明 android:canTakeScreenshot），
    // 直接**看**屏幕，把微信来电页底部那两个圆钮（左红"挂断"、右绿"接听"）找出来。
    // 全程本地像素分析，不联网、不落盘。
    private volatile ScreenLook.Result mLook;
    private volatile long mLookAt = 0L;
    /** 一次截图正在进行：期间不再发起第二次（系统本来也会按 1 秒限流） */
    private volatile boolean mLookBusy;
    private volatile long mLookStartAt = 0L;
    private static final Object LOOK_LOCK = new Object();
    /** 两次截图之间的最小间隔，配合系统限流 */
    private static final long LOOK_MIN_INTERVAL_MS = 800L;
    /** 截图结果的有效期：超过这个时间界面早就变了，不能拿旧图当证据 */
    private static final long LOOK_FRESH_MS = 3000L;

    public static CallHelperAccessibilityService get() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        sAppCtx = getApplicationContext();
        CallDiag.init(this);
        CallDiag.log("无障碍", "服务已连接");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        dropWinCache();
        CallDiag.log("无障碍", "服务已断开（系统关闭了无障碍开关？）");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        dropWinCache();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !WECHAT_PKG.equals(pkg.toString())) return;

        // 【v1.22】总开关：关掉之后一条界面都不扫。
        // 和通知监听那里共用同一个开关、也都在入口最前面拦，
        // 避免出现"关了通知却还在扫界面"这种半关的状态。
        if (!WhiteListManager.isAppEnabled(sAppCtx != null ? sAppCtx : this)) return;

        int type = event.getEventType();

        // 【v1.22】记下当前微信页面的 Activity 类名。
        // 这是"此刻站在哪个页面"最直接的证据 —— 比去读节点树可靠得多，
        // 因为节点树在整页自绘时可能一个字都没有，而类名永远是系统给出来的。
        // 微信的 VoIP 通话页类名含 "voip"（如 com.tencent.mm.plugin.voip.ui.VideoActivity），
        // 用来佐证"确实站在来电页上"，把聊天记录里的历史通话留痕排除掉。
        CharSequence cn = event.getClassName();
        if (cn != null && cn.length() > 0) {
            mLastWinClass = cn.toString();
        }

        // 【v1.21】窗口状态变化也要限流。
        // 微信整页自绘的来电界面在响铃时会连续抛 TYPE_WINDOW_STATE_CHANGED，
        // 每次都做一次全树扫描的话，光"等待接听"这一分钟就能扫几百次，
        // 这是本 App 最容易被系统判定为耗电的地方。
        //
        // 但"省电"不能拿"漏判"来换：这里不是简单丢弃，而是排一次**尾扫描** ——
        // 限流期内的后续事件只会刷新那一个待执行任务，最后一次变化一定会扫到，
        // 不会像粗暴丢弃那样把"已经接通"这个关键转变丢掉。
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            long now = SystemClock.elapsedRealtime();
            if (now - mLastScanAt >= WIN_STATE_THROTTLE_MS) {
                mWindowScanPending = false;
                scanNow(true);
            } else if (!mWindowScanPending) {
                mWindowScanPending = true;
                sMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mWindowScanPending = false;
                        scanNow(true);
                    }
                }, WIN_STATE_THROTTLE_MS);
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // 内容变化事件很频繁，限流扫描
            long now = SystemClock.elapsedRealtime();
            if (now - mLastScanAt > SCAN_INTERVAL_MS) {
                scanNow(false);
            }
        }
    }

    @Override
    public void onInterrupt() {
        // 无需处理
    }

    // ---------------- 界面状态判断 ----------------

    /**
     * 微信窗口的根节点 + 它在屏幕上的实际区域。
     *
     * {@code bounds} 是本版本新增的关键数据：微信的按钮是摆在自己窗口里的，
     * 所以「接听键在窗口的什么位置」才是稳定的；而窗口本身会随导航方式变化
     * （三键导航时窗口底部比物理屏幕底部高出一个导航栏）。
     */
    public static class WeChatWin {
        public AccessibilityNodeInfo root;
        public final Rect bounds = new Rect();
    }

    /** 供诊断日志读取当前微信窗口（拿不到返回 null）。只用于打日志，不改变任何行为 */
    public WeChatWin debugWeChatWindow() {
        try {
            return findWeChatWindow();
        } catch (Throwable t) {
            return null;
        }
    }

    // 【v1.21】这里以前有个 openNotificationShade()（下拉通知栏）和一个
    // getForegroundPackage()，全量扫描后确认都没有调用点。
    //
    // openNotificationShade 尤其要删干净：v1.21 已经移除了"下拉通知栏找来电通知"这条路径
    // ——工程里从来没有任何地方会把通知栏收回去，一旦拉下来，后面所有手势点击
    // 都会落在通知栏上（详见 DEVELOPMENT.md 的陷阱清单）。留着一个能一键拉下通知栏的
    // 公共方法，等于把那颗雷重新放回抽屉里。

    /**
     * 在所有窗口里找属于微信的那一个，并记下它的屏幕区域。
     *
     * 为什么不直接用 getRootInActiveWindow()：来电时我们会在屏幕最上层显示
     * 「屏幕指引」浮层（GuideOverlay），它是另一个窗口。若只取"最上面的活动窗口"，
     * 有可能拿到我们自己的浮层，于是误判成「当前不在微信」→ 不点击、也判断不出
     * 是否已接通。所以这里改为在所有窗口里找属于微信的那一个，做到"浮层在场也不受影响"。
     *
     * 【v1.21】加了 150ms 的短缓存。一次接听决策里这个方法会被调用 4~6 次
     * （判断界面状态、算坐标、找按钮、确认结果），每次都要跨进程取窗口树。
     * 缓存窗口刻意这么短：来电界面一秒能刷好几帧，缓存久了会拿着过期的树做判断，
     * 反而变成"明明接通了却判断成没接通"。点击之后请主动调 {@link #dropWinCache()}。
     */
    private WeChatWin findWeChatWindow() {
        long now = SystemClock.elapsedRealtime();
        synchronized (WIN_LOCK) {
            if (mWinCache != null && now - mWinCacheAt < WIN_CACHE_TTL_MS) {
                return mWinCache;
            }
        }
        WeChatWin found = findWeChatWindowUncached();
        synchronized (WIN_LOCK) {
            mWinCache = found;
            mWinCacheAt = now;
        }
        return found;
    }

    /** 丢弃微信窗口缓存。点了屏幕、拉起界面之后都应该调一次，别让下一次判断读到点击前的旧树 */
    public void dropWinCache() {
        synchronized (WIN_LOCK) {
            mWinCache = null;
            mWinCacheAt = 0;
        }
    }

    private WeChatWin findWeChatWindowUncached() {
        // 【v1.21】被我们"看过一眼然后扔掉"的节点和窗口，要显式 recycle()。
        // 无障碍返回的 AccessibilityNodeInfo/AccessibilityWindowInfo 背后是跨进程句柄，
        // 不回收要等到 GC 才释放（系统会打印 "Instances not recycled" 警告）。
        // 这里只回收**确定没人再用**的那些：不是微信的窗口、后台不可见的窗口。
        // 被选中的那个绝不能回收 —— 它还要交给调用方将继续用（还有 150ms 缓存）。
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) {
                if (isWeChatWindow(active)) {
                    WeChatWin w = new WeChatWin();
                    w.root = active;
                    try { active.getBoundsInScreen(w.bounds); } catch (Exception ignore) {}
                    return w;
                }
                recycleQuietly(active);
            }
        } catch (Exception ignore) {}
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo win : wins) {
                    if (win == null) continue;
                    AccessibilityNodeInfo r;
                    try {
                        r = win.getRoot();
                    } catch (Exception ignore) {
                        continue;
                    }
                    if (r == null) continue;
                    if (!isWeChatWindow(r)) {
                        recycleQuietly(r);
                        continue;
                    }
                    // 只认**真的显示在屏幕上**的微信窗口。
                    // getWindows() 也可能返回后台窗口，而后台窗口上当然没有接听键，
                    // 拿它来判断"是不是全屏来电界面"会得出完全错误的结论。
                    boolean visible;
                    try {
                        visible = r.isVisibleToUser();
                    } catch (Exception ignore) {
                        visible = true; // 取不到可见性时按"可见"处理，宁可多扫一次也别漏
                    }
                    if (!visible) {
                        recycleQuietly(r);
                        continue;
                    }
                    WeChatWin w = new WeChatWin();
                    w.root = r;
                    try { win.getBoundsInScreen(w.bounds); } catch (Exception ignore) {}
                    if (w.bounds.isEmpty()) {
                        try { r.getBoundsInScreen(w.bounds); } catch (Exception ignore) {}
                    }
                    // bounds 已经拷到我们自己的 Rect 里，这个 window 句柄可以还回去了
                    recycleQuietly(win);
                    return w;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static void recycleQuietly(AccessibilityNodeInfo n) {
        if (n == null) return;
        try {
            n.recycle();
        } catch (Exception ignore) {
        }
    }

    private static void recycleQuietly(AccessibilityWindowInfo w) {
        if (w == null) return;
        try {
            w.recycle();
        } catch (Exception ignore) {
        }
    }

    private AccessibilityNodeInfo wechatWindowRoot() {
        WeChatWin w = findWeChatWindow();
        return w != null ? w.root : null;
    }

    /**
     * 导航栏高度（像素）。取不到时返回 0（当作手势导航，那时窗口确实铺满整屏）。
     *
     * 为什么要它：见 {@link #answerPointInternal}。三键导航的手机上，
     * 接听键的实际位置比"按物理屏幕比例"算出来的要高一个导航栏 ——
     * 不扣掉就会点到导航栏里去（实测偏差 128px，而按钮半径只有 109px，必然点空）。
     */
    private int navigationBarHeight() {
        // 【v1.21】导航栏高度在一次决策里会被问 3~4 次，而它几乎不变（只有横竖屏切换才会变），
        // 这里缓存 2 秒，省掉重复的 getWindows() 跨进程调用。
        long now = SystemClock.elapsedRealtime();
        if (mNavCache >= 0 && now - mNavCacheAt < NAV_CACHE_TTL_MS) return mNavCache;
        int v = navigationBarHeightUncached();
        mNavCache = v;
        mNavCacheAt = now;
        return v;
    }

    private int navigationBarHeightUncached() {
        int[] size = screenSize();
        int screenW = size[0], screenH = size[1];
        if (screenH <= 0) return 0;
        // ① 最准：无障碍能直接看到导航栏窗口，再按实际情况量出高度
        //    （手势导航只有一条细条，三键导航约 48dp，两者高度差很多，所以必须实测）
        //    注意：无障碍把状态栏和导航栏都归为 TYPE_SYSTEM，只能靠"位置+形状"认出来——
        //    导航栏的特征是：横跨整个屏幕宽度、紧贴屏幕最底部、高度不大。
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                int best = 0;
                for (AccessibilityWindowInfo w : wins) {
                    if (w == null) continue;
                    try {
                        if (w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                        Rect r = new Rect();
                        w.getBoundsInScreen(r);
                        int hh = r.height();
                        if (hh <= 0 || hh >= screenH / 4) continue;          // 太高，肯定不是
                        if (r.width() < screenW * 0.9f) continue;            // 没横跨屏幕宽度，不是
                        if (r.bottom < screenH - 2) continue;                // 没贴住屏幕底部，不是
                        if (hh > best) best = hh;
                    } finally {
                        // 【v1.21】这里每个窗口都只是量了一下尺寸，用完必须还回去
                        recycleQuietly(w);
                    }
                }
                if (best > 0) return best;
            }
        } catch (Exception ignore) {}
        // ② 退一步：读系统资源里的导航栏高度
        try {
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) {
                int hh = getResources().getDimensionPixelSize(id);
                if (hh > 0 && hh < screenH / 4) return hh;
            }
        } catch (Exception ignore) {}
        return 0;
    }

    /**
     * 当前是否是**全屏来电界面**（也就是屏幕上有绿色接听键可点的那个界面）。
     *
     * 这是 v1.11 的核心修正之一。用户实测发现微信来电在手机上会出现三种形态：
     *   ① 全屏来电界面（点开就是整屏的「邀请你视频通话」+ 绿/红按钮）→ 能点接听
     *   ② 下拉通知栏里的来电通知 → 屏幕上没有接听键
     *   ③ 屏幕顶部的横幅通知（heads-up）→ 屏幕上也没有接听键
     * 旧版本不管哪种形态都去「按比例盲点右下角」，在 ②③ 下等于在通知栏/桌面上瞎点，
     * 既接不到电话，还可能点到别的东西。现在先判形态：只有 ① 才允许点。
     *
     * 判断依据：
     *   - 拿到微信窗口，且窗口里读得到「邀请…通话」等来电特征 → 是
     *   - 微信界面完全自绘、一个字都读不到，但窗口本身占满屏幕（不是小窗/分屏）→ 也认
     *     （否则这种机型就彻底没法自动接听了）
     */
    public boolean isFullScreenCallUi() {
        WeChatWin w = findWeChatWindow();
        if (w == null || w.root == null) return false;
        if (isInCall(w.root)) return false;
        if (isRinging(w.root)) return true;
        // 完全自绘的界面：读不到任何文字，只能靠"窗口是不是铺满整屏"来判断
        if (!canReadUiText(w.root) && isWindowFullScreen(w)) {
            return true;
        }
        return false;
    }

    /** 微信通话界面当前形态 */
    public int callUiState() {
        WeChatWin w = findWeChatWindow();
        if (w == null || w.root == null) {
            // 【v1.18】窗口读不到，但微信确实在前台（自绘通话页 / 指引浮层干扰活动窗口判定）
            // → 报"全屏来电界面"。旧实现这里一律返回 UI_NONE，
            // 导致上层认为"屏幕上没有接听键"，反复拉起却永远不点。
            //
            // 收紧条件：必须是**当前有来电会话**且微信在前台，避免离开通话后
            // 微信留在后台还被误当成来电界面。
            if (isWeChatForeground() && CallSessionManager.isSessionActive()) {
                return UI_RINGING;
            }
            return UI_NONE;
        }
        if (isInCall(w.root)) return UI_IN_CALL;
        if (isFullScreenCallUi()) return UI_RINGING;
        // 读到微信界面、不是通话中、也不是全屏来电页，但**有来电会话在进行**：
        // 很可能是"微信刚被拉起、页面还没铺开"的中间态。报 UI_RINGING 让上层继续尝试，
        // 比报 UI_NONE 让上层干等着强（这正是不肯点的另一种情形）。
        //
        // 【v1.20 收紧】上面那条放宽是有代价的：只要"有来电会话 + 微信在前台"，
        // 哪怕微信此刻停在聊天列表，也会被报成"全屏来电界面"，上层就会往
        // 右下角坐标戳——在聊天页上那是在别人的输入框/按钮上乱点。
        // 所以补一条硬约束：只有在**读不到内容**（自绘通话页的典型样子）或
        // **窗口确实铺满整屏**时，才允许按中间态处理。其余一律如实报 UI_NONE。
        if (CallSessionManager.isSessionActive() && isWeChatForeground()) {
            // 【v1.21】只保留"读不到内容"这一个条件。
            // 原来后面还跟着 `|| isWindowFullScreen(w)`，那句话把这个收紧条件又作废了：
            // isWindowFullScreen 只要求窗口覆盖 ≥90% 宽、≥85% 高 ——
            // 微信主页、聊天页、朋友圈全都满足。于是**读得到内容的微信聊天页**
            // 照样被报成 UI_RINGING，上面这段注释声明的"收紧"根本没收。
            // 「读不到内容」才是整页自绘来电页的独有特征，用它就够了。
            if (!canReadUiText(w.root)) {
                return UI_RINGING;
            }
            CallDiag.log("无障碍", "微信在前台且确有来电会话，但当前是读得到内容的普通页面"
                    + "（窗口也没铺满）→ 不算来电界面，先把它拉起来");
        }
        return UI_NONE;
    }

    /** 窗口是否基本铺满整屏（用来把「全屏来电界面」和「小窗/分屏/聊天页」区分开） */
    private boolean isWindowFullScreen(WeChatWin w) {
        if (w == null) return false;
        int[] size = screenSize();
        int w2 = size[0], h2 = size[1];
        if (w2 <= 0 || h2 <= 0) return false;
        int usableH = h2 - navigationBarHeight();
        return w.bounds.width() >= w2 * 0.9f && w.bounds.height() >= usableH * 0.85f;
    }

    /**
     * 【v1.21 性能重构】这里以前要**遍历二十多遍整棵节点树**才能给一个结论。
     *
     * 旧调用链：isRinging() → 先跑 isInCall()（里头 2 次 findNode("接听") +
     * 2 次 findNode("邀请你") + 5 个 IN_CALL_KEYS + 一整遍 hasCallDuration），
     * 再跑 3 个 RINGING_KEYS，最后又查一次「接听」——而**每个 findNode 都是一次完整的树遍历**。
     * 更关键的是，每唤一次 `getChild()` 都是一次打到微信进程的 IPC，
     * sWatchdog 每 1.5 秒就要跑一整套，这里既是本 App 后台耗电的大头，
     * 也会拖慢被调用的微信。现在改成**一次遍历把所有信号都采回来**。
     */
    private void scanNow(boolean windowChanged) {
        mLastScanAt = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo root = wechatWindowRoot();
        if (root == null) {
            if (windowChanged) CallDiag.log("无障碍", "收到微信窗口变化，但拿不到微信界面内容");
            return;
        }

        // 【v1.16】先看「是不是已经接通」，再看「是不是在响铃」。
        // 顺序很关键：通话中界面和响铃界面有一部分是重叠的（都有「挂断」），
        // 先判响铃就会把刚接起来的电话当成新来电，导致接听后还在一直提示。
        UiPhase ph = phaseOf(root);
        if (ph == UiPhase.IN_CALL) {
            CallDiag.log("无障碍", "识别到微信「通话中」界面 → 不再当作新来电（避免接听后重复提醒）");
            CallSessionManager.onWeChatCallAnswered(this);
        } else if (ph == UiPhase.RINGING) {
            // 【v1.22 关键修复】看到「邀请你…通话」不等于有人正在打来。
            // 微信会把通话留痕以灰字留在聊天记录里，打开聊天框就能被扫到 ——
            // 旧代码在这里直接判定新来电，于是出现"打开微信就开始播报"的误触发。
            if (!realIncoming(root)) {
                CallDiag.log("无障碍", "读到「邀请你…通话」字样，但不满足真来电的判定条件"
                        + "（多半是聊天记录里的历史通话留痕，或通话邀请已被撤回）"
                        + " → 不认定为来电，不播报、不建会话");
                return;
            }
            boolean video = findNode(root, "视频", false) != null
                    || findNode(root, "翻转", false) != null
                    || findNode(root, "模糊背景", false) != null;
            String name = guessCallerName(root);
            // 记录一次界面结构：万一以后微信改版、点位又对不上，
            // 有这份记录就能看出微信到底暴露了哪些文字/按钮
            dumpTreeForDiag(root);
            CallDiag.log("无障碍", "识别到微信来电界面：名字=" + name
                    + " 视频=" + video + " 有接听文字=" + (findNode(root, "接听", false) != null));
            CallSessionManager.onIncomingViaA11y(this, name, video);
        } else if (findNode(root, "通话结束", false) != null
                || findNode(root, "已结束", false) != null) {
            CallSessionManager.onWeChatCallEnded(this, "界面显示通话结束");
        }
    }

    /** 界面处于哪个阶段 */
    private enum UiPhase {
        /** 读不到 / 既不像响铃也不像通话中 */
        UNKNOWN, RINGING, IN_CALL
    }

    private interface NodeVisitor {
        void visit(AccessibilityNodeInfo n);
    }

    /**
     * 遍历节点树的唯一实现，带节点数上限（防止微信某次布局异常把主线程拖死）。
     * 之后工程里凡是"要把界面看一遍"的判定，都应该复用它而不是各写一份遍历。
     */
    private static void walkOnce(AccessibilityNodeInfo root, NodeVisitor v, int maxNodes) {
        if (root == null || v == null) return;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < maxNodes) {
            AccessibilityNodeInfo n = stack.pop();
            visited++;
            v.visit(n);
            int cc = n.getChildCount();
            for (int i = 0; i < cc; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
    }

    /**
     * 一次遍历判定界面处于哪个阶段。判定用的词表仍然是上面那几组常量，改词不用改这里。
     */
    private static UiPhase phaseOf(AccessibilityNodeInfo root) {
        if (root == null) return UiPhase.UNKNOWN;
        final boolean[] ringing = new boolean[1];   // 「接听」/「邀请你…通话」：只在响铃阶段存在
        final boolean[] inCallKey = new boolean[1]; // 静音/免提等：接通后才有
        final boolean[] duration = new boolean[1];  // 通话计时（00:35）
        final boolean[] decline = new boolean[1];   // 「挂断」
        walkOnce(root, new NodeVisitor() {
            @Override
            public void visit(AccessibilityNodeInfo n) {
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                String st = t == null ? null : t.toString();
                String sd = d == null ? null : d.toString();
                for (int i = 0; i < 2; i++) {
                    String s = i == 0 ? st : sd;
                    if (s == null) continue;
                    for (String k : RINGING_KEYS) {
                        if (s.contains(k)) { ringing[0] = true; break; }
                    }
                    if (s.contains("挂断")) decline[0] = true;
                    for (String k : IN_CALL_KEYS) {
                        if (s.contains(k)) { inCallKey[0] = true; break; }
                    }
                    if (isCallDuration(s)) duration[0] = true;
                }
            }
        }, 600);

        // ① 还在响铃的铁证优先级最高（「挂断」在响铃与通话中都存在，所以先看这条）
        if (ringing[0]) return UiPhase.RINGING;
        // ② 接通后才会出现的通话控件
        if (inCallKey[0]) return UiPhase.IN_CALL;
        // ③ 通话计时。
        //    【v1.21 修复】它**不能**单独支撑"已接通"：那个正则 \d{1,2}:[0-5]\d
        //    会把聊天页顶部的**时间分隔条**（"14:30"）和**语音消息时长气泡**（"0:15"）
        //    也认成通话计时。而上一通电话结束时微信恰好会退回聊天页 ——
        //    紧接着第二通电话打进来，isInCall() 因此误判为真，
        //    把整通新来电当成"上一通的延续"直接吞掉
        //    （SAME_CALL_GUARD_MS=30 秒，微信响铃也就 40~60 秒，等于吃掉大半个响铃期）。
        //    现在要求它必须伴随「挂断」才算数：聊天页没有挂断键，响铃页已被 ① 排除。
        if (duration[0]) {
            return decline[0] ? UiPhase.IN_CALL : UiPhase.UNKNOWN;
        }
        return UiPhase.UNKNOWN;
    }

    /**
     * 【v1.22】这是不是**此刻真的正在响铃的来电页面**（而不是聊天记录里的历史通话留痕）。
     *
     * 只在「要不要当成一通新来电」这种**启动性**判断上使用
     * （目前唯一调用方是 {@link #scanNow}）。
     *
     * 判定要求两条**互相独立**的证据同时成立：
     *   ① 文字：树上能读到完整的来电邀请话术（{@link #INVITE_PHRASES}）
     *   ② 结构：同时还能读到拒绝/挂断/接听这类来电页专属控件（{@link #RING_ACTIONS}），
     *      或者最近一次窗口变化的 Activity 类名是微信的 VoIP 通话页
     *
     * 为什么必须这样：聊天记录里那句「XXX 邀请你语音通话」满足了 ①，
     * 但聊天页上并没有 ②③ 里任何一个，所以会被挡住。
     *
     * ⚠️ 不要在已建立会话之后用它判断"还在响铃吗" ——
     * 那时候请用 {@link #isRinging()}：整页自绘的来电页可能一个字都读不到，
     * 用这个方法会误判成"已经不是来电页"，从而错误得出"已经接通"的结论。
     */
    private boolean looksLikeRealIncoming(AccessibilityNodeInfo root) {
        if (root == null) return false;
        final boolean[] invite = new boolean[1];
        final boolean[] action = new boolean[1];
        walkOnce(root, new NodeVisitor() {
            @Override
            public void visit(AccessibilityNodeInfo n) {
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                for (int i = 0; i < 2; i++) {
                    String s = i == 0
                            ? (t == null ? null : t.toString())
                            : (d == null ? null : d.toString());
                    if (s == null) continue;
                    if (!invite[0]) {
                        for (String k : INVITE_PHRASES) {
                            if (s.contains(k)) { invite[0] = true; break; }
                        }
                    }
                    if (!action[0]) {
                        for (String k : RING_ACTIONS) {
                            if (s.contains(k)) { action[0] = true; break; }
                        }
                    }
                }
            }
        }, 600);
        if (!invite[0]) return false;
        return action[0] || voipWindow();
    }

    /**
     * 【v1.22】这是**此刻真的正在响铃的来电页**吗 —— 最终的"认不认"裁决。
     *
     * <p>三条互相独立的证据，满足任意一条就认：
     * <ol>
     *   <li><b>结构</b>：节点树上除了那句"邀请你…通话"，还能读到挂断/拒绝/接听
     *       这类只有来电页才有的控件；</li>
     *   <li><b>类名</b>：最近一次窗口变化的 Activity 是微信的 VoIP 通话页
     *       （{@code com.tencent.mm.plugin.voip.ui.VideoActivity} 一类）；</li>
     *   <li><b>画面</b>：截图里同时看到底部红色「挂断」与绿色「接听」两个实心圆钮。</li>
     * </ol>
     *
     * <p>③ 是 v1.22 新增的，也是最硬的一条：它完全不依赖微信暴露给无障碍的东西，
     * 整页自绘照样有效。"只是打开了微信聊天框"时屏幕上不存在这两个钮，
     * 于是那类误触发被彻底排除。
     *
     * <p>拿不到截图结论时先不作定论（requestLook 之后下一轮会补上），
     * 但**绝不**用"没看到"去反推"不在响"——截图可能失败、可能被系统限流。
     */
    private boolean realIncoming(AccessibilityNodeInfo root) {
        if (looksLikeRealIncoming(root)) return true;
        if (lookProvesRinging()) {
            CallDiag.log("无障碍", "截图里同时看到红色「挂断」与绿色「接听」两个圆钮"
                    + " → 确实站在微信全屏来电页上（读作节点树管用时以这条为准）");
            return true;
        }
        // 还没拿到结论：先要一张截图，下一次扫描自然会有结果。
        // 真来电页响铃时微信会持续抛窗口事件，所以下一次扫描一定会来。
        requestLook();
        return false;
    }

    /** 最近一次微信窗口变化的活动类名是否是 VoIP 通话页 */
    private boolean voipWindow() {
        String c = mLastWinClass;
        if (c == null || c.isEmpty()) return false;
        String lc = c.toLowerCase();
        return lc.contains("voip") || lc.contains("voipvideo")
                || lc.contains(".ui.videoactivity") || lc.contains(".ui.voipactivity");
    }

    // ---------------- 【v1.22】看屏幕 ----------------

    /**
     * 请求"看一眼屏幕"：把微信来电页底部那两个圆钮找出来。
     *
     * <p>节流 + 去重：多次请求只会真的截一次，结果缓存在 {@link #mLook} 里供后续使用。
     * 只能从主线程调用（截图回调本身也可能在主线程跑）。
     *
     * @param force true 时忽略最小间隔（来电刚到、必须马上看清楚时用）
     */
    public void requestLook(boolean force) {
        if (Build.VERSION.SDK_INT < 30) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (LOOK_LOCK) {
            if (mLookBusy) return;
            if (!force && now - mLookStartAt < LOOK_MIN_INTERVAL_MS) return;
            mLookStartAt = now;
            mLookBusy = true;
        }
        final int[] size = screenSize();
        // 【关键】指引层画的**也是一个绿圈**。截图前先把它藏 320 毫秒，
        // 否则识别器认出来的是我们自己画的那一圈，等于自己骗自己。
        GuideOverlay.suspendForShot(320L);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY,
                    new java.util.concurrent.Executor() {
                        @Override
                        public void execute(Runnable command) {
                            new Handler(Looper.getMainLooper()).post(command);
                        }
                    },
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult res) {
                            HardwareBuffer hb = null;
                            Bitmap bmp = null;
                            try {
                                hb = res.getHardwareBuffer();
                                bmp = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                                onShotReady(bmp, size);
                            } catch (Throwable t) {
                                CallDiag.log("看图", "读取截图像素失败：" + t);
                            } finally {
                                try { if (bmp != null) bmp.recycle(); } catch (Throwable ignore) {}
                                try { if (hb != null) hb.close(); } catch (Throwable ignore) {}
                                mLookBusy = false;
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            mLookBusy = false;
                            CallDiag.log("看图", "截图失败：" + shotErrorText(errorCode)
                                    + " → 本次仍按原方式（比例坐标）点接听键");
                        }
                    });
        } catch (Throwable t) {
            mLookBusy = false;
            CallDiag.log("看图", "发起截图失败：" + t + " → 本次仍按原方式点接听键");
        }
    }

    public void requestLook() {
        requestLook(false);
    }

    private static String shotErrorText(int code) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                return "两次截图间隔太短（系统限流）";
            }
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) {
                return "服务不具备截图能力（配置文件没声明）";
            }
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY) {
                return "显示设备无效";
            }
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW) {
                return "目标窗口不存在了";
            }
            if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                return "目标窗口禁止截屏";
            }
        }
        return "错误码 " + code;
    }

    private void onShotReady(Bitmap bmp, int[] size) {
        ScreenLook.Result r = ScreenLook.look(bmp, size[0], size[1]);
        mLook = r;
        mLookAt = SystemClock.elapsedRealtime();
        if (r == null) return;
        CallDiag.log("看图", r.note);
        rememberAnswerPoint(r);
        // 看见"红 + 绿"两个钮 = 微信此刻确实在响铃。
        // 界面可能刚切换过来，趁热再扫一次，别等下一次自然轮询。
        if (r.looksLikeRinging()) {
            long now = SystemClock.elapsedRealtime();
            if (now - mLastScanAt >= SCAN_INTERVAL_MS) scanNow(true);
        }
    }

    /**
     * 把"这次亲眼看到的接听键位置"记下来，下次不用再猜。
     *
     * <p>只在**同时看到红绿两个钮**（几乎不可能是误判）时才写。
     * 用户手动校准过的位置只有在"和看到的差了 5% 屏宽以上"时才覆盖 ——
     * 那种情况说明微信改版或换了一类来电界面，存档值已经不准了。
     */
    private void rememberAnswerPoint(ScreenLook.Result r) {
        if (r == null || !r.looksLikeRinging()) return;
        Context app = sAppCtx != null ? sAppCtx : getApplicationContext();
        if (app == null) return;
        int w = r.screenW, h = r.screenH;
        if (w <= 0 || h <= 0) return;
        float xr = r.green.cx / (float) w;
        float br = (h - r.green.cy) / (float) h;
        float rr = r.green.r / (float) w;
        if (xr <= 0f || xr >= 1f || br <= 0f || br >= 1f || rr <= 0f) return;

        int[] old = AnswerPointPrefs.point(app, w, h);
        int far = Math.max(Math.abs(old[0] - r.green.cx), Math.abs(old[1] - r.green.cy));
        boolean custom = AnswerPointPrefs.isCustomized(app);
        if (!custom || far > w * 0.05f) {
            AnswerPointPrefs.save(app, xr, br, rr);
            CallDiag.log("看图", "已把这次看到的圆钮位置记为校准值：横向 "
                    + pctStr(xr) + "，距底部 " + pctStr(br)
                    + "（原来按 " + (custom ? "你自己调的值" : "内置默认值") + " 是 ("
                    + old[0] + "," + old[1] + ")，差了 " + far + "px）");
        } else {
            CallDiag.log("看图", "看到的圆钮与你校准过的位置只差 " + far + "px → 不动存档值");
        }
    }

    private static String pctStr(float v) {
        return Math.round(v * 1000) / 10f + "%";
    }

    /** 缓存还热乎的截图结果（超过 {@link #LOOK_FRESH_MS} 就不算数了） */
    private ScreenLook.Result freshLook() {
        ScreenLook.Result r = mLook;
        if (r == null) return null;
        if (SystemClock.elapsedRealtime() - mLookAt > LOOK_FRESH_MS) return null;
        return r;
    }

    /**
     * 最近一次截图里看到的绿色接听钮中心。拿不到就返回 null（调用方退回原来的比例坐标）。
     *
     * @return {中心X, 中心Y, 半径}，物理屏坐标
     */
    public int[] seenAnswerCenter() {
        ScreenLook.Result r = freshLook();
        if (r == null || r.green == null) return null;
        return new int[]{r.green.cx, r.green.cy, r.green.r};
    }

    /**
     * 截图能不能证明"此刻微信真的在响铃"。
     *
     * <p>注意**故意不提供**反向的"没看到 → 不在响"判定：截图可能失败、可能被系统
     * 限流、微信也可能哪天把按钮换个颜色。本项目铁律是"没有证据不得做否定结论"
     * （见 §4.1 / trap #18），所以这里只认正面证据。
     */
    public boolean lookProvesRinging() {
        ScreenLook.Result r = freshLook();
        return r != null && r.looksLikeRinging();
    }

    /** 是否处于「正在响铃的来电」界面 */
    public boolean isRinging() {
        AccessibilityNodeInfo root = wechatWindowRoot();
        return root != null && phaseOf(root) == UiPhase.RINGING;
    }

    private boolean isRinging(AccessibilityNodeInfo root) {
        return phaseOf(root) == UiPhase.RINGING;
    }

    /** 是否已经接通（通话中界面） */
    public boolean isInCall() {
        AccessibilityNodeInfo root = wechatWindowRoot();
        return root != null && phaseOf(root) == UiPhase.IN_CALL;
    }

    private boolean isInCall(AccessibilityNodeInfo root) {
        return phaseOf(root) == UiPhase.IN_CALL;
    }

    /** 是否符合通话时长的写法：00:35 / 1:02:33 */
    private static boolean isCallDuration(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        if (s.length() < 4 || s.length() > 9) return false;
        return s.matches("\\d{1,2}:[0-5]\\d(:[0-5]\\d)?");
    }

    // ---------------- 接听 ----------------

    /**
     * 尝试按下微信的接听键。
     *
     * 前提：**必须是全屏来电界面**。三种形态（全屏 / 通知栏 / 顶部横幅）里只有全屏
     * 有接听键；其余形态直接返回 {@link #RESULT_NOT_RINGING}，由上层先把界面拉起来
     * （见 CallSessionManager.ensureFullScreenThenAnswer），绝不盲点。
     *
     * 四级定位，前一级失败才用下一级：
     *   1) 语义：节点里有 text/contentDescription 命中「接听 / Answer」
     *   2) 几何：屏幕右下方那个可点击的圆形按钮（微信接听键是纯图标）
     *   3) 镜像：只找到左下角的挂断键时，按左右对称推出接听键
     *   4) 兜底：按**微信窗口**比例盲点一次
     *
     * 返回值区分「精确」与「盲点」，因为盲点无法确认，调用方不应重复盲点
     * ——重复点右下角，在已经接通的情况下有碰到挂断键的风险。
     */
    public int answerCall() {
        return answerCall(true);
    }

    /**
     * @param allowBlind 是否允许「按坐标盲点」这一最后手段。调用方在一次来电里
     *                   只应允许盲点一次：盲点无法确认到底点中了什么，反复盲点
     *                   在已经接通的情况下有碰到挂断键的风险。
     */
    public int answerCall(boolean allowBlind) {
        // 【v1.22】每一轮都顺便刷新一次"看到的接听键位置"。
        // 这次点击用的是上一轮截到的结果，点完之后新一轮自然会有更接近真实的那一个点。
        requestLook();
        WeChatWin win = findWeChatWindow();
        AccessibilityNodeInfo root = win != null ? win.root : null;
        if (root == null) {
            // 【v1.18 关键修正】之前这里直接 return RESULT_NOT_WECHAT，
            // 造成"读不到节点就永远不点"。详见下面的说明。
            //
            // 微信的通话界面是**整页自绘**的，无障碍经常一个节点都读不到；
            // 我们自己画的指引圈浮在它上面，还会进一步干扰"活动窗口"的判定。
            // 用户实测日志里，全屏来电页明明就在眼前，却反复写
            // 「拿不到微信界面 → 不盲点」和「微信在前台=false」，于是一整通电话
            // 都不肯点一下，最后靠用户手动接听 —— 这就是"半自动"的真正原因。
            //
            // 现在的策略：只要微信确实在前台（或无障碍看到的就是通话界面），
            // 就算读不到节点，也按**用户校准过的物理比例坐标**点一次。
            // 这个坐标是用户对着真实来电界面亲手校准的，此刻屏幕右下角就是接听键，
            // 点在那里是安全的。
            boolean weChatFront = isWeChatForeground();
            if (weChatFront) {
                // 【v1.20 修复】原实现在这里无视 allowBlind 直接盲点。
                // WeChatClicker 有"整通来电只准盲点一次"的闸门（sBlindUsedThisCall），
                // 而这一支恰好是本机最常走的路（微信整页自绘、节点为零），
                // 于是闸门形同虚设：每一轮都往同一个坐标戳，一旦中途接上了，
                // 后续几下就有戳到左边挂断键的风险。现在必须先过闸门。
                if (!allowBlind) {
                    CallDiag.log("接听", "仍是整页自绘界面，但本通来电的坐标兜底已用过一次"
                            + " → 这一轮不再戳坐标，避免误碰挂断键");
                    return RESULT_NO_WINDOW;
                }
                CallDiag.log("接听", "拿不到微信节点（整页自绘），但微信确在前台"
                        + " → 按" + (seenAnswerCenter() != null ? "截图看到的圆心" : "校准坐标")
                        + "执行一次手势点击（不再因为读不到节点就放弃）");
                // 走的是坐标 → 如实报 RESULT_CLICKED_BLIND，让上层计入盲点配额
                if (tapAnswerPreferred()) return RESULT_CLICKED_BLIND;
                CallDiag.log("接听", "手势点击下发失败");
                return RESULT_NO_WINDOW;
            }
            CallDiag.log("接听", "微信不在前台（屏幕上可能是通知栏/横幅/桌面）"
                    + "→ 不盲点，先要求把全屏来电界面拉出来");
            return RESULT_NOT_WECHAT;
        }
        if (isInCall(root)) {
            CallDiag.log("接听", "已经在通话中，无需再接");
            return RESULT_CLICKED_PRECISE; // 上层会用 isInCall 再次确认
        }
        if (!isFullScreenCallUi()) {
            // 【v1.12 关键放行】"判定不是全屏来电界面"有两种截然不同的情况，必须分开：
            //
            //  A. 界面上**读得到内容**，且内容明确表明不是来电页
            //     （例如只显示了聊天列表、或只有顶部横幅）→ 真的没有接听键，绝不能点。
            //
            //  B. 界面上**读不到任何内容**（canReadUiText=false），微信窗口却铺满整屏。
            //     这正是微信全屏来电界面的典型样子（整页自绘，无障碍一个节点都没有）。
            //     旧版本在这里一律当成 A 拒绝，于是永远返回"不是全屏来电界面"，
            //     上层反复"拉起"也拉不出个所以然，最终整通电话都没自动接听
            //     —— 用户实测反馈的正是这个现象。
            //     这种情况必须放行：屏幕右下角就是接听键，按几何/比例去点。
            boolean readable = canReadUiText(root);
            boolean windowFull = isWindowFullScreen(win);
            if (readable || !windowFull) {
                CharSequence pkg = root.getPackageName();
                CallDiag.log("接听", "当前不是全屏来电界面（前台包名="
                        + (pkg == null ? "未知" : pkg) + "）→ 不点击，先要求拉起全屏界面。"
                        + "读到的文字=" + (readable ? "有" : "无")
                        + " 窗口=" + win.bounds.toShortString());
                return RESULT_NOT_RINGING;
            }
            CallDiag.log("接听", "微信窗口铺满整屏且读不到任何节点（整页自绘，正是全屏来电界面的特征）"
                    + " → 按几何/比例尝试点击接听键。窗口=" + win.bounds.toShortString());
        }

        // 【v1.15】真正点击一律走「物理屏比例坐标 + 真实手势」。
        // 原因（用户实机日志定位出的两个根因）：
        //   · 微信 8.0.78 的接听键对无障碍 ACTION_CLICK 无响应——ACTION_CLICK 返回成功、
        //     日志写"已点击(精确定位)"，呼叫却一直在响铃。只有真实手势点屏幕坐标才生效。
        //   · 节点的 getBoundsInScreen() 在部分 ROM 下坐标空间失真（窗口曾出现
        //     [987,136,2085,2576] 这种右边缘远超物理屏宽的情况），用节点中心去点会点到屏幕外。
        // 因此：语义/几何/镜像只用来"确认这是来电接听界面 + 打日志"，
        // 实际点击统一用 answerPointInternal 算出的物理比例坐标（已在本机截图验证准确）。
        // 【v1.22 新增第 0 级】截图亲眼看到的圆心优先于一切估算。
        // 前面四级（语义/几何/镜像/比例）都是在微信读不到节点的现实下对位置的**估算**，
        // 而这个点是刚才那张真实截图上绿钮的位置，原则上不可能比估算更差。
        int[] seen = seenAnswerCenter();
        if (seen != null) {
            CallDiag.log("接听", "按截图里那个绿色圆钮的中心点按 (" + seen[0] + "," + seen[1]
                    + ") 半径=" + seen[2] + "（不再用比例估算）");
            if (tapScreen(seen[0], seen[1])) return RESULT_CLICKED_PRECISE;
        }

        AccessibilityNodeInfo node = findAnswerNode(root);
        AccessibilityNodeInfo geo = findAnswerByGeometry(root);
        if (node != null) {
            CallDiag.log("接听", "按文字/描述命中接听键，改用物理比例坐标点按（不再用 ACTION_CLICK）");
        } else if (geo != null) {
            CallDiag.log("接听", "按右下角圆形按钮定位接听键，改用物理比例坐标点按");
        } else {
            CallDiag.log("接听", "未直接认出接听键文字/按钮，仍按物理比例坐标点按（微信整页自绘时常用）");
        }
        if (tapAnswerByRatio()) {
            return RESULT_CLICKED_PRECISE;
        }

        // 2b) 镜像：只找到左下角的挂断键时，接听键就在同一行的对称位置
        float[] mirror = mirrorOfDecline(root);
        if (mirror != null && tapScreen(mirror[0], mirror[1])) {
            CallDiag.log("接听", "只找到左下角的挂断键，按左右对称推算接听键并点击 ("
                    + (int) mirror[0] + "," + (int) mirror[1] + ")");
            return RESULT_CLICKED_PRECISE;
        }

        // 3) 兜底
        if (!allowBlind) {
            CallDiag.log("接听", "文字与按钮都定位不到，且本次已用过坐标兜底，不再重复盲点");
            return RESULT_NO_WINDOW;
        }
        boolean ok = tapAnswerPreferred();
        CallDiag.log("接听", "坐标兜底点按 -> " + ok);
        return ok ? RESULT_CLICKED_BLIND : RESULT_NO_WINDOW;
    }

    /**
     * 退一步找左下角的「挂断」键：微信来电界面上接听/挂断是同一行左右对称的两个圆钮，
     * 知道其中任意一个的位置，就能推算出另一个（x 关于屏幕中线做镜像）。
     */
    private float[] mirrorOfDecline(AccessibilityNodeInfo root) {
        Rect base = baseRect();
        int w = base.width(), h = base.height();
        if (w <= 0 || h <= 0) return null;
        int minSide = Math.round(40 * getResources().getDisplayMetrics().density);

        AccessibilityNodeInfo best = null;
        int bestY = Integer.MIN_VALUE;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < 400) {
            AccessibilityNodeInfo n = stack.pop();
            visited++;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (isClickableish(n) && r.width() >= minSide && r.height() >= minSide) {
                float cx = r.exactCenterX(), cy = r.exactCenterY();
                boolean leftHalf = cx < base.left + w * 0.45f;
                boolean bottomArea = cy > base.top + h * 0.55f;
                boolean roundish = r.height() != 0
                        && (float) r.width() / r.height() > 0.6f
                        && (float) r.width() / r.height() < 1.7f;
                if (leftHalf && bottomArea && roundish && cy > bestY) {
                    bestY = (int) cy;
                    best = n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
        if (best == null) return null;
        Rect r = new Rect();
        best.getBoundsInScreen(r);
        // 左右镜像：接听键中心 x = 基准区左边界 + 右边界 − 挂断键 x
        return new float[]{base.left + (base.right - r.exactCenterX()), r.exactCenterY()};
    }

    /**
     * 定位基准区域：优先微信窗口（自动排除导航栏），拿不到就用「整屏减去导航栏」。
     * 三键导航的手机上，用整屏当基准会让"右半边 / 下半边"的判断整体偏移。
     */
    private Rect baseRect() {
        int[] s = screenSize();
        Rect base = new Rect(0, 0, s[0], Math.max(0, s[1] - navigationBarHeight()));
        WeChatWin win = findWeChatWindow();
        if (win != null && win.bounds.width() > 0 && win.bounds.height() > 0) {
            base.set(win.bounds);
        }
        return base;
    }

    private AccessibilityNodeInfo findAnswerNode(AccessibilityNodeInfo root) {
        for (String k : ANSWER_KEYS) {
            AccessibilityNodeInfo n = findNode(root, k, false);
            if (n != null) return n;
        }
        return null;
    }

    /**
     * 找不到任何文字时，靠位置找接听键：
     * 在所有可点击（或本身就是按钮）的节点里，挑出位于屏幕右下角、形状接近圆形的那个。
     * 微信来电界面上，右下角只有接听键一个这样的按钮（挂断在左边）。
     */
    private AccessibilityNodeInfo findAnswerByGeometry(AccessibilityNodeInfo root) {
        Rect base = baseRect();
        int w = base.width(), h = base.height();
        if (w <= 0 || h <= 0) return null;
        int minSide = Math.round(40 * getResources().getDisplayMetrics().density);

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < 400) {
            AccessibilityNodeInfo n = stack.pop();
            visited++;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            boolean clickable = isClickableish(n);
            if (clickable && r.width() >= minSide && r.height() >= minSide) {
                float cx = r.exactCenterX(), cy = r.exactCenterY();
                boolean rightHalf = cx > base.left + w * 0.55f;
                boolean bottomArea = cy > base.top + h * 0.55f;
                boolean roundish = r.height() != 0
                        && (float) r.width() / r.height() > 0.6f
                        && (float) r.width() / r.height() < 1.7f;
                if (rightHalf && bottomArea && roundish) {
                    // 越靠右下越可能是接听键
                    int score = (int) (cx - base.exactCenterX()) + (int) (cy - base.exactCenterY());
                    if (score > bestScore) {
                        bestScore = score;
                        best = n;
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
        return best;
    }

    private boolean isClickableish(AccessibilityNodeInfo n) {
        if (n.isClickable()) return true;
        // 有些按钮的可点击性挂在父节点上，但这种情况下我们会向上找，
        // 这里只把「自身可点击」或「有描述的图片按钮」当作候选。
        CharSequence d = n.getContentDescription();
        return d != null && d.length() > 0;
    }

    /**
     * 兜底：按**微信窗口**的比例盲点接听键位置（80.3% 宽 / 距窗口底部 11.4% 高）。
     * 若当前界面能读到「摄像头已开 / 模糊背景 / 翻转」这类按钮，就用它们的横坐标
     * 校准——这些按钮与接听键是同一竖列，比固定比例更准。
     */
    private boolean tapAnswerByRatio() {
        WeChatWin win = findWeChatWindow();
        int[] size = screenSize();
        if (size[0] <= 0 || size[1] <= 0) return false;
        int[] p = answerPointInternal(win, size, navigationBarHeight());
        return tapScreen(p[0], p[1]);
    }

    /**
     * 点接听键的首选入口：截图看到了就点看到的圆心，看不到才退回比例估算。
     *
     * <p>凡是原来直接调 {@link #tapAnswerByRatio()} 的地方都应该换成这里 ——
     * 保证"看得见时永远优先相信眼睛"。
     */
    private boolean tapAnswerPreferred() {
        int[] seen = seenAnswerCenter();
        if (seen != null) {
            CallDiag.log("接听", "按截图里那个绿色圆钮的中心点按 (" + seen[0] + "," + seen[1]
                    + ") 半径=" + seen[2] + "（不再用比例估算）");
            return tapScreen(seen[0], seen[1]);
        }
        return tapAnswerByRatio();
    }

    /**
     * 计算接听键中心点（屏幕坐标），返回 {x, y, 半径}。
     *
     * 【坐标系基准：微信窗口，而不是整个物理屏幕】—— 这是用户实测反馈后修正的。
     *
     * 用户的原话：「有些手机底部是有虚拟按键的…你是不是需要在 y 轴上把虚拟按键的
     * 高度去掉再定位」。对的，而且这是**必然点空**的原因：
     * 微信的按钮摆在**自己的窗口**里。手势导航时窗口铺满整屏，实测
     * （1220×2712 截图）接听键中心在距屏幕底部 11.4% 屏高处；
     * 但三键导航时窗口底部比物理屏幕底部高出一个导航栏（约 48dp / 144px），
     * 仍然按整屏算就会偏低 128px —— 而按钮半径只有 109px，于是每一下都点进导航栏。
     *
     * 所以基准改成：优先微信窗口的实际区域（三键导航时它自动不含导航栏），
     * 拿不到窗口时用「屏幕高度 − 导航栏高度」。
     *
     * 坐标系约定（用户的思路）：以**基准区域左下角为原点**，
     * 接听键中心在水平方向占 80.3% 宽、垂直方向距底部 11.4% 高。
     *
     * 供屏幕指引浮层（GuideOverlay）与自动点击共用，保证"圈出来的位置"
     * 与"实际点的位置"永远是同一个点。
     */
    public static int[] answerPoint(Context ctx) {
        if (sAppCtx == null && ctx != null) sAppCtx = ctx.getApplicationContext();
        CallHelperAccessibilityService svc = sInstance;
        WeChatWin win = svc != null ? svc.findWeChatWindow() : null;
        int[] size = svc != null ? svc.screenSize() : screenSizeFrom(ctx);
        return answerPointInternal(win, size, svc != null ? svc.navigationBarHeight() : 0);
    }

    private static int[] answerPointInternal(WeChatWin win, int[] size, int navBarHeight) {
        int w = size[0], h = size[1];
        if (w <= 0 || h <= 0) return new int[]{0, 0, 0};

        // 【v1.16 新增：用户自己校准过的位置优先】
        // 不同机型/微信版本/导航方式下这个绿钮的位置都不一样，默认比例不可能每台都准。
        // 用户在「设置 → 校准接听键位置」里亲手拖过之后，这里无条件用他指定的位置：
        // 圈画在这里，自动点击也点在这里，两者永远是同一个点。
        Context ctx0 = sAppCtx;
        if (ctx0 == null) {
            CallHelperAccessibilityService svc0 = sInstance;
            if (svc0 != null) ctx0 = svc0.getApplicationContext();
        }
        if (ctx0 != null && AnswerPointPrefs.isCustomized(ctx0)) {
            int[] up = AnswerPointPrefs.point(ctx0, w, h);
            CallDiag.log("接听", "接听键位置=用户校准值（设置页手动调过）→ 中心=("
                    + up[0] + "," + up[1] + ") 半径=" + up[2]
                    + "；屏幕=" + w + "x" + h + "（不再做自动微调，避免又把点挪走）");
            return up;
        }

        // 【v1.15 核心修复：坐标必须落在物理屏幕上】
        // 旧逻辑把"微信窗口"当成基准区，乘以比例算坐标。
        // 但微信窗口在通知刚拉起、或个别 ROM 下，getBoundsInScreen() 返回的
        // 是一个**缩放/平移过的坐标空间**（实测见过 [987,136,2085,2576]，
        // 右边缘 2085 远超物理屏宽 1220）。用它算出来的中心点 (1869,2298)
        // 直接跑到屏幕外面，于是：屏幕指引的圈画在屏幕外（看不见），
        // 自动接听的点击也落在屏幕外（点了个寂寞，呼叫却一直在响）。
        // 这就是"有圈的信息却看不到圈、点了也接不通"的根因之一。
        //
        // 修法：比例只作用在**物理屏幕尺寸**上，永远不碰那个可能失真的窗口坐标。
        // 实测 1220×2712 截图里接听键中心 = 屏宽 80.3%、距屏底 11.4%，
        // 这个比例是相对物理屏的，跨机型都成立。窗口只用来"判断是否全屏"，
        // 不再参与坐标计算。

        // 【v1.21 修复】纵坐标必须以**微信实际能用的高度**为基准，不能直接用物理屏高。
        //
        // 11.4% 这个比例是在 1220×2712、**手势导航（没有导航栏）**的机器上量出来的，
        // 那时可用高度恰好等于物理屏高 2712，所以"用 h 还是用 h-navBar"看不出差别。
        // 但换成三键导航的机器就不一样了：导航栏会占掉底部约 130px，
        // 微信内容区只有 2582px 高，它的接听键会跟着上移，
        // 而我们仍按 2712 去算 → y 偏大约 115px，正好点进导航栏里。
        // 按钮半径才 135px，偏 115px 等于必然点空 ——
        // 表现为"已经有圈、也点了，但就是接不通"，而且只在部分机型上出现。
        //
        // navBarHeight 这个参数以前传进来却从来没参与过 y 的计算，现在补上。
        // 手势导航时它为 0，结果与旧行为完全一致，不存在回归风险。
        int usableH = h - (navBarHeight > 0 ? navBarHeight : 0);
        if (usableH <= 0) usableH = h;

        float x = w * ANSWER_X_RATIO;
        float y = usableH - usableH * ANSWER_BOTTOM_RATIO;   // 距可用区底部 11.4%
        int r = Math.round(w * ANSWER_RADIUS_RATIO);

        StringBuilder cal = new StringBuilder();
        cal.append("接听键基准=物理屏幕(").append(w).append("x").append(h).append(")")
                .append(" 导航栏=").append(navBarHeight)
                .append(" → 参与计算的高=").append(usableH)
                .append(" → 中心=(").append(Math.round(x)).append(",").append(Math.round(y))
                .append(") 半径=").append(r);

        // 只在"微信窗口确实铺满整屏、且坐标也在屏幕内"时，才用同列按钮做≤2% 屏宽的微调；
        // 一旦窗口坐标越界（说明坐标空间失真），直接跳过微调，避免把点带飞到屏幕外。
        if (win != null && win.root != null
                && win.bounds.width() > w * 0.9f && win.bounds.left >= -2 && win.bounds.right <= w + 2
                && win.bounds.height() > h * 0.9f && win.bounds.top >= -2 && win.bounds.bottom <= h + 2) {
            for (String k : X_ANCHOR_KEYS) {
                AccessibilityNodeInfo n = findNodeStatic(win.root, k);
                if (n == null) continue;
                Rect rect = new Rect();
                n.getBoundsInScreen(rect);
                if (rect.width() <= 0) continue;
                float cx = rect.exactCenterX();
                if (cx <= w * 0.5f) continue;         // 只看右半屏的按钮
                float diff = Math.abs(cx - x);
                if (diff <= w * X_ANCHOR_TOLERANCE) {
                    x = cx;
                    cal.append("；并用「").append(k).append("」微调横坐标（差 ")
                            .append(Math.round(diff)).append("px）→ x=").append(Math.round(x));
                }
                break;
            }
        } else if (win != null) {
            cal.append("；窗口坐标疑似失真(区域=[")
                    .append(win.bounds.left).append(",").append(win.bounds.top)
                    .append(",").append(win.bounds.right).append(",").append(win.bounds.bottom)
                    .append("])，跳过窗口微调，只用物理屏比例");
        }
        CallDiag.log("接听", cal.toString());
        return new int[]{Math.round(x), Math.round(y), r};
    }

    /** 和 WeChatClicker 等静态入口共用的屏幕基准，直接委托 {@link Screen}。 */
    private static int[] screenSizeFrom(Context ctx) {
        return Screen.realSize(ctx);
    }

    private static AccessibilityNodeInfo findNodeStatic(AccessibilityNodeInfo root, String key) {
        if (root == null) return null;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < 600) {
            AccessibilityNodeInfo n = stack.pop();
            visited++;
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            if ((t != null && t.toString().contains(key))
                    || (d != null && d.toString().contains(key))) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
        return null;
    }

    /** 当前微信界面里是否读得到任何文字/描述（用来判断「读不到」还是「真的没接通」） */
    public boolean canReadUiText() {
        return canReadUiText(wechatWindowRoot());
    }

    public boolean canReadUiText(AccessibilityNodeInfo root) {
        if (root == null) return false;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < 300) {
            AccessibilityNodeInfo n = stack.pop();
            visited++;
            if (hasText(n.getText()) || hasText(n.getContentDescription())) return true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
        return false;
    }

    private boolean hasText(CharSequence cs) {
        return cs != null && cs.toString().trim().length() > 0;
    }

    /** 物理屏尺寸（含系统栏）。统一走 {@link Screen}，避免多处实现基准不一致。 */
    private int[] screenSize() {
        return Screen.realSize(this);
    }

    /**
     * 点击节点。
     *
     * 【v1.15 关键修复】原来先尝试无障碍 ACTION_CLICK，不行再改手势点节点中心。
     * 但**微信 8.0.78 的接听键对 ACTION_CLICK 无响应**——performAction 返回 true、
     * 看起来"点成功了"，呼叫却一直在响铃（运行记录里就是"已点击(精确定位) 但仍在响铃"）。
     * 所以现在**优先用真实手势点屏幕坐标**（微信的按钮只认 onTouch，手势能命中），
     * 只有手势 API 不可用（低于 Android 7）时才退回 ACTION_CLICK。
     *
     * 坐标取节点在屏幕上的中心；如果节点坐标落在屏幕外（微信窗口坐标空间失真时常见），
     * 退化到"按物理屏比例算出的接听键位置"——那个坐标在真机上已验证是准的。
     */
    public boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        int[] sc = screenSize();
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        boolean onScreen = r.width() > 0 && r.height() > 0
                && r.exactCenterX() >= 0 && r.exactCenterX() <= sc[0]
                && r.exactCenterY() >= 0 && r.exactCenterY() <= sc[1];
        if (onScreen) {
            // 真机实测：微信的接听键比节点 bounds 略小，中心基本对得上，直接点中心即可
            return tapScreen(r.exactCenterX(), r.exactCenterY());
        }
        // 节点坐标失真 → 退回到物理屏比例坐标（已验证准确）
        int[] p = answerPointInternal(findWeChatWindow(), sc, navigationBarHeight());
        if (p[0] > 0 && p[1] > 0) return tapScreen(p[0], p[1]);
        // 手势都不可用（极老的系统）：最后再试一次 ACTION_CLICK
        return actionClickNode(node);
    }

    /** 无障碍 ACTION_CLICK 兜底（仅当手势不可用或坐标全部失真时） */
    private boolean actionClickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        int hops = 0;
        while (cur != null && hops < 6) {
            if (cur.isClickable()) {
                try {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                } catch (Exception ignore) {}
            }
            cur = cur.getParent();
            hops++;
        }
        return false;
    }

    /** 兼容旧调用：按文字点击（内部已改为通用实现） */
    public boolean clickNodeWithText(String label) {
        AccessibilityNodeInfo root = wechatWindowRoot();
        if (root == null) return false;
        return clickNode(findNode(root, label, false));
    }

    /**
     * 微信是不是现在正显示在屏幕上。
     *
     * 【v1.18 修正】旧实现只问一句"能不能拿到微信窗口内容就返回"，这太脆弱了：
     * 微信的通话页是**整页自绘**的，无障碍经常读不到任何节点；
     * 再加上悬浮窗浮在上面时，活动窗口可能被判成"不是微信"。
     * 结果日志里反复出现「微信在前台=false」，上层据此判定"点不了"，
     * 明明全屏来电页就在眼前却不肯点 —— 用户实测反馈的正是这个。
     *
     * 现在三级判定，任一成立就算在前台：
     *   ① 无障碍能拿到微信窗口（最直接）
     *   ② 最近的窗口变化事件来自微信包名（getWindows 里能看到微信窗口也算）
     *   ③ 活动窗口包名兜底
     */
    public boolean isWeChatForeground() {
        // ① 已经在 150ms 缓存里的微信窗口，直接复用；注意别回收它，上面还要接着用
        if (wechatWindowRoot() != null) return true;
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    if (w == null) continue;
                    boolean hit;
                    AccessibilityNodeInfo r = null;
                    try {
                        r = w.getRoot();
                        CharSequence pkg = r == null ? null : r.getPackageName();
                        hit = pkg != null && WECHAT_PKG.equals(pkg.toString());
                    } finally {
                        recycleQuietly(r);
                        recycleQuietly(w);
                    }
                    if (hit) return true;
                }
            }
        } catch (Exception ignore) {}
        try {
            AccessibilityNodeInfo a = getRootInActiveWindow();
            if (a != null) {
                try {
                    CharSequence pkg = a.getPackageName();
                    if (pkg != null && WECHAT_PKG.equals(pkg.toString())) return true;
                } finally {
                    recycleQuietly(a);
                }
            }
        } catch (Exception ignore) {}
        return false;
    }

    /** 对外暴露的"在屏幕某点按一下"（用于点通知横幅等） */
    public boolean tapAt(float x, float y) {
        return tapScreen(x, y);
    }

    private boolean tapScreen(float x, float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder gb = new GestureDescription.Builder();
            gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 70));
            boolean ok = dispatchGesture(gb.build(), null, null);
            // 点了屏幕，界面随时会变：清掉窗口缓存，别让紧接着的判断读到点击前的旧树
            dropWinCache();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前窗口是否属于微信（防止误操作别的应用的界面） */
    private boolean isWeChatWindow(AccessibilityNodeInfo root) {
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        return pkg != null && WECHAT_PKG.equals(pkg.toString());
    }

    // ---------------- 节点查找 ----------------

    /** 先精确匹配，再包含匹配 */
    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, String key, boolean exactOnly) {
        if (root == null) return null;
        AccessibilityNodeInfo r = findNodeInternal(root, key, true);
        if (r == null && !exactOnly) {
            r = findNodeInternal(root, key, false);
        }
        return r;
    }

    private AccessibilityNodeInfo findNodeInternal(AccessibilityNodeInfo root, String key, boolean exact) {
        if (root == null) return null;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < 600) {
            AccessibilityNodeInfo n = stack.pop();
            visited++;
            if (matches(n.getText(), key, exact) || matches(n.getContentDescription(), key, exact)) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
        return null;
    }

    private boolean matches(CharSequence cs, String key, boolean exact) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        return exact ? s.equals(key) : s.contains(key);
    }

    private String bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return "[" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]";
    }

    /**
     * 把当前界面结构写进运行记录（限深度与条数，避免日志爆炸）。
     * 只在来电时记录一次，用于以后微信改版时定位问题。
     */
    private void dumpTreeForDiag(AccessibilityNodeInfo root) {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastTreeDumpAt < 20_000L) return;
        mLastTreeDumpAt = now;
        try {
            StringBuilder sb = new StringBuilder();
            int[] count = new int[]{0};
            collect(root, 0, sb, count);
            CallDiag.log("界面结构", sb.length() == 0
                    ? "微信来电界面对无障碍暴露的节点为空（完全自绘），只能靠坐标点击"
                    : sb.toString());
        } catch (Exception ignore) {}
    }

    private void collect(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || depth > 6 || count[0] > 60) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        if ((t != null && t.length() > 0) || (d != null && d.length() > 0)) {
            count[0]++;
            sb.append(count[0] == 1 ? "" : " | ")
                    .append(depth).append(':')
                    .append(t != null && t.length() > 0 ? t : ("desc=" + d));
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), depth + 1, sb, count);
        }
    }

    // ---------------- 来电人姓名猜测（仅辅助，通知里拿到的名字优先） ----------------

    private String guessCallerName(AccessibilityNodeInfo root) {
        String best = null;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < 400) {
            AccessibilityNodeInfo n = stack.pop();
            visited++;
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String s = null;
            if (t != null && t.length() > 0) {
                s = t.toString().trim();
            } else if (d != null && d.length() > 0) {
                s = d.toString().trim();
            }
            if (s != null && s.length() >= 1 && s.length() <= 16 && !isKeyword(s)) {
                if (best == null || s.length() > best.length()) best = s;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
        return best != null ? best : "微信联系人";
    }

    private boolean isKeyword(String s) {
        return s.contains("接听") || s.contains("挂断") || s.contains("微信") || s.contains("通话")
                || s.contains("邀请") || s.contains("静音") || s.contains("免提") || s.contains("切换")
                || s.contains("取消") || s.contains("稍后") || s.contains("拒绝") || s.contains("视频")
                || s.contains("语音") || s.contains("对方") || s.contains("留言") || s.contains("提醒")
                || s.contains("消息") || s.contains("秒") || s.contains("分钟") || s.contains("…")
                || s.contains("隐藏") || s.contains("翻转") || s.contains("模糊")
                || s.contains("摄像头") || s.contains("背景");
    }
}
