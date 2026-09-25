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
 * 现在改成三级定位：语义（文字/描述）→ 几何（屏幕右下角那个可点击圆钮）→
 * 比例坐标兜底（兜底只点一次，避免点歪到挂断）。
 */
public class CallHelperAccessibilityService extends AccessibilityService {

    /** 只认微信。用于事件过滤，也用于「只在微信界面里点击」的防呆校验 */
    private static final String WECHAT_PKG = "com.tencent.mm";

    /** 接听结果 */
    public static final int RESULT_CLICKED_PRECISE = 1; // 精确点到（有节点树可读，可靠）
    public static final int RESULT_CLICKED_BLIND = 2;   // 盲点坐标（不确定，不要再点第二次）
    public static final int RESULT_NOT_WECHAT = 3;      // 微信界面不在前台，需要先把它拉起来
    public static final int RESULT_NO_WINDOW = 4;       // 连界面都拿不到，无法操作

    /** 微信来电界面的文案特征（实测：视频来电只有「邀请你视频通话」） */
    private static final String[] RINGING_KEYS = {"邀请你", "邀请对方", "接听", "挂断"};
    /** 接听键可能的文字（少数版本/语言下存在） */
    private static final String[] ANSWER_KEYS = {"接听", "接听电话", "Answer", "Accept", "answer", "accept"};
    /** 通话已接通的特征（接通后才会出现静音/免提这类按钮） */
    private static final String[] IN_CALL_KEYS = {"静音", "免提", "扬声器", "切换摄像头", "摄像头已关"};

    /**
     * 盲点兜底：微信来电的接听键在屏幕右下角，大致在屏幕宽度 75%、高度 80% 的位置。
     * 水平方向还能进一步校准——视频来电界面上方的「摄像头已开 / 模糊背景」
     * 与底部的绿色接听键在同一竖列，用它们的横坐标替换经验值会准得多（见 tapAnswerByRatio）。
     */
    private static final float ANSWER_X_RATIO = 0.75f;
    private static final float ANSWER_Y_RATIO = 0.80f;
    /** 用于校准接听键横坐标的上方按钮文案 */
    private static final String[] X_ANCHOR_KEYS = {"摄像头已开", "摄像头已关", "模糊背景", "翻转"};

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

    /** 扫描当前微信界面，判断处于来电/通话中/已结束哪种状态 */
    private void scanNow(boolean windowChanged) {
        mLastScanAt = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (windowChanged) CallDiag.log("无障碍", "收到微信窗口变化，但拿不到界面内容（root=null）");
            return;
        }
        if (!isWeChatWindow(root)) return;

        if (isRinging(root)) {
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
        } else if (isInCall(root)) {
            CallSessionManager.onWeChatCallAnswered(this);
        } else if (findNode(root, "通话结束", false) != null
                || findNode(root, "已结束", false) != null) {
            CallSessionManager.onWeChatCallEnded(this, "界面显示通话结束");
        }
    }

    /** 是否处于「正在响铃的来电」界面 */
    public boolean isRinging() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && isWeChatWindow(root) && isRinging(root);
    }

    private boolean isRinging(AccessibilityNodeInfo root) {
        if (root == null) return false;
        // 实测视频来电界面只有「邀请你视频通话」；语音来电同样是「邀请你...」开头。
        // 因此以「邀请」为主特征，配合「通话/接听/挂断」任一即认定为来电。
        boolean invite = findNode(root, "邀请", false) != null;
        if (invite && (findNode(root, "通话", false) != null
                || findNode(root, "接听", false) != null)) {
            return true;
        }
        // 兼容带完整文字的界面
        for (String k : RINGING_KEYS) {
            if (findNode(root, k, false) != null) {
                return findNode(root, "接听", false) != null
                        || findNode(root, "挂断", false) != null;
            }
        }
        return false;
    }

    /** 是否已经接通（通话中界面） */
    public boolean isInCall() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isWeChatWindow(root)) return false;
        return isInCall(root);
    }

    private boolean isInCall(AccessibilityNodeInfo root) {
        if (root == null) return false;
        if (findNode(root, "邀请", false) != null) return false; // 还在响铃
        for (String k : IN_CALL_KEYS) {
            if (findNode(root, k, false) != null) return true;
        }
        return false;
    }

    // ---------------- 接听 ----------------

    /**
     * 尝试按下微信的接听键。
     *
     * 三级定位，前一级失败才用下一级：
     *   1) 语义：节点里有 text/contentDescription 命中「接听 / Answer」
     *   2) 几何：屏幕右下方那个可点击的圆形按钮（微信接听键是纯图标）
     *   3) 兜底：按屏幕比例盲点一次
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (!allowBlind) {
                CallDiag.log("接听", "拿不到微信界面，且本次已用过坐标兜底，不再重复盲点");
                return RESULT_NO_WINDOW;
            }
            boolean ok = tapAnswerByRatio();
            CallDiag.log("接听", "拿不到微信界面（root=null），改用坐标盲点 -> " + ok);
            return ok ? RESULT_CLICKED_BLIND : RESULT_NO_WINDOW;
        }
        if (!isWeChatWindow(root)) {
            CharSequence p = root.getPackageName();
            CallDiag.log("接听", "当前前台不是微信（" + (p == null ? "未知" : p) + "），不点击");
            return RESULT_NOT_WECHAT;
        }

        // 1) 语义
        AccessibilityNodeInfo node = findAnswerNode(root);
        if (node != null && clickNode(node)) {
            CallDiag.log("接听", "按文字/描述命中接听键并已点击");
            return RESULT_CLICKED_PRECISE;
        }

        // 2) 几何
        AccessibilityNodeInfo geo = findAnswerByGeometry(root);
        if (geo != null && clickNode(geo)) {
            CallDiag.log("接听", "按右下角圆形按钮定位接听键并已点击（" + bounds(geo) + "）");
            return RESULT_CLICKED_PRECISE;
        }

        // 3) 兜底
        if (!allowBlind) {
            CallDiag.log("接听", "文字与按钮都定位不到，且本次已用过坐标兜底，不再重复盲点");
            return RESULT_NO_WINDOW;
        }
        boolean ok = tapAnswerByRatio();
        CallDiag.log("接听", "文字与按钮都定位不到，改用坐标盲点 -> " + ok);
        return ok ? RESULT_CLICKED_BLIND : RESULT_NO_WINDOW;
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
        int[] size = screenSize();
        int w = size[0], h = size[1];
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
                boolean rightHalf = cx > w * 0.55f;
                boolean bottomArea = cy > h * 0.55f;
                boolean roundish = r.height() != 0
                        && (float) r.width() / r.height() > 0.6f
                        && (float) r.width() / r.height() < 1.7f;
                if (rightHalf && bottomArea && roundish) {
                    // 越靠右下越可能是接听键
                    int score = (int) (cx - w / 2) + (int) (cy - h / 2);
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
     * 兜底：按屏幕比例盲点接听键位置（右下角）。
     * 若当前界面能读到「摄像头已开 / 模糊背景 / 翻转」这类按钮，就用它们的横坐标
     * 校准——这些按钮与接听键是同一竖列，比固定 75% 准得多。
     */
    private boolean tapAnswerByRatio() {
        int[] size = screenSize();
        if (size[0] <= 0 || size[1] <= 0) return false;
        float x = size[0] * ANSWER_X_RATIO;
        float y = size[1] * ANSWER_Y_RATIO;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && isWeChatWindow(root)) {
            for (String k : X_ANCHOR_KEYS) {
                AccessibilityNodeInfo n = findNode(root, k, false);
                if (n == null) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.width() > 0 && r.exactCenterX() > size[0] * 0.5f) {
                    x = r.exactCenterX();
                    CallDiag.log("接听", "用「" + k + "」校准接听键横坐标 -> x=" + (int) x
                            + "（原按屏幕宽度估算为 " + (int) (size[0] * ANSWER_X_RATIO) + "）");
                    break;
                }
            }
        }
        return tapScreen(x, y);
    }

    /** 当前微信界面里是否读得到任何文字/描述（用来判断「读不到」还是「真的没接通」） */
    public boolean canReadUiText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isWeChatWindow(root)) return false;
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
     * 点击节点：优先用无障碍的 ACTION_CLICK（找自身或最近的可点击祖先），
     * 都不行再模拟手势点节点中心（微信不少按钮是自绘的，没有可点击属性）。
     */
    public boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
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
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            return tapScreen(r.exactCenterX(), r.exactCenterY());
        }
        return false;
    }

    /** 兼容旧调用：按文字点击（内部已改为通用实现） */
    public boolean clickNodeWithText(String label) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isWeChatWindow(root)) return false;
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

    public boolean isWeChatForeground() {
        return WECHAT_PKG.equals(getForegroundPackage());
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
