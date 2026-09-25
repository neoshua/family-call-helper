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
     */
    public static Entry match(Context ctx, String caller) {
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
}
