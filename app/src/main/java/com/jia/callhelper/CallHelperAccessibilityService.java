package com.jia.callhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
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

    /** 微信来电界面的文案特征（实测：视频来电只有「邀请你视频通话」） */
    /**
     * 来电界面的特征词。
     * 注意**不能放「挂断」**：通话中的界面也有挂断键，放进去会把"已接通"误判成"正在响铃"
     * （v1.16 之前正是因此出现「接听后还在提示」）。判断"是不是响铃中"只看
     * 「邀请你…」和「接听」这两个只在响铃阶段存在的证据。
     */
    private static final String[] RINGING_KEYS = {"邀请你", "邀请对方", "接听"};
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
    private static final float ANSWER_X_RATIO = 0.803f;
    /** 接听键中心距屏幕底部的比例（原点取左下角，见 answerPoint） */
    private static final float ANSWER_BOTTOM_RATIO = 0.114f;
    /** 接听键半径占屏幕宽度的比例（屏幕指引画圈时用） */
    public static final float ANSWER_RADIUS_RATIO = 0.0885f;
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
    private static final long SCAN_INTERVAL_MS = 1200;
    private volatile long mLastScanAt = 0;
    private volatile long mLastTreeDumpAt = 0;

    public static CallHelperAccessibilityService get() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        CallDiag.init(this);
        CallDiag.log("无障碍", "服务已连接");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        CallDiag.log("无障碍", "服务已断开（系统关闭了无障碍开关？）");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !WECHAT_PKG.equals(pkg.toString())) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scanNow(true);
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

    /**
     * 下拉通知栏（无障碍全局动作）。
     *
     * 用途：来电时屏幕上只显示一条通知（用户图二那种情况），
     * 下拉通知栏能让这条来电通知进入可交互状态，是"把微信通话页拉起来"的一条辅助路径。
     * 成功后返回 true；系统不支持时返回 false（调用方会继续用其它办法）。
     */
    public boolean openNotificationShade() {
        try {
            return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在所有窗口里找属于微信的那一个，并记下它的屏幕区域。
     *
     * 为什么不直接用 getRootInActiveWindow()：来电时我们会在屏幕最上层显示
     * 「屏幕指引」浮层（GuideOverlay），它是另一个窗口。若只取"最上面的活动窗口"，
     * 有可能拿到我们自己的浮层，于是误判成「当前不在微信」→ 不点击、也判断不出
     * 是否已接通。所以这里改为在所有窗口里找属于微信的那一个，做到"浮层在场也不受影响"。
     */
    private WeChatWin findWeChatWindow() {
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null && isWeChatWindow(active)) {
                WeChatWin w = new WeChatWin();
                w.root = active;
                try { active.getBoundsInScreen(w.bounds); } catch (Exception ignore) {}
                return w;
            }
        } catch (Exception ignore) {}
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo win : wins) {
                    if (win == null) continue;
                    AccessibilityNodeInfo r = win.getRoot();
                    if (r == null || !isWeChatWindow(r)) continue;
                    // 只认**真的显示在屏幕上**的微信窗口。
                    // getWindows() 也可能返回后台窗口，而后台窗口上当然没有接听键，
                    // 拿它来判断"是不是全屏来电界面"会得出完全错误的结论。
                    try {
                        if (!r.isVisibleToUser()) continue;
                    } catch (Exception ignore) {}
                    WeChatWin w = new WeChatWin();
                    w.root = r;
                    try { win.getBoundsInScreen(w.bounds); } catch (Exception ignore) {}
                    if (w.bounds.isEmpty()) {
                        try { r.getBoundsInScreen(w.bounds); } catch (Exception ignore) {}
                    }
                    return w;
                }
            }
        } catch (Exception ignore) {}
        return null;
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
                    if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    int hh = r.height();
                    if (hh <= 0 || hh >= screenH / 4) continue;          // 太高，肯定不是
                    if (r.width() < screenW * 0.9f) continue;            // 没横跨屏幕宽度，不是
                    if (r.bottom < screenH - 2) continue;                // 没贴住屏幕底部，不是
                    if (hh > best) best = hh;
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
        if (w == null || w.root == null) return UI_NONE;
        if (isInCall(w.root)) return UI_IN_CALL;
        if (isFullScreenCallUi()) return UI_RINGING;
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

    /** 扫描当前微信界面，判断处于来电/通话中/已结束哪种状态 */
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
        if (isInCall(root)) {
            CallDiag.log("无障碍", "识别到微信「通话中」界面 → 不再当作新来电（避免接听后重复提醒）");
            CallSessionManager.onWeChatCallAnswered(this);
        } else if (isRinging(root)) {
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

    /** 是否处于「正在响铃的来电」界面 */
    public boolean isRinging() {
        AccessibilityNodeInfo root = wechatWindowRoot();
        return root != null && isRinging(root);
    }

    private boolean isRinging(AccessibilityNodeInfo root) {
        if (root == null) return false;
        // 【v1.16 根因修复】
        // 旧逻辑只要界面上出现「挂断」两个字就判定为来电 —— 但**接通之后**的通话界面
        // 同样挂着「挂断」按钮。于是电话一接通，这里又把它当成"新的来电"，
        // 上层（CallSessionManager.onIncomingViaA11y）立刻重开一次提醒会话，
        // 表现为用户反馈的「可以自动接听了，但是接听后还在提示」。
        // 现在改为三道判定，且**先排除通话中**：
        if (isInCall(root)) {
            // 已经在通话中，绝不能再判成"正在响铃的来电"
            return false;
        }
        // ① 「邀请你视频通话 / 邀请你语音通话」是来电最可靠的特征
        for (String k : RINGING_KEYS) {
            if (findNode(root, k, false) != null) return true;
        }
        // ② 界面上还有「接听」键 → 一定还没接通
        if (findNode(root, "接听", false) != null) return true;
        return false;
    }

    /**
     * 界面上是否显示通话时长（如「00:35」「1:02:33」）。
     *
     * 这是「已经接通」最硬的证据：微信只有真正通话中才会开始计时，
     * 而像「挂断」这种按钮在响铃中和通话中都存在，没法用来区分。
     */
    private boolean hasCallDuration(AccessibilityNodeInfo root) {
        if (root == null) return false;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        int visited = 0;
        while (!stack.isEmpty() && visited < 600) {
            AccessibilityNodeInfo n = stack.pop();
            visited++;
            if (isCallDuration(n.getText()) || isCallDuration(n.getContentDescription())) {
                return true;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.push(c);
            }
        }
        return false;
    }

    /** 是否符合通话时长的写法：00:35 / 1:02:33 */
    private static boolean isCallDuration(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        if (s.length() < 4 || s.length() > 9) return false;
        return s.matches("\\d{1,2}:[0-5]\\d(:[0-5]\\d)?");
    }

    /** 是否已经接通（通话中界面） */
    public boolean isInCall() {
        AccessibilityNodeInfo root = wechatWindowRoot();
        if (root == null) return false;
        return isInCall(root);
    }

    private boolean isInCall(AccessibilityNodeInfo root) {
        if (root == null) return false;
        // 还在响铃的铁证：界面上有「接听」键，或有「邀请你…通话」
        if (findNode(root, "接听", false) != null) return false;
        if (findNode(root, "邀请你", false) != null) return false;
        for (String k : IN_CALL_KEYS) {
            if (findNode(root, k, false) != null) return true;
        }
        // 接通后才会出现的通话计时（00:35）——比「挂断」这类按钮可靠得多
        return hasCallDuration(root);
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
        WeChatWin win = findWeChatWindow();
        AccessibilityNodeInfo root = win != null ? win.root : null;
        if (root == null) {
            // 连微信窗口都没有：屏幕上可能是通知栏/横幅，也可能是别的应用。
            // 这种情况**不做坐标盲点**——盲点等于在别人脸上乱戳，
            // 而且即使戳中也没有接听键。交给上层去把全屏界面拉出来。
            CallDiag.log("接听", "拿不到微信界面（可能只有通知/横幅）→ 不盲点，先要求拉起全屏来电界面");
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
        boolean ok = tapAnswerByRatio();
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
        CallHelperAccessibilityService svc = sInstance;
        WeChatWin win = svc != null ? svc.findWeChatWindow() : null;
        int[] size = svc != null ? svc.screenSize() : screenSizeFrom(ctx);
        return answerPointInternal(win, size, svc != null ? svc.navigationBarHeight() : 0);
    }

    private static int[] answerPointInternal(WeChatWin win, int[] size, int navBarHeight) {
        int w = size[0], h = size[1];
        if (w <= 0 || h <= 0) return new int[]{0, 0, 0};

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

        float x = w * ANSWER_X_RATIO;
        float y = h - h * ANSWER_BOTTOM_RATIO;   // 距屏底 11.4% → 从顶部算
        int r = Math.round(w * ANSWER_RADIUS_RATIO);

        StringBuilder cal = new StringBuilder();
        cal.append("接听键基准=物理屏幕(").append(w).append("x").append(h).append(")")
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

    private static int[] screenSizeFrom(Context ctx) {
        if (ctx == null) return new int[]{0, 0};
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

    private int[] screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return new int[]{0, 0};
            if (Build.VERSION.SDK_INT >= 17) {
                wm.getDefaultDisplay().getRealMetrics(dm);
            } else {
                wm.getDefaultDisplay().getMetrics(dm);
            }
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
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

    /** 当前最上层窗口属于哪个应用（拿不到时返回 null） */
    public String getForegroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            CharSequence p = root.getPackageName();
            if (p != null) return p.toString();
        }
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo win : wins) {
                    if (win == null || !win.isFocused()) continue;
                    AccessibilityNodeInfo r = win.getRoot();
                    if (r != null) {
                        CharSequence p = r.getPackageName();
                        if (p != null) return p.toString();
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * 微信是否在前台。
     * 用"能找到微信窗口"来判断，而不是"最上面的窗口是不是微信"——
     * 因为我们自己会在最上面画屏幕指引浮层（见 GuideOverlay），
     * 用后者会把浮层误当成"微信不在前台"。
     */
    public boolean isWeChatForeground() {
        return wechatWindowRoot() != null;
    }

    private boolean tapScreen(float x, float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder gb = new GestureDescription.Builder();
            gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 70));
            return dispatchGesture(gb.build(), null, null);
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
