package com.jia.callhelper;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 启动首页：只展示家人联系人，入口极简。
 * - 顶部温和横幅：仅当必要权限未开启时提示，点一下去设置
 * - 中部：家人列表（点一项进详情）
 * - 底部两个大按钮：添加家人 / 设置
 */
public class HomeActivity extends Activity {

    private LinearLayout mBanner;
    private LinearLayout mList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        mBanner = (LinearLayout) findViewById(R.id.banner);
        mList = (LinearLayout) findViewById(R.id.list_container);

        // 安卓 13+ 通知运行时权限（发来电提醒通知需要）
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, AddContactActivity.class));
            }
        });
        findViewById(R.id.btn_go_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
        // 横幅可点：直接进设置补权限
        mBanner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBanner();
        renderList();
    }

    private void refreshBanner() {
        int missing = PermissionStatus.countMissing(this);
        if (missing > 0) {
            mBanner.setVisibility(View.VISIBLE);
            TextView t = (TextView) mBanner.findViewById(R.id.banner_text);
            t.setText("有 " + missing + " 项必要权限未开启，来电可能无法正常提醒。点这里去设置");
        } else {
            mBanner.setVisibility(View.GONE);
        }
    }

    private void renderList() {
        mList.removeAllViews();
        List<WhiteListManager.Entry> entries = WhiteListManager.load(this);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有家人。点下方「添加家人」，添加后微信来电会大声喊出称呼并自动帮接。");
            empty.setTextSize(18);
            empty.setTextColor(0xFF888888);
            empty.setPadding(4, 24, 4, 24);
            mList.addView(empty);
            return;
        }
        for (final WhiteListManager.Entry e : entries) {
            View row = getLayoutInflater().inflate(R.layout.item_contact, mList, false);
            TextView avatar = (TextView) row.findViewById(R.id.contact_avatar);
            avatar.setText(e.name.isEmpty() ? "?" : e.name.substring(0, 1));
            TextView name = (TextView) row.findViewById(R.id.contact_name);
            name.setText(e.name);
            TextView sub = (TextView) row.findViewById(R.id.contact_sub);
            StringBuilder sb = new StringBuilder();
            if (!e.number.isEmpty()) sb.append("微信：").append(e.number);
            sb.append(e.auto ? "　· 自动接听开" : "　· 自动接听关");
            sub.setText(sb.toString());

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent it = new Intent(HomeActivity.this, ContactDetailActivity.class);
                    it.putExtra("key", e.key);
                    startActivity(it);
                }
            });
            mList.addView(row);
        }
    }
}
