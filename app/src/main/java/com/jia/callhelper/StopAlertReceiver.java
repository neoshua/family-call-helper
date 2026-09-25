package com.jia.callhelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 「停止提醒」按钮的落点。
 *
 * 来电通知上和屏幕浮层上各有一个「停止提醒」，两处都走这里：
 * 点一下立刻停掉语音播报、铃声、震动，并撤下浮层与通知。
 *
 * 为什么必须有一个显式的关闭入口：之前的版本只有「接听/挂断」才会停声音，
 * 一旦微信那边的状态没被检测到（例如对方挂断、通知被划掉），
 * 老人就只能听着铃声一直响，没有任何办法关掉。
 */
public class StopAlertReceiver extends BroadcastReceiver {

    public static final String ACTION_STOP = "com.jia.callhelper.ACTION_STOP_ALERT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_STOP.equals(intent.getAction())) return;
        CallDiag.init(context);
        CallSessionManager.stopByUser(context);
    }
}
