package com.jia.callhelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 家人白名单管理。
 * 存储格式：wl_<时间戳> = "称呼|备注名或号码|是否自动接听(0/1)"
 */
public class WhiteListManager {

    public static class Entry {
        public String key;
        public String name;
        public String number;
        public boolean auto;
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("call_helper", Context.MODE_PRIVATE);
    }

    public static List<Entry> load(Context ctx) {
        List<Entry> list = new ArrayList<Entry>();
        Map<String, ?> all = prefs(ctx).getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k == null || !k.startsWith("wl_")) continue;
            String v = String.valueOf(e.getValue());
            String[] parts = v.split("\\|");
            if (parts.length < 2) continue;
            Entry en = new Entry();
            en.key = k;
            en.name = parts[0];
            en.number = parts[1];
            en.auto = parts.length >= 3 && "1".equals(parts[2]);
            list.add(en);
        }
        return list;
    }

    public static void add(Context ctx, String name, String number, boolean auto) {
        String key = "wl_" + System.currentTimeMillis();
        prefs(ctx).edit().putString(key,
                name + "|" + (number == null ? "" : number) + "|" + (auto ? "1" : "0")).apply();
    }

    public static void remove(Context ctx, String key) {
        prefs(ctx).edit().remove(key).apply();
    }

    public static void setAuto(Context ctx, String key, boolean auto) {
        for (Entry e : load(ctx)) {
            if (e.key.equals(key)) {
                prefs(ctx).edit().putString(key,
                        e.name + "|" + e.number + "|" + (auto ? "1" : "0")).apply();
                return;
            }
        }
    }

    /** 按 key 取单条联系人（用于详情/编辑页） */
    public static Entry get(Context ctx, String key) {
        Object v = prefs(ctx).getAll().get(key);
        if (v == null) return null;
        String[] parts = String.valueOf(v).split("\\|");
        if (parts.length < 2) return null;
        Entry e = new Entry();
        e.key = key;
        e.name = parts[0];
        e.number = parts[1];
        e.auto = parts.length >= 3 && "1".equals(parts[2]);
        return e;
    }

    /** 修改联系人的称呼 / 微信备注名 / 自动接听 */
    public static void update(Context ctx, String key, String name, String number, boolean auto) {
        prefs(ctx).edit().putString(key,
                name + "|" + (number == null ? "" : number) + "|" + (auto ? "1" : "0")).apply();
    }

    /**
     * 来电人是否在家人名单。
     *
     * 分两轮匹配，顺序很重要：
     *   第一轮按「微信备注名 / 微信号」—— 这是最可靠的依据（配置时就是照微信里抄的）
     *   第二轮才按「称呼」—— 有些人会把称呼直接填成微信里显示的名字
     * 先匹配备注名可以避免"称呼"造成误命中（例如称呼是「儿子」，
     * 而微信里恰好有个叫「儿子的同事」的陌生人）。
     *
     * 【v1.12】未命中时不再只是返回 null，而是记下"这次看到的名字"，
     * 并去重保存成"最近未匹配的来电人"。设置页会把它显示出来并提示：
     * "微信来电显示的是 XXX，如果这是你家人，请把 XXX 填进他的微信备注名"。
     * 用户实测反馈过：填了「老婆」但微信显示「hh」，app 匹配不上，
     * 表现为"既没自动接听、播报还是微信昵称"，而用户根本不知道问题出在哪。
     */
    public static Entry match(Context ctx, String caller) {
        Entry hit = matchQuiet(ctx, caller);
        if (hit == null && caller != null && !caller.trim().isEmpty()) {
            noteUnmatched(ctx, caller.trim());
        }
        return hit;
    }

    /** 纯匹配，不产生任何副作用（自动化流程内部用） */
    public static Entry matchQuiet(Context ctx, String caller) {
        if (caller == null || caller.trim().isEmpty()) return null;
        String c = caller.trim();
        List<Entry> list = load(ctx);

        for (Entry e : list) {
            if (e.number == null) continue;
            String n = e.number.trim();
            if (!n.isEmpty() && c.equals(n)) return e;
        }
        for (Entry e : list) {
            if (e.number == null) continue;
            String n = e.number.trim();
            if (n.length() >= 2 && c.contains(n)) return e;
        }
        for (Entry e : list) {
            if (e.name == null) continue;
            String n = e.name.trim();
            if (!n.isEmpty() && c.equals(n)) return e;
        }
        for (Entry e : list) {
            if (e.name == null) continue;
            String n = e.name.trim();
            if (n.length() >= 2 && c.contains(n)) return e;
        }
        // 【v1.14】上面四轮都是"原样比较"。真机上很多次匹配不上，
        // 根本原因是两边看着一样、字符却不同，例如：
        //   · 微信标题「hh 」带一个看不见的尾空格 / 换行
        //   · 用户填的是全角「ｈｈ」而微信是半角「hh」
        //   · 通知标题是「hh 邀请你视频通话」（名字和话术挤在同一串里）
        //   · 大小写不同（「Hh」vs「hh」）
        // 这些在原样比较里全部落空，表现就是"既不自动接听、播报还念微信昵称"。
        // 所以再补一轮**规范化后**的比较：统一小写、去掉所有空白与常见标点，
        // 再判断相等或互相包含。这一轮只在前四轮都失败时才走，不影响原有优先级。
        Entry loose = matchLoose(c, list);
        if (loose != null) {
            CallDiag.log("来电", "名单匹配：原样比较未命中，规范化后命中「"
                    + loose.name + "」（微信显示「" + c + "」，配置「"
                    + loose.number + "」）");
        }
        return loose;
    }

    /**
     * 规范化匹配（最后一道匹配手段）。
     *
     * 把两边的"干扰字符"全部剥掉后再比：小写化 + 去掉空白、全角转半角、
     * 去掉常见中英文标点。剥完再判断相等 / 互相包含。
     *
     * 注意仍然要求被包含的一方长度 ≥ 2，避免单字（如「妈」）误命中一大串文字。
     */
    private static Entry matchLoose(String caller, List<Entry> list) {
        String c = normalize(caller);
        if (c.isEmpty()) return null;

        // 先比备注名/号码，再比称呼——与原样比较保持同样的优先级
        for (Entry e : list) {
            String n = normalize(e.number);
            if (n.length() >= 2 && c.equals(n)) return e;
        }
        for (Entry e : list) {
            String n = normalize(e.number);
            if (n.length() >= 2 && (c.contains(n) || n.contains(c))) return e;
        }
        for (Entry e : list) {
            String n = normalize(e.name);
            if (n.length() >= 2 && c.equals(n)) return e;
        }
        for (Entry e : list) {
            String n = normalize(e.name);
            if (n.length() >= 2 && (c.contains(n) || n.contains(c))) return e;
        }
        return null;
    }

    /**
     * 归一化：全角转半角 + 去空白 + 去标点 + 转小写。
     *
     * 做成 public 是为了让设置页能把"微信显示的名字"和"你配置的名字"
     * 都以归一化后的样子展示出来——用户一眼就能看出到底差在哪个字符上，
     * 而不是面对两个"看起来一模一样"的字符串无从下手。
     */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // 全角 ASCII（！-～）转半角
            if (ch >= '\uFF01' && ch <= '\uFF5E') ch = (char) (ch - 0xFEE0);
            // 全角空格
            if (ch == '\u3000') continue;
            if (Character.isWhitespace(ch)) continue;
            // 常见中英文标点一律丢掉，它们不该参与"是不是同一个人"的判断
            if ("：:，,。.、！!？?·-—_~～'\"“”‘’()（）[]【】{}<>《》".indexOf(ch) >= 0) continue;
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /**
     * 给"为什么没匹配上"做一份可读的对照说明，写进运行记录 / 设置页提示。
     * 返回形如：微信显示「hh」→ 归一化「hh」；你配置的「丈母娘/AB」→ 归一化「丈母娘/ab」
     */
    public static String explainNoMatch(Context ctx, String caller) {
        StringBuilder sb = new StringBuilder();
        sb.append("微信显示「").append(caller == null ? "" : caller.trim())
                .append("」→ 归一化「").append(normalize(caller)).append("」");
        List<Entry> list = load(ctx);
        if (list.isEmpty()) {
            sb.append("；家人名单为空（还没添加任何家人）");
            return sb.toString();
        }
        sb.append("；名单里能比的是：");
        int i = 0;
        for (Entry e : list) {
            if (i++ > 0) sb.append(" / ");
            sb.append("「").append(e.name).append("」");
            if (e.number != null && !e.number.trim().isEmpty()) {
                sb.append("(备注=").append(e.number.trim())
                        .append("→").append(normalize(e.number)).append(")");
            }
        }
        return sb.toString();
    }

    // ---------------- 未匹配来电人的记录 ----------------

    private static final String KEY_UNMATCHED = "unmatched_callers";
    /** 最多记 8 个，多了没用还占地方 */
    private static final int UNMATCHED_MAX = 8;

    /** 记下"没见过、也没匹配上"的来电人（去重，新的排前面） */
    public static void noteUnmatched(Context ctx, String caller) {
        if (caller == null) return;
        String c = caller.trim();
        if (c.isEmpty() || c.length() > 40) return;
        try {
            List<String> old = getUnmatched(ctx);
            List<String> out = new ArrayList<String>();
            out.add(c);
            for (String s : old) {
                if (!s.equals(c) && out.size() < UNMATCHED_MAX) out.add(s);
            }
            StringBuilder sb = new StringBuilder();
            for (String s : out) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(s);
            }
            prefs(ctx).edit().putString(KEY_UNMATCHED, sb.toString()).apply();
        } catch (Exception ignore) {}
    }

    /** 最近几次"微信有来电、但名单里没有他"的显示名（最新的在前） */
    public static List<String> getUnmatched(Context ctx) {
        List<String> out = new ArrayList<String>();
        String v = prefs(ctx).getString(KEY_UNMATCHED, "");
        if (v == null || v.isEmpty()) return out;
        for (String line : v.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static void clearUnmatched(Context ctx) {
        prefs(ctx).edit().remove(KEY_UNMATCHED).apply();
    }

    /** 从待办列表里移除一个名字（用户已把它填进某位家人的备注名后调用） */
    public static void removeUnmatched(Context ctx, String caller) {
        if (caller == null) return;
        List<String> old = getUnmatched(ctx);
        StringBuilder sb = new StringBuilder();
        for (String s : old) {
            if (s.equals(caller.trim())) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(s);
        }
        prefs(ctx).edit().putString(KEY_UNMATCHED, sb.toString()).apply();
    }
}
