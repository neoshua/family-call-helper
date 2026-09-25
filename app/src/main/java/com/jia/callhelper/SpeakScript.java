package com.jia.callhelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音播报文案（用户可在设置页全部自定义）。
 *
 * 用户要求："所有的语音播报都可以自定义"。
 *
 * 设计要点：
 *  1. **每一条都有精心写好的默认值**，用户什么都不改也能用；
 *  2. 文案里可以用占位符，播报时替换成真实内容：
 *       {称呼}   —— App 里配置的家人称呼（如「丈母娘」）；不在名单里时是微信显示名
 *       {秒数}   —— 自动接听剩余秒数
 *       {类型}   —— 「视频」或「语音」
 *  3. 存放在 SharedPreferences（与名单同一个文件），不联网、不上传。
 *
 * 为什么文案要集中在这里：以前这些中文散落在 CallSessionManager 的字符串拼接里，
 * 改一句话要动业务代码，用户更不可能自己改。集中一处后，设置页只要遍历
 * {@link Item} 就能自动生成编辑界面，以后新增一条文案也只改这个文件。
 */
public final class SpeakScript {

    /** 所有可自定义的文案（顺序即设置页显示顺序） */
    public enum Item {
        /** 第一句：谁打来的（最常听见的一句） */
        GREETING("speak_greeting", "开头：谁打来的",
                "{称呼}来{类型}电话了。",
                "这是老人最先听到的一句。"),
        /** 自动接听时：还要等几秒 */
        AUTO_WAIT("speak_auto_wait", "自动接听：等待中",
                "还有{秒数}秒自动帮您接听。",
                "开了自动接听的家人来电，会念出还要等几秒。"),
        /** 手动接听时：教老人点哪里（全屏来电界面） */
        GUIDE_FULL("speak_guide_full", "提醒接听：屏幕上已圈出按钮",
                "请点屏幕上圈出的绿色接听按钮。不想接就点左边的红色按钮。",
                "屏幕上已经出现微信来电界面、绿色圆圈套住接听键时念这句。"),
        /** 还没出现全屏界面时：让老人先点一下通知 */
        GUIDE_NOTIFY("speak_guide_notify", "提醒接听：只有通知（正在自动打开）",
                "正在为您打开微信来电界面，请稍等。",
                "屏幕上只有通知条、还没打开来电界面时念这句。"),
        /** 循环播报时的简短版本（全屏界面） */
        REPEAT_FULL("speak_repeat_full", "循环提醒：屏幕上已圈出按钮",
                "请点屏幕上圈出的绿色接听按钮。",
                "一直没接听时，反复提醒的短句。"),
        /** 循环播报时的简短版本（只有通知） */
        REPEAT_NOTIFY("speak_repeat_notify", "循环提醒：只有通知",
                "请点一下屏幕上的微信来电。",
                "一直没接听、且界面上还没有接听按钮时，反复提醒的短句。"),
        /** 自动点击失败：需要老人自己动手 */
        ACCEPT_FAILED("speak_accept_failed", "自动接听没成功，请自己点",
                "没能自动接听，请点屏幕上圈出的绿色接听按钮。",
                "自动点击没成功时念这句，同时屏幕指引会重新显示。"),
        /** 试听用 */
        TEST("speak_test", "试听（设置页点按钮时念）",
                "{称呼}来{类型}电话了。这是试听，想接就点屏幕上圈出的绿色按钮。",
                "只在设置页点「试听」时念，不会真的接电话。");

        public final String key;
        public final String title;
        public final String def;
        public final String hint;

        Item(String key, String title, String def, String hint) {
            this.key = key;
            this.title = title;
            this.def = def;
            this.hint = hint;
        }
    }

    private SpeakScript() {}

    private static SharedPreferences prefs(Context ctx) {
        return WhiteListManager.prefs(ctx);
    }

    /** 取某条文案（用户没改过就返回默认值）。绝不返回空串 */
    public static String get(Context ctx, Item item) {
        String v = null;
        try {
            v = prefs(ctx).getString(item.key, null);
        } catch (Exception ignore) {}
        if (v == null || v.trim().isEmpty()) return item.def;
        return v;
    }

    /** 用户是否改过这条文案 */
    public static boolean isCustomized(Context ctx, Item item) {
        try {
            String v = prefs(ctx).getString(item.key, null);
            return v != null && !v.trim().isEmpty() && !v.equals(item.def);
        } catch (Exception ignore) {
            return false;
        }
    }

    public static void set(Context ctx, Item item, String text) {
        if (text == null || text.trim().isEmpty()) {
            reset(ctx, item);
            return;
        }
        prefs(ctx).edit().putString(item.key, text.trim()).apply();
    }

    /** 恢复某条为默认文案 */
    public static void reset(Context ctx, Item item) {
        prefs(ctx).edit().remove(item.key).apply();
    }

    /** 全部恢复默认 */
    public static void resetAll(Context ctx) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        for (Item it : Item.values()) ed.remove(it.key);
        ed.apply();
    }

    // ---------------- 渲染（把占位符换成真实内容） ----------------

    /**
     * 渲染一条文案。
     *
     * @param caller 念给老人的称呼（已由 CallSessionManager 做过去昵称处理）
     * @param video  是否视频通话
     * @param remainSec 自动接听剩余秒数，&lt;=0 表示用不到
     */
    public static String render(Context ctx, Item item, String caller,
                                boolean video, long remainSec) {
        return fill(get(ctx, item), caller, video, remainSec);
    }

    /** 占位符替换（与取文案分开，便于设置页做"预览"而不落库） */
    public static String fill(String template, String caller, boolean video, long remainSec) {
        if (template == null) return "";
        String name = (caller == null || caller.trim().isEmpty()) ? "家人" : caller.trim();
        String type = video ? "视频" : "语音";
        String s = template;
        s = s.replace("{称呼}", name);
        s = s.replace("{类型}", type);
        s = s.replace("{秒数}", remainSec > 0 ? String.valueOf(remainSec) : "");
        // 用户可能写「{称呼}」以外的写法，顺手兼容半角花括号
        s = s.replace("{name}", name).replace("{type}", type);
        // 占位符被替换成空时可能留下多余空格，收一下
        return s.replaceAll("\\s{2,}", " ").trim();
    }

    /** 设置页用：列出所有文案的当前值（标题 → 正文），便于生成编辑界面 */
    public static Map<String, String> snapshot(Context ctx) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Item it : Item.values()) m.put(it.title, get(ctx, it));
        return m;
    }

    /** 可用占位符说明（设置页展示给用户看） */
    public static String placeholderHelp() {
        return "可用占位符：{称呼} = 家人称呼，{类型} = 视频/语音，{秒数} = 自动接听剩余秒数";
    }
}
