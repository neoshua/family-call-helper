package com.jia.callhelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 微信「来电邀请」话术的可自定义配置。
 *
 * <p>v1.23 及以前这些话术是写死在 {@link WeChatCallListenerService} 里的常量，
 * 一旦微信换文案、或老人用的是海外版/英文版微信，通知文案变成
 * "XXX is inviting you to a video call"，就一个都匹配不上 → 根本不触发接听。
 *
 * <p>v1.24 起挪进 SharedPreferences，用户能在「设置 → 自定义来电话术」里改。
 * 默认值保留 v1.23 的全部中文词表，用户没改过就用默认值，行为和旧版完全一致。
 *
 * <p>两个词表：
 * <ul>
 *   <li><b>邀请词</b>：通知里出现其中任一句 → 认定为"正在响铃的来电"。</li>
 *   <li><b>非邀请词</b>：先排除"通话已结束/已取消"这类，避免把结束通知当新来电。</li>
 * </ul>
 */
public final class NotifyPhrasesPrefs {

    private static final String KEY_INVITE = "notify_invite_phrases";
    private static final String KEY_NOT_INVITE = "notify_not_invite_phrases";
    private static final String KEY_INVITE_CUSTOM = "notify_invite_custom";
    private static final String KEY_NOT_INVITE_CUSTOM = "notify_not_invite_custom";

    /** 默认「是来电邀请」话术（v1.23 词表，逐条完整句子，不接受孤立关键词） */
    public static final String[] DEFAULT_INVITE = {
            "邀请你视频通话", "邀请你语音通话", "邀请你通话",
            "邀请你进行视频通话", "邀请你进行语音通话",
            "邀请你视频", "邀请你语音", "邀请你接听",
            "邀请您视频通话", "邀请您语音通话", "邀请您通话",
            "视频通话邀请", "语音通话邀请", "通话邀请",
            "向您发起视频通话", "向您发起语音通话",
            "正在呼叫你", "正在呼叫您"
    };

    /** 默认「已结束 / 不是新来电」话术（先否定，优先级高于邀请词） */
    public static final String[] DEFAULT_NOT_INVITE = {
            "通话时长", "通话结束", "已接通", "通话中断", "通话已",
            "已取消", "已拒绝", "未接听", "已过期"
    };

    private static SharedPreferences sp(Context ctx) {
        return WhiteListManager.prefs(ctx);
    }

    /** 用户是否改过「邀请」话术（改过才读用户版，否则读默认） */
    public static boolean hasCustomInvite(Context ctx) {
        return sp(ctx).getBoolean(KEY_INVITE_CUSTOM, false);
    }

    public static Set<String> getInvitePhrases(Context ctx) {
        if (!hasCustomInvite(ctx)) {
            return new LinkedHashSet<>(Arrays.asList(DEFAULT_INVITE));
        }
        Set<String> s = sp(ctx).getStringSet(KEY_INVITE, null);
        if (s == null || s.isEmpty()) {
            return new LinkedHashSet<>(Arrays.asList(DEFAULT_INVITE));
        }
        return new LinkedHashSet<>(s);
    }

    public static Set<String> getNotInvitePhrases(Context ctx) {
        if (!sp(ctx).getBoolean(KEY_NOT_INVITE_CUSTOM, false)) {
            return new LinkedHashSet<>(Arrays.asList(DEFAULT_NOT_INVITE));
        }
        Set<String> s = sp(ctx).getStringSet(KEY_NOT_INVITE, null);
        if (s == null || s.isEmpty()) {
            return new LinkedHashSet<>(Arrays.asList(DEFAULT_NOT_INVITE));
        }
        return new LinkedHashSet<>(s);
    }

    /** 保存用户编辑的邀请话术（一行一个） */
    public static void setInvitePhrases(Context ctx, Set<String> phrases) {
        Set<String> clean = clean(phrases);
        sp(ctx).edit().putStringSet(KEY_INVITE, clean)
                .putBoolean(KEY_INVITE_CUSTOM, true).apply();
    }

    public static void setNotInvitePhrases(Context ctx, Set<String> phrases) {
        Set<String> clean = clean(phrases);
        sp(ctx).edit().putStringSet(KEY_NOT_INVITE, clean)
                .putBoolean(KEY_NOT_INVITE_CUSTOM, true).apply();
    }

    /** 恢复默认（清掉自定义标记，下次读默认词表） */
    public static void resetToDefault(Context ctx) {
        sp(ctx).edit()
                .remove(KEY_INVITE).putBoolean(KEY_INVITE_CUSTOM, false)
                .remove(KEY_NOT_INVITE).putBoolean(KEY_NOT_INVITE_CUSTOM, false)
                .apply();
    }

    private static Set<String> clean(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in == null) return out;
        for (String p : in) {
            String t = p == null ? "" : p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
