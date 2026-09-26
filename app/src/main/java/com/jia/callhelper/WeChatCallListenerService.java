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
     * ⚠️ 这是「挂断后还在一直播报」的根因修复点。
     * 实测微信在【对方挂断 / 自己接听 / 对方取消】时，处理方式是**把来电通知直接移除**，
     * 而不是把通知文字改成「已取消」。旧版本只实现了 onNotificationPosted（内容变化），
     * 没实现 onNotificationRemoved，于是挂断后 App 完全不知道，
     * 语音播报会一直念到硬超时（旧版是 3 分钟）。
     */
    private static volatile String sCallNotifyKey;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 【v1.21】这里以前是 catch(Exception ignore) —— 通知监听是**唯一的来电入口**：
        // 一旦 handle() 里出任何异常，它被吞得干干净净，现象就是"来电了 App 完全没反应"，
        // 运行记录里还一条痕迹都没有。现在必须留下报错。
        try {
            handle(sbn);
        } catch (Throwable t) {
            CallDiag.init(this);
            CallDiag.log("通知", "处理微信通知异常（这条异常会被上层忽略）：" + t);
        }
    }

    /** 微信来电通知消失 = 来电结束（挂断/已接听/已取消/被划掉） */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            handleRemoved(sbn);
        } catch (Throwable t) {
            CallDiag.init(this);
            CallDiag.log("通知", "处理微信通知移除异常：" + t);
        }
    }

    private void handleRemoved(StatusBarNotification sbn) {
        if (sbn == null || !WECHAT.equals(sbn.getPackageName())) return;
        String key = sbn.getKey();
        if (key == null || !key.equals(sCallNotifyKey)) return;
        sCallNotifyKey = null;
        CallDiag.init(this);
        CallDiag.log("通知", "微信来电通知已消失 → 停止语音播报与震动");
        CallSessionManager.onWeChatCallNotificationGone(this);
    }

    /**
     * 【v1.22】判定"这是一通正在响铃的来电"。
     *
     * ## 为什么这里必须死守"完整话术"
     *
     * v1.21 为了让更多机型能被覆盖，把这个判断放松成了
     * 「含 邀请/来电/呼叫/通话 任意一个词 + 通知本身是 ongoing 或高优先级」。
     * 那次放松是错的：微信的**普通消息通知**通常就是高优先级，
     * 于是别人发来一句「我们在通话吗」也会被当成来电；
     * 加上标题就是对方昵称，结果 App 直接开始播报，并把对方塞进
     * 「最近未匹配的来电人」列表 —— 用户看到的正是"没给我打过电话的人出现在列表里"。
     *
     * 现在回到最保守也最贴合事实的判据：**通知标题/正文里出现完整的来电邀请话术**。
     * 漏掉某种写法（这一通没提醒）的代价，远低于没事就播报一次的骚扰。
     *
     * ## 词表为什么全是完整句子
     * 微信来电通知的标准形态是
     *   标题：张三        正文：邀请你视频通话
     *   标题：微信        正文：张三：邀请你语音通话
     * 所以这里逐条列出**完整话术**，不接受孤立关键词。
     */
    private static final String[] INVITE_PHRASES = {
            "邀请你视频通话", "邀请你语音通话", "邀请你通话",
            "邀请你进行视频通话", "邀请你进行语音通话",
            "邀请你视频", "邀请你语音", "邀请你接听",
            "邀请您视频通话", "邀请您语音通话", "邀请您通话",
            "视频通话邀请", "语音通话邀请", "通话邀请",
            "向您发起视频通话", "向您发起语音通话",
            "正在呼叫你", "正在呼叫您"
    };

    /** 通话已经结束的通知里常见的话术：不能因为它含"通话"就当成新来电 */
    private static final String[] NOT_AN_INVITE = {
            "通话时长", "通话结束", "已接通", "通话中断", "通话已",
            "已取消", "已拒绝", "未接听", "已过期"
    };

    /**
     * 判断是否是「正在响铃的来电邀请」。
     *
     * 判定顺序很重要：先排除"通话已经结束"类的通知，再看完整邀请话术。
     * 二者都可能命中同一个字符串（例如「通话已结束」里既有"通话"也有"结束"），
     * 所以先否定的那一步必须在前。
     */
    private boolean isIncomingInvite(String s) {
        for (String k : NOT_AN_INVITE) {
            if (s.contains(k)) return false;
        }
        for (String k : INVITE_PHRASES) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null || !WECHAT.equals(sbn.getPackageName())) return;

        // 【v1.22】总开关：关掉之后整个 App 的核心功能停摆。
        // 闸口放在**入口最前面**，而不是散落在会话逻辑各处 —— 后者总会漏掉某个角落，
        // 表现为"明明关了却还在响"。这里一票否决，后面所有逻辑都不会执行。
        CallDiag.init(this);
        if (!WhiteListManager.isAppEnabled(this)) return;

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
        if (!isIncomingInvite(all)) {
            if (callRelated) {
                CallDiag.log("通知", "与通话有关但未认定为来电邀请（没有完整的邀请话术）："
                        + shortOf(all) + "（渠道=" + channel + " flags=" + n.flags + "）");
            }
            return;
        }

        String caller = resolveCaller(title, text, bigText, ticker);
        boolean video = all.contains("视频");
        PendingIntent pi = n.contentIntent;
        // 记住这条通知：它一消失（挂断/接听/取消）就要立刻停止语音播报与震动
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
