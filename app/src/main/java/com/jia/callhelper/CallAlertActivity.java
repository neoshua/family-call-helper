package com.jia.callhelper;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 全屏大按钮来电界面。
 * - 黑底白字、超大姓名、230dp 绿色「接听」圆按钮
 * - 锁屏之上显示、自动亮屏、屏幕常亮
 * - 白名单自动接听时显示倒计时
 */
public class CallAlertActivity extends Activity {

    private String mCaller;
    private boolean mVideo;
    private boolean mTest;
    private TextView mTvCaller;
    private TextView mTvType;
    private TextView mTvAuto;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private Runnable mTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 显示在锁屏之上 + 亮屏 + 常亮
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        readIntent(getIntent());
        setContentView(R.layout.activity_call_alert);

        mTvCaller = (TextView) findViewById(R.id.tv_caller);
        mTvType = (TextView) findViewById(R.id.tv_type);
        mTvAuto = (TextView) findViewById(R.id.tv_auto);
        render();

        Button accept = (Button) findViewById(R.id.btn_accept);
        Button decline = (Button) findViewById(R.id.btn_decline);
        accept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAccept();
            }
        });
        decline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDecline();
            }
        });

        CallSessionManager.setAlertActivity(this);

        // 轮询：更新自动接听倒计时；会话结束（对方挂断/已接通/超时）自动关闭
        mTick = new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                updateCountdown();
                if (!mTest) {
                    CallSessionManager.Session s = CallSessionManager.current();
                    if (s == null || s.ended) {
                        finish();
                        return;
                    }
                }
                mUi.postDelayed(this, 400L);
            }
        };
        mUi.postDelayed(mTick, 200L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        render();
    }

    private void readIntent(Intent it) {
        if (it == null) return;
        mCaller = it.getStringExtra("caller_name");
        mVideo = it.getBooleanExtra("is_video", false);
        mTest = it.getBooleanExtra("test_mode", false);
        if (mCaller == null || mCaller.trim().isEmpty()) {
            mCaller = "微信联系人";
        }
    }

    private void render() {
        mTvCaller.setText(mCaller);
        mTvType.setText(mVideo ? "视频通话" : "语音通话");
    }

    private void updateCountdown() {
        CallSessionManager.Session s = CallSessionManager.current();
        boolean show = s != null && s.autoAnswer && !s.handled && !s.ended
                && s.autoAnswerAt > System.currentTimeMillis();
        mTvAuto.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            long remain = (s.autoAnswerAt - System.currentTimeMillis()) / 1000L + 1;
            mTvAuto.setText(remain + " 秒后自动接听");
        }
    }

    private void onAccept() {
        if (mTest) {
            Toast.makeText(this, "测试结束：真实来电时会自动帮您按下微信的接听键",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Toast.makeText(this, "正在接听…", Toast.LENGTH_SHORT).show();
        CallSessionManager.performAccept(false);
        finish();
    }

    private void onDecline() {
        if (mTest) {
            finish();
            return;
        }
        CallSessionManager.performDecline();
        finish();
    }

    @Override
    protected void onDestroy() {
        CallSessionManager.clearAlertActivity(this);
        if (mTick != null) {
            mUi.removeCallbacks(mTick);
        }
        super.onDestroy();
    }
}
