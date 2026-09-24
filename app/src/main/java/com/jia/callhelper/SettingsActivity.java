package com.jia.callhelper;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页（从首页「⚙ 设置」进入）：集中管理权限、试听、音量、自动接听、后台运行。
 * 不再堆砌白名单录入，录入移到了首页「添加家人」。
 */
public class SettingsActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 1;

    private TextView mStatusNotif;
    private TextView mStatusAcc;
    private TextView mStatusOverlay;
    private TextView mStatusFsi;
    private SeekBar mVolume;
    private TextView mVolumePct;
    private Switch mAutoMaster;
    private EditText mDelay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mStatusNotif = (TextView) findViewById(R.id.status_notification);
        mStatusAcc = (TextView) findViewById(R.id.status_accessibility);
        mStatusOverlay = (TextView) findViewById(R.id.status_overlay);
        mStatusFsi = (TextView) findViewById(R.id.status_fullscreen);
        mVolume = (SeekBar) findViewById(R.id.seek_volume);
        mVolumePct = (TextView) findViewById(R.id.tv_volume_pct);
        mAutoMaster = (Switch) findViewById(R.id.sw_auto_master);
        mDelay = (EditText) findViewById(R.id.input_auto_delay);
        mDelay.setText(String.valueOf(WhiteListManager.prefs(this)
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
                startTest();
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

        // 媒体音量（语音播报走媒体通道）
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        mVolume.setMax(max);
        mVolume.setProgress(cur);
        updateVolumePct(cur, max);
        mVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                updateVolumePct(progress, max);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 自动接听总开关（默认关）
        mAutoMaster.setChecked(WhiteListManager.prefs(this).getBoolean("auto_answer_master", false));
        mAutoMaster.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                WhiteListManager.prefs(SettingsActivity.this).edit()
                        .putBoolean("auto_answer_master", isChecked).apply();
                Toast.makeText(SettingsActivity.this,
                        isChecked ? "自动接听已开启（仅对标记自动接听的家人生效）" : "自动接听已关闭",
                        Toast.LENGTH_SHORT).show();
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

    private void updateVolumePct(int cur, int max) {
        int pct = max > 0 ? cur * 100 / max : 0;
        mVolumePct.setText("当前媒体音量：" + pct + "%");
    }

    private void startTest() {
        TtsSpeaker.init(SettingsActivity.this);
        CallSessionManager.startTestCall(SettingsActivity.this, "张三", true);
        // 1.5 秒后若仍无中文语音引擎，提示去装中文 TTS
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!TtsSpeaker.isUsable()) {
                    Toast.makeText(SettingsActivity.this,
                            "未检测到中文语音引擎，已用铃声代替。\n请在「设置 → 辅助功能 → 文字转语音(TTS)」中"
                                    + "安装并选择中文引擎（如系统自带或讯飞），即可语音播报。",
                            Toast.LENGTH_LONG).show();
                }
            }
        }, 1500L);
        Intent it = new Intent(SettingsActivity.this, CallAlertActivity.class);
        it.putExtra("caller_name", "张三（测试）");
        it.putExtra("is_video", true);
        it.putExtra("test_mode", true);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
    }

    private void saveDelay() {
        int d;
        try {
            d = Integer.parseInt(mDelay.getText().toString().trim());
        } catch (NumberFormatException e) {
            d = CallSessionManager.DEFAULT_AUTO_DELAY_SEC;
        }
        if (d < 3) d = 3;
        if (d > 60) d = 60;
        mDelay.setText(String.valueOf(d));
        WhiteListManager.prefs(this).edit().putInt("auto_delay_sec", d).apply();
        Toast.makeText(this, "已保存：自动接听前等待 " + d + " 秒", Toast.LENGTH_SHORT).show();
    }

    private void bind(int id, View.OnClickListener l) {
        Button b = (Button) findViewById(id);
        if (b != null) b.setOnClickListener(l);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ---------------- 权限状态展示 ----------------

    private void refreshStatus() {
        setStatus(mStatusNotif, PermissionStatus.isNotificationListener(this),
                "✓ 已开启（可以监听微信来电了）", "✗ 未开启，点下方按钮去开启");
        setStatus(mStatusAcc, PermissionStatus.isAccessibility(this),
                "✓ 已开启（可以自动帮您点微信的接听键）", "✗ 未开启：只能播报和显示大按钮，无法自动接听");

        View rowOverlay = findViewById(R.id.row_overlay);
        if (Build.VERSION.SDK_INT >= 23) {
            rowOverlay.setVisibility(View.VISIBLE);
            setStatus(mStatusOverlay, PermissionStatus.isOverlay(this),
                    "✓ 已允许（来电时能弹出大按钮界面）", "✗ 未允许，来电时界面可能弹不出来");
        } else {
            rowOverlay.setVisibility(View.GONE);
        }

        View rowFsi = findViewById(R.id.row_fullscreen);
        if (Build.VERSION.SDK_INT >= 29) {
            rowFsi.setVisibility(View.VISIBLE);
            if (Build.VERSION.SDK_INT >= 33) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                boolean fsiOk = nm != null && nm.canUseFullScreenIntent();
                setStatus(mStatusFsi, fsiOk,
                        "✓ 已允许（锁屏来电也能弹大按钮界面）", "✗ 未允许：请为本应用和微信都打开「全屏通知」");
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

    /** 各品牌后台保活设置入口，尽力跳转，失败则落到应用详情 */
    private void openBatterySettings() {
        String pkg = getPackageName();
        List<Intent> intents = new ArrayList<Intent>();
        intents.add(new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.powerkeeper.PowerSettings")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        intents.add(new Intent().setComponent(new ComponentName("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        intents.add(new Intent().setComponent(new ComponentName("com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        intents.add(new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        intents.add(new Intent().setComponent(new ComponentName("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
