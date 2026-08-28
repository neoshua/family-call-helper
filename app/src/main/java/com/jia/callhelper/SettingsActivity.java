package com.jia.callhelper;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置主界面：权限引导（四步）+ 家人白名单 + 试听 + 后台保活入口。
 * 大字体高对比，方便子女帮老人配置。
 */
public class SettingsActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 1;

    private TextView mStatusNotif;
    private TextView mStatusAcc;
    private TextView mStatusOverlay;
    private TextView mStatusFsi;
    private LinearLayout mWhitelistContainer;
    private EditText mNameInput;
    private EditText mNumberInput;
    private EditText mDelayInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mStatusNotif = (TextView) findViewById(R.id.status_notification);
        mStatusAcc = (TextView) findViewById(R.id.status_accessibility);
        mStatusOverlay = (TextView) findViewById(R.id.status_overlay);
        mStatusFsi = (TextView) findViewById(R.id.status_fullscreen);
        mWhitelistContainer = (LinearLayout) findViewById(R.id.whitelist_container);
        mNameInput = (EditText) findViewById(R.id.input_name);
        mNumberInput = (EditText) findViewById(R.id.input_number);
        mDelayInput = (EditText) findViewById(R.id.input_auto_delay);
        mDelayInput.setText(String.valueOf(WhiteListManager.prefs(this)
                .getInt("auto_delay_sec", CallSessionManager.DEFAULT_AUTO_DELAY_SEC)));

        bind(R.id.btn_open_notification_access, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        bind(R.id.btn_open_accessibility, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        bind(R.id.btn_open_overlay, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                }
            }
        });
        bind(R.id.btn_open_fullscreen, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT));
                    } catch (Exception e) {
                        Toast.makeText(SettingsActivity.this,
                                "请在 设置→应用→特殊权限 中找到「全屏通知」", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
        bind(R.id.btn_test_voice, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallSessionManager.startTestCall(SettingsActivity.this, "张三", true);
                Intent it = new Intent(SettingsActivity.this, CallAlertActivity.class);
                it.putExtra("caller_name", "张三（测试）");
                it.putExtra("is_video", true);
                it.putExtra("test_mode", true);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(it);
            }
        });
        bind(R.id.btn_add_whitelist, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addWhitelistEntry();
            }
        });
        bind(R.id.btn_save_delay, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveDelay();
            }
        });
        bind(R.id.btn_open_battery, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });

        // 安卓 13+ 通知运行时权限（弹大按钮通知需要）
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS);
            }
        }
    }

    private void bind(int id, View.OnClickListener l) {
        Button b = (Button) findViewById(id);
        if (b != null) b.setOnClickListener(l);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        renderWhitelist();
    }

    // ---------------- 权限状态展示 ----------------

    private void refreshStatus() {
        // 1. 通知使用权
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        boolean notifOk = flat != null && flat.contains(getPackageName());
        setStatus(mStatusNotif, notifOk,
                "✓ 已开启（可以监听微信来电了）",
                "✗ 未开启，点击下方按钮去开启");

        // 2. 无障碍服务
        boolean accOk = isAccessibilityEnabled();
        setStatus(mStatusAcc, accOk,
                "✓ 已开启（可以自动帮您点微信的接听键）",
                "✗ 未开启：只能播报和显示大按钮，无法自动接听");

        // 3. 悬浮窗（安卓 10+ 后台弹界面需要）
        View rowOverlay = findViewById(R.id.row_overlay);
        if (Build.VERSION.SDK_INT >= 23) {
            rowOverlay.setVisibility(View.VISIBLE);
            boolean overlayOk = Settings.canDrawOverlays(this);
            setStatus(mStatusOverlay, overlayOk,
                    "✓ 已允许（来电时能弹出大按钮界面）",
                    "✗ 未允许，来电时界面可能弹不出来");
        } else {
            rowOverlay.setVisibility(View.GONE);
        }

        // 4. 锁屏全屏通知（安卓 10+）
        View rowFsi = findViewById(R.id.row_fullscreen);
        if (Build.VERSION.SDK_INT >= 29) {
            rowFsi.setVisibility(View.VISIBLE);
            if (Build.VERSION.SDK_INT >= 33) {
                NotificationManager nm = (NotificationManager)
                        getSystemService(Context.NOTIFICATION_SERVICE);
                boolean fsiOk = nm != null && nm.canUseFullScreenIntent();
                setStatus(mStatusFsi, fsiOk,
                        "✓ 已允许（锁屏来电也能弹大按钮界面）",
                        "✗ 未允许：请为本应用和微信都打开「全屏通知」");
            } else {
                setStatus(mStatusFsi, true,
                        "默认开启；若锁屏时弹不出界面，请检查系统「全屏通知」设置", "");
            }
        } else {
            rowFsi.setVisibility(View.GONE);
        }
    }

    private void setStatus(TextView tv, boolean ok, String good, String bad) {
        if (tv == null) return;
        tv.setText(ok ? good : bad);
        tv.setTextColor(ok ? 0xFF1B7F3B : 0xFFB3261E);
    }

    private boolean isAccessibilityEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null) return false;
        return flat.contains(getPackageName() + "/"
                + CallHelperAccessibilityService.class.getName());
    }

    // ---------------- 白名单管理 ----------------

    private void addWhitelistEntry() {
        String name = mNameInput.getText().toString().trim();
        String number = mNumberInput.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请填写称呼，例如：大儿子", Toast.LENGTH_SHORT).show();
            return;
        }
        WhiteListManager.add(this, name, number, false);
        mNameInput.setText("");
        mNumberInput.setText("");
        renderWhitelist();
        Toast.makeText(this, "已添加：" + name, Toast.LENGTH_SHORT).show();
    }

    private void saveDelay() {
        int d;
        try {
            d = Integer.parseInt(mDelayInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            d = CallSessionManager.DEFAULT_AUTO_DELAY_SEC;
        }
        if (d < 3) d = 3;
        if (d > 60) d = 60;
        mDelayInput.setText(String.valueOf(d));
        WhiteListManager.prefs(this).edit().putInt("auto_delay_sec", d).apply();
        Toast.makeText(this, "已保存：自动接听前等待 " + d + " 秒", Toast.LENGTH_SHORT).show();
    }

    private void renderWhitelist() {
        mWhitelistContainer.removeAllViews();
        List<WhiteListManager.Entry> entries = WhiteListManager.load(this);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有添加家人。添加后，语音会直接播报称呼（如「大儿子来电话了」），"
                    + "勾选「自动接听」的家人来电还会自动接通。");
            empty.setTextSize(16);
            empty.setTextColor(0xFF888888);
            empty.setPadding(0, 12, 0, 12);
            mWhitelistContainer.addView(empty);
            return;
        }
        for (final WhiteListManager.Entry e : entries) {
            View row = getLayoutInflater().inflate(R.layout.item_whitelist, mWhitelistContainer, false);
            TextView nameView = (TextView) row.findViewById(R.id.item_name);
            nameView.setText(e.name + (e.number.isEmpty() ? "" : "（" + e.number + "）"));
            CheckBox cb = (CheckBox) row.findViewById(R.id.item_auto);
            cb.setChecked(e.auto);
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    WhiteListManager.setAuto(SettingsActivity.this, e.key, isChecked);
                }
            });
            Button del = (Button) row.findViewById(R.id.item_delete);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    WhiteListManager.remove(SettingsActivity.this, e.key);
                    renderWhitelist();
                }
            });
            mWhitelistContainer.addView(row);
        }
    }

    /** 各品牌后台保活设置入口，尽力跳转，失败则落到应用详情 */
    private void openBatterySettings() {
        String pkg = getPackageName();
        List<Intent> intents = new ArrayList<Intent>();
        // 小米
        intents.add(new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.powerkeeper.PowerSettings"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // 华为
        intents.add(new Intent().setComponent(new ComponentName("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // 荣耀
        intents.add(new Intent().setComponent(new ComponentName("com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // OPPO
        intents.add(new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // vivo
        intents.add(new Intent().setComponent(new ComponentName("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // 兜底：应用详情页
        intents.add(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + pkg)));

        for (Intent it : intents) {
            try {
                if (getPackageManager().resolveActivity(it, 0) != null) {
                    startActivity(it);
                    return;
                }
            } catch (Exception ignore) {}
        }
        Toast.makeText(this, "请在系统设置里允许本应用「自启动」和「后台运行」", Toast.LENGTH_LONG).show();
    }
}
