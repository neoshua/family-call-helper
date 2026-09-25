package com.jia.callhelper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 运行记录页：把 App 每一步的判断结果摊开给用户看。
 *
 * 来电没自动接听时，用户只要来这里看一眼，就能知道卡在哪一环：
 * 是没收到通知、还是判定成普通消息、还是不在名单里、还是能读到界面但没点中。
 * 不需要连电脑、不需要看懂技术日志。
 */
public class DiagActivity extends Activity {

    private TextView mText;
    private ScrollView mScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        CallDiag.init(this);

        mText = (TextView) findViewById(R.id.diag_text);
        mScroll = (ScrollView) findViewById(R.id.diag_scroll);

        ((Button) findViewById(R.id.btn_diag_refresh)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                render();
            }
        });
        ((Button) findViewById(R.id.btn_diag_copy)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        });
        ((Button) findViewById(R.id.btn_diag_clear)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallDiag.clear();
                CallDiag.log("记录", "已清空旧的运行记录");
                render();
                Toast.makeText(DiagActivity.this, "已清空", Toast.LENGTH_SHORT).show();
            }
        });
        render();
    }

    private void render() {
        mText.setText(CallDiag.dump());
        // 滚到最底部：最新的记录在下面
        mScroll.post(new Runnable() {
            @Override
            public void run() {
                mScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** 复制全部记录：老人家属可以把它发给帮忙排查的人 */
    private void copy() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            String ver;
            try {
                ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                ver = "未知";
            }
            cm.setPrimaryClip(ClipData.newPlainText("亲情接听助手运行记录",
                    "版本 " + ver + "\n" + CallDiag.dump()));
            Toast.makeText(this, "已复制，可以粘贴发送出去", Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {}
    }
}
