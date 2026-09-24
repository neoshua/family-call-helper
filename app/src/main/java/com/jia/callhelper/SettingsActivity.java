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
    private TextView mStatusTts;
    private SeekBar mVolume;
    private TextView mVolumePct;
    private Switch mAutoMaster;
    private EditText mDelay;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mStatusNotif = (TextView) findViewById(R.id.status_notification);
        mStatusAcc = (TextView) findViewById(R.id.status_accessibility);
        mStatusOverlay = (TextView) findViewById(R.id.status_overlay);
        mStatusFsi = (TextView) findViewById(R.id.status_fullscreen);
        mStatusTts = (TextView) findViewById(R.id.status_tts);
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
        bind(R.id.btn_open_tts, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 跳到系统「文字转语音」设置，让用户选引擎 / 装中文语音包。
                // 注意：Android SDK 里没有 Settings.ACTION_TTS_SETTINGS 这个常量，
                // TTS 设置页只暴露了系统内部 action 字符串，所以这里用字面量并逐级兜底。
                if (openTtsSettings()) return;
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(SettingsActivity.this,
                            TtsSpeaker.fixPath(), Toast.LENGTH_LONG).show();
                }
            }
        });
        bind(R.id.btn_recheck_tts, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 用户在系统里改了引擎设置后，点这里强制重连一次并重新判定，
                // 避免 App 一直记着上一次的失败结论
                TtsSpeaker.recheck(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this, "正在重新检测…", Toast.LENGTH_SHORT).show();
                pollTtsStatus(0);
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

        // 安卓 13+ 通知运行时权限（发来电提醒通知需要）
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS);
            }
        }

        // 语音引擎自检：引擎是异步加载的，这里轮询更新状态，
        // 避免「引擎还没加载完就被误判成没装 TTS」
        TtsSpeaker.init(this);
        pollTtsStatus(0);

        // 底部显示真实版本号（直接读安装包信息，不再写死，
        // 避免「App 里显示 1.1.0、下载的包却叫 v1.5」这种对不上的情况）
        TextView about = (TextView) findViewById(R.id.tv_about);
        if (about != null) {
            about.setText("版本 " + appVersion() + "\n"
                    + "亲情接听助手 · 仅在本地运行，不联网、不上传数据");
        }
    }

    /** 读取本应用真实的版本号（versionName），失败时返回 "未知" */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    private static final int TTS_POLL_MAX = 16; // 16 × 500ms = 最多等 8 秒

    /** 轮询语音引擎状态，就绪或超时后停止 */
    private void pollTtsStatus(final int n) {
        updateTtsStatus();
        if (TtsSpeaker.isUsable() || n >= TTS_POLL_MAX) return;
        // 到了 2 秒、5 秒还没结论，就主动说一句探测语：
        // 引擎真的出声（onStart 回调）就改判为可用，比语言探测可靠得多。
        // 注意：已经判定可用时不再探测，否则会把正在播的试听内容打断。
        if ((n == 4 || n == 10)
                && TtsSpeaker.getState() != TtsSpeaker.STATE_READY
                && !TtsSpeaker.isSpeakVerified()) {
            TtsSpeaker.probe();
        }
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollTtsStatus(n + 1);
            }
        }, 500L);
    }

    /** 把语音引擎状态渲染到设置页（常驻，随时可自查） */
    private void updateTtsStatus() {
        if (mStatusTts == null) return;
        boolean verbose = false;
        switch (TtsSpeaker.getState()) {
            case TtsSpeaker.STATE_READY:
                mStatusTts.setText("✓ 语音播报正常，来电会念出「谁来电了」");
                mStatusTts.setTextColor(0xFF1B7F3B);
                break;
            case TtsSpeaker.STATE_NO_CHINESE:
                mStatusTts.setText("✗ " + TtsSpeaker.describeProblem() + "\n" + TtsSpeaker.fixPath());
                mStatusTts.setTextColor(0xFFB3261E);
                verbose = true;
                break;
            case TtsSpeaker.STATE_INIT_FAILED:
                mStatusTts.setText("✗ " + TtsSpeaker.describeProblem() + "\n" + TtsSpeaker.fixPath());
                mStatusTts.setTextColor(0xFFB3261E);
                verbose = true;
                break;
            default:
                mStatusTts.setText("正在检测语音引擎…（首次可能要几秒）");
                mStatusTts.setTextColor(0xFF666666);
                break;
        }
        // 出错时附上诊断信息（引擎包名 / 检测到几个引擎），方便排查是系统限制还是真没装
        if (verbose) {
            mStatusTts.append("\n");
            mStatusTts.append("检测情况：" + TtsSpeaker.diagnostics());
        }
    }

    private void updateVolumePct(int cur, int max) {
        int pct = max > 0 ? cur * 100 / max : 0;
        mVolumePct.setText("当前媒体音量：" + pct + "%");
    }

    private void startTest() {
        TtsSpeaker.init(SettingsActivity.this);
        // 只播报一句「张三来视频电话了」，不弹任何界面。
        // （以前会弹一个全屏测试来电界面，但那个界面上的接听键是假的，已整体去掉）
        CallSessionManager.startTestCall(SettingsActivity.this, "张三", true);
        // 轮询等待语音引擎，好让下面的状态卡给出准确结论：
        // 引擎冷启动常超过 1.5 秒，不等就下结论会误报「没装 TTS」。
        waitTtsForTest(0);
    }

    /** 试听时的引擎等待：最多 8 秒，用来给状态卡一个准确结论 */
    private void waitTtsForTest(final int n) {
        updateTtsStatus();
        if (TtsSpeaker.isUsable()) return;
        if (n >= TTS_POLL_MAX) {
            if (TtsSpeaker.isUsable()) return;
            updateTtsStatus();
            Toast.makeText(SettingsActivity.this,
                    "这次没能用语音播报。本页下面的「检测情况」可看到原因。",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // 中途用一句探测语验证引擎是否真的能出声（已判定可用时不打断播报）
        if ((n == 4 || n == 10)
                && TtsSpeaker.getState() != TtsSpeaker.STATE_READY
                && !TtsSpeaker.isSpeakVerified()) {
            TtsSpeaker.probe();
        }
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                waitTtsForTest(n + 1);
            }
        }, 500L);
    }

    private void saveDelay() {
        String raw = mDelay.getText().toString().trim();
        if (raw.isEmpty()) {
            // 输入框不能为空：报错并回填已保存的值，不允许存空
            int saved = WhiteListManager.prefs(this)
                    .getInt("auto_delay_sec", CallSessionManager.DEFAULT_AUTO_DELAY_SEC);
            mDelay.setText(String.valueOf(saved));
            mDelay.requestFocus();
            mDelay.selectAll();
            Toast.makeText(this, "等待秒数不能为空，已恢复为 " + saved + " 秒", Toast.LENGTH_SHORT).show();
            return;
        }
        int d;
        try {
            d = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            d = CallSessionManager.DEFAULT_AUTO_DELAY_SEC;
        }
        if (d < 3) d = 3;
        if (d > 60) d = 60;
        mDelay.setText(String.valueOf(d));
        WhiteListManager.prefs(this).edit().putInt("auto_delay_sec", d).apply();
        Toast.makeText(this, "已保存：自动接听前等待 " + d + " 秒", Toast.LENGTH_SHORT).show();
    }

    /**
     * 打开系统「文字转语音」设置页。
     *
     * Android SDK 并未公开 TTS 设置页的常量（没有 Settings.ACTION_TTS_SETTINGS），
     * 只能用系统内部的 action 字符串，且各厂商 ROM 命名不一，所以依次尝试，
     * 全部打不开时返回 false，由调用方退到「无障碍」设置并给出文字指引。
     */
    private boolean openTtsSettings() {
        String[] actions = new String[]{
                "com.android.settings.TTS_SETTINGS",
                "com.android.settings.TEXT_TO_SPEECH_SETTINGS",
                "android.settings.TTS_SETTINGS"
        };
        for (String action : actions) {
            try {
                Intent it = new Intent(action);
                if (getPackageManager().resolveActivity(it, 0) == null) continue;
                startActivity(it);
                return true;
            } catch (Exception ignore) {
            }
        }
        return false;
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
                "✓ 已开启（能发现微信来电）", "✗ 未开启，点下方按钮去开启");
        setStatus(mStatusAcc, PermissionStatus.isAccessibility(this),
                "✓ 已开启（能自动帮您点微信的接听键）", "✗ 未开启：只能播报提醒，无法自动接听");

        View rowOverlay = findViewById(R.id.row_overlay);
        if (Build.VERSION.SDK_INT >= 23) {
            rowOverlay.setVisibility(View.VISIBLE);
            setStatus(mStatusOverlay, PermissionStatus.isOverlay(this),
                    "✓ 已允许（来电时能把微信通话界面调到最前面，接听更容易成功）",
                    "✗ 未允许：锁屏来电时微信界面可能调不到前台，自动接听容易失败");
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
                        "✓ 已允许（锁屏来电也能亮屏、直达微信接听）",
                        "✗ 未允许：请为本应用打开「全屏通知」，否则锁屏来电可能不亮屏");
            } else {
                setStatus(mStatusFsi, true,
                        "默认开启；若锁屏来电不亮屏，请检查系统「全屏通知」设置", "");
            }
        } else {
            rowFsi.setVisibility(View.GONE);
        }

        updateTtsStatus();
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
