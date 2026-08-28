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

    /** 来电人是否在家人名单（按备注名精确/包含、号码包含匹配） */
    public static Entry match(Context ctx, String caller) {
        if (caller == null || caller.trim().isEmpty()) return null;
        String c = caller.trim();
        for (Entry e : load(ctx)) {
            if (c.equals(e.name)) return e;
            if (e.name != null && e.name.length() >= 2 && c.contains(e.name)) return e;
            if (e.number != null && !e.number.isEmpty() && c.contains(e.number)) return e;
        }
        return null;
    }
}
