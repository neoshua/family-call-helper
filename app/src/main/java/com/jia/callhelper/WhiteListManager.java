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
        return null;
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
