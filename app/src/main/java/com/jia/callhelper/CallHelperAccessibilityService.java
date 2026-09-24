package com.jia.callhelper;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.accessibilityservice.GestureDescription;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 微信专用无障碍服务。
 *
 * 作用：
 * 1. 看到「接听+挂断」按钮 → 判断为微信来电界面 → 通知 CallSessionManager 播报来电人
 *    （覆盖微信在前台、没有系统通知的场景）
 * 2. 看到接听消失、出现「静音/免提」→ 通话已接通 → 停止播报
 * 3. 提供自动点击能力：替用户点掉微信里的接听键（本应用自己不显示任何界面）
 *
 * 只监听 com.tencent.mm 一个包，其他应用零开销。
 */
public class CallHelperAccessibilityService extends AccessibilityService {

    /** 只认微信。用于事件过滤，也用于「只在微信界面里点击」的防呆校验 */
    private static final String WECHAT_PKG = "com.tencent.mm";

    private static volatile CallHelperAccessibilityService sInstance;
    private static final long SCAN_INTERVAL_MS = 1200;
    private volatile long mLastScanAt = 0;

    public static CallHelperAccessibilityService get() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
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
            scanNow();
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // 内容变化事件很频繁，限流扫描
            long now = SystemClock.elapsedRealtime();
            if (now - mLastScanAt > SCAN_INTERVAL_MS) {
                scanNow();
            }
        }
    }

    @Override
    public void onInterrupt() {
        // 无需处理
    }

    /** 扫描当前微信界面，判断处于来电/通话中/已结束哪种状态 */
    private void scanNow() {
        mLastScanAt = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        // 必须确认当前在微信界面：后台微信发来的事件也可能触发本方法，
        // 而此刻屏幕最上层可能是别的应用（甚至另一个也有「接听/挂断」字样的界面），
        // 不校验就会把别的界面误判成微信来电界面。
        if (!isWeChatWindow(root)) return;

        try {
            boolean hasAnswer = findNode(root, "接听", false) != null;
            boolean hasDecline = findNode(root, "挂断", false) != null;

            if (hasAnswer && hasDecline) {
                // 微信来电界面
                boolean video = findNode(root, "切换到语音", false) != null;
                String name = guessCallerName(root);
                CallSessionManager.onIncomingViaA11y(this, name, video);
            } else if (hasDecline && !hasAnswer) {
                // 接通后的通话界面：有挂断，还应有静音/免提
                if (findNode(root, "静音", false) != null
                        || findNode(root, "免提", false) != null
                        || findNode(root, "扬声器", false) != null) {
                    CallSessionManager.onWeChatCallAnswered(this);
                } else if (findNode(root, "通话结束", false) != null
                        || findNode(root, "已结束", false) != null
                        || findNode(root, "通话时长", false) != null) {
                    CallSessionManager.onWeChatCallEnded(this, "界面显示通话结束");
                }
            }
        } finally {
            // 不 recycle：节点可能被上层持有
        }
    }

    /**
     * 自动点击：找到文字对应的节点并点击（找不到返回 false，可重试）。
     *
     * 防呆关键：**只允许在微信界面里点击**。
     * 若不校验包名，getRootInActiveWindow() 可能返回的是别的应用的界面，
     * 于是「帮您按微信的接听键」就点到了别处——表面上点成功了，实际根本没碰到微信。
     * 这就是必须做包名校验的原因。
     */
    public boolean clickNodeWithText(String label) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        if (!isWeChatWindow(root)) return false;
        AccessibilityNodeInfo node = findNode(root, label, false);
        if (node == null) return false;

        // 优先用无障碍点击：找自身或最近的可点击祖先
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

        // 兜底：模拟手势点击节点中心（适配自定义绘制的按钮）
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) {
                    return tapScreen(r.exactCenterX(), r.exactCenterY());
                }
            } catch (Exception ignore) {}
        }
        return false;
    }

    /** 当前窗口是否属于微信（防止误操作我们自己的界面） */
    private boolean isWeChatWindow(AccessibilityNodeInfo root) {
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        return pkg != null && WECHAT_PKG.equals(pkg.toString());
    }

    private boolean tapScreen(float x, float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder gb = new GestureDescription.Builder();
            gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
            return dispatchGesture(gb.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 节点查找 ----------------

    /** 先精确匹配，再包含匹配 */
    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, String key, boolean exactOnly) {
        AccessibilityNodeInfo r = findNodeInternal(root, key, true);
        if (r == null && !exactOnly) {
            r = findNodeInternal(root, key, false);
        }
        return r;
    }

    private AccessibilityNodeInfo findNodeInternal(AccessibilityNodeInfo root, String key, boolean exact) {
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo n = stack.pop();
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

    // ---------------- 来电人姓名猜测（仅辅助，通知里拿到的名字优先） ----------------

    private String guessCallerName(AccessibilityNodeInfo root) {
        String best = null;
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<AccessibilityNodeInfo>();
        stack.push(root);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo n = stack.pop();
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
                || s.contains("消息") || s.contains("秒") || s.contains("分钟") || s.contains("…");
    }
}
