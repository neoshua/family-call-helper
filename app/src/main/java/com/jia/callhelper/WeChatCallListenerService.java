package com.jia.callhelper;

import android.app.Notification;
import android.app.PendingIntent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 微信来电通知监听。
 *
 * 微信来电时（尤其锁屏/后台）会发出类似这样的通知：
 *   标题：张三    正文：邀请你视频通话
 *   或：标题：微信  正文：张三：邀请你语音通话
 * 挂断/取消时会有「对方已取消」「通话已结束」等通知。
 */
public class WeChatCallListenerService extends NotificationListenerService {

    private static final String WECHAT = "com.tencent.mm";
    private static final String[] END_KEYS = {
            "已取消", "已拒绝", "已结束", "已挂断", "未接听", "已过期", "已超时"
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Exception ignore) {}
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null || !WECHAT.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        String title = cs(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = cs(n.extras.getCharSequence(Notification.EXTRA_TEXT));
        String bigText = cs(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String ticker = cs(n.tickerText);
        String all = (title + " " + text + " " + bigText + " " + ticker).trim();
        if (all.isEmpty()) return;

        // 1. 结束类通知：停止播报、关掉大按钮界面
        if (isEndMessage(all)) {
            CallSessionManager.onWeChatCallEnded(this, all);
            return;
        }

        // 2. 来电邀请类通知
        if (!isIncomingInvite(all, n)) return;

        String caller = resolveCaller(title, text, bigText, ticker);
        boolean video = all.contains("视频");
        PendingIntent pi = n.contentIntent;
        CallSessionManager.startCall(this, caller, video, pi, false);
    }

    private boolean isEndMessage(String s) {
        if (!s.contains("通话")) return false;
        for (String k : END_KEYS) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /**
     * 判断是否是「正在响铃的来电邀请」。
     * 除了文本匹配（邀请 + 通话），还要求通知本身像来电通知：
     * 进行中(ongoing)、高优先级、或渠道名含 voip/call/语音/视频，
     * 以避免把聊天记录里历史的「邀请你视频通话」消息当成来电。
     */
    private boolean isIncomingInvite(String s, Notification n) {
        if (!s.contains("邀请") || !s.contains("通话")) return false;
        if (n.flags != 0 && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return true;
        if (n.priority >= Notification.PRIORITY_HIGH) return true;
        try {
            String channel = n.getChannelId() == null ? "" : n.getChannelId().toLowerCase();
            return channel.contains("voip") || channel.contains("call")
                    || channel.contains("voice") || channel.contains("video")
                    || n.getChannelId().contains("语音") || n.getChannelId().contains("视频");
        } catch (Exception ignore) {
            return false;
        }
    }

    private String resolveCaller(String title, String text, String bigText, String ticker) {
        // 优先用标题（常见格式：标题=张三，正文=邀请你视频通话）
        if (isValidName(title)) return title.trim();

        // 其次从正文里截取「张三：邀请你语音通话」的「张三」
        String body = !text.isEmpty() ? text : (!bigText.isEmpty() ? bigText : ticker);
        int idx = body.indexOf("邀请");
        if (idx > 0) {
            String name = body.substring(0, idx).trim();
            name = name.replace("：", "").replace(":", "")
                    .replace("，", "").replace(",", "").trim();
            if (isValidName(name)) return name;
        }
        return "微信联系人";
    }

    private boolean isValidName(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.isEmpty() || s.length() > 20) return false;
        String lower = s.toLowerCase();
        return !lower.contains("微信") && !s.contains("通话") && !s.contains("邀请")
                && !s.contains("消息") && !s.contains("通知") && !s.contains("已");
    }

    private static String cs(CharSequence c) {
        return c == null ? "" : c.toString().trim();
    }
}
