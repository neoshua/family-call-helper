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

    /**
     * 最近一条被认定为「微信来电」的通知 key。
     *
     * ⚠️ 这是「挂断后铃声还在响」的根因修复点。
     * 实测微信在【对方挂断 / 自己接听 / 对方取消】时，处理方式是**把来电通知直接移除**，
     * 而不是把通知文字改成「已取消」。旧版本只实现了 onNotificationPosted（内容变化），
     * 没实现 onNotificationRemoved，于是挂断后 App 完全不知道，
     * 语音和铃声会一直响到硬超时（旧版是 3 分钟）。
     */
    private static volatile String sCallNotifyKey;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Exception ignore) {}
    }

    /** 微信来电通知消失 = 来电结束（挂断/已接听/已取消/被划掉） */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            handleRemoved(sbn);
        } catch (Exception ignore) {}
    }

    private void handleRemoved(StatusBarNotification sbn) {
        if (sbn == null || !WECHAT.equals(sbn.getPackageName())) return;
        String key = sbn.getKey();
        if (key == null || !key.equals(sCallNotifyKey)) return;
        sCallNotifyKey = null;
        CallDiag.init(this);
        CallDiag.log("通知", "微信来电通知已消失 → 停止语音与铃声");
        CallSessionManager.onWeChatCallNotificationGone(this);
    }

    /**
     * 「一定是在响铃的来电邀请」的特征话术。命中就直接认定为来电，
     * 不再去看通知的 ongoing / 优先级 / 渠道名。
     *
     * 为什么必须这样放松：安卓 8.0 起通知优先级由 NotificationChannel 决定，
     * Notification.priority 恒为默认值 0；微信不同版本的渠道名也不一样
     * （voip_notify / message_voip / 视频通话…）。原来要求「必须 ongoing 或
     * 高优先级或渠道名含 voip」才算来电，会把这些特征都不满足的来电通知
     * 当成普通消息丢掉 —— 结果就是「来电了但 App 完全没反应」。
     */
    private static final String[] STRONG_INVITE = {
            "邀请你视频通话", "邀请你语音通话", "邀请你通话",
            "邀请你进行视频通话", "邀请你进行语音通话",
            "邀请你视频", "邀请你语音", "邀请你接听"
    };

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

        CallDiag.init(this);
        // 只把「与通话有关」的通知写进运行记录，普通聊天消息不记，避免日志被刷爆
        boolean callRelated = all.contains("通话") || all.contains("邀请");
        String channel = "";
        try {
            channel = n.getChannelId() == null ? "" : n.getChannelId();
        } catch (Exception ignore) {}

        // 1. 结束类通知：停止播报、清理会话
        if (isEndMessage(all)) {
            if (callRelated) {
                CallDiag.log("通知", "结束类通知：" + shortOf(all) + " → 清理会话");
            }
            sCallNotifyKey = null;
            CallSessionManager.onWeChatCallEnded(this, all);
            return;
        }

        // 2. 来电邀请类通知
        if (!isIncomingInvite(all, n)) {
            if (callRelated) {
                CallDiag.log("通知", "与通话有关但未认定为来电邀请：" + shortOf(all)
                        + "（渠道=" + channel + " flags=" + n.flags + "）");
            }
            return;
        }

        String caller = resolveCaller(title, text, bigText, ticker);
        boolean video = all.contains("视频");
        PendingIntent pi = n.contentIntent;
        // 记住这条通知：它一消失（挂断/接听/取消）就要立刻停掉语音与铃声
        sCallNotifyKey = sbn.getKey();
        CallDiag.log("通知", "认定为来电邀请：" + shortOf(all)
                + " → 来电人=" + caller + " 视频=" + video
                + "（渠道=" + channel + " flags=" + n.flags + "）");
        // 【v1.12】通知里的名字通常比界面猜的更准（通知标题就是微信里的备注名），
        // 所以先让会话用它重匹配一次名单，再决定要不要新开会话。
        CallSessionManager.refineCaller(this, caller);
        CallSessionManager.startCall(this, caller, video, pi);
    }

    /** 日志里只留个短摘要，避免长文本把记录挤爆 */
    private static String shortOf(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
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
        // 强特征命中：直接认定为来电，不再要求 ongoing / 高优先级 / 渠道名。
        // 这些附加条件在真机上并不可靠（理由见 STRONG_INVITE 的注释）。
        for (String k : STRONG_INVITE) {
            if (s.contains(k)) return true;
        }
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
