package com.jia.callhelper;

import android.content.Context;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * 语音播报（TTS）单例。
 * - 播报时自动把媒体音量临时调高到 85%，播完恢复，确保老人听得到
 * - 中文 TTS 不可用时，上层会回退为循环响铃
 * - 关键修复：TextToSpeech 初始化是异步的，本类在引擎就绪前会把播报内容排队，
 *   引擎 onInit 完成后再自动补播，避免「点了没声音」的竞态
 */
public final class TtsSpeaker {

    private static TextToSpeech sTts;
    private static AudioManager sAm;
    private static Context sApp;
    private static volatile boolean sReady = false;
    private static volatile boolean sZhOk = false;
    private static volatile String sPending = null; // 引擎加载完成前排队的待播文本
    private static int sSavedVol = -1;

    private TtsSpeaker() {}

    public static synchronized void init(Context ctx) {
        if (sTts != null) return;
        try {
            sApp = ctx.getApplicationContext();
            sAm = (AudioManager) sApp.getSystemService(Context.AUDIO_SERVICE);
            sTts = new TextToSpeech(sApp, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    synchronized (TtsSpeaker.class) {
                        sReady = status == TextToSpeech.SUCCESS;
                        if (sReady && sTts != null) {
                            int r = sTts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                            sZhOk = r != TextToSpeech.LANG_MISSING_DATA
                                    && r != TextToSpeech.LANG_NOT_SUPPORTED;
                            if (!sZhOk) {
                                try {
                                    int r2 = sTts.setLanguage(Locale.CHINA);
                                    sZhOk = r2 != TextToSpeech.LANG_MISSING_DATA
                                            && r2 != TextToSpeech.LANG_NOT_SUPPORTED;
                                } catch (Exception ignore) {}
                            }
                        }
                        // 引擎就绪后，把之前因为异步未初始化而排队的播报补上
                        if (sReady && sZhOk && sPending != null) {
                            String t = sPending;
                            sPending = null;
                            doSpeak(t);
                        }
                    }
                }
            });
        } catch (Exception ignore) {}
    }

    public static synchronized boolean isUsable() {
        return sReady && sZhOk;
    }

    /**
     * 播报。引擎未就绪会先排队、就绪后自动补播；无中文语音则丢弃（由上层回退响铃）。
     */
    public static synchronized void speak(String content) {
        if (sApp != null && sTts == null) init(sApp); // 兜底：尚未初始化则先初始化
        if (isUsable()) {
            doSpeak(content);
        } else if (sTts != null && sReady && !sZhOk) {
            // 引擎已就绪但没有中文语音：丢弃，不重复排队（上层负责响铃回退）
        } else {
            // 引擎还在加载：先排队，onInit 里会补播
            sPending = content;
        }
    }

    private static void doSpeak(String content) {
        boostVolume();
        try {
            sTts.speak(content, TextToSpeech.QUEUE_FLUSH, null, "call_announce");
        } catch (Exception ignore) {}
    }

    public static synchronized void stop() {
        if (sTts != null) {
            try { sTts.stop(); } catch (Exception ignore) {}
        }
        sPending = null;
        restoreVolume();
    }

    /** 只调高不调低；播完恢复原音量 */
    private static void boostVolume() {
        if (sAm == null || sSavedVol >= 0) return;
        try {
            sSavedVol = sAm.getStreamVolume(AudioManager.STREAM_MUSIC);
            int target = (int) (sAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.85f);
            if (target > sSavedVol) {
                sAm.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            }
        } catch (Exception ignore) {}
    }

    private static void restoreVolume() {
        if (sAm == null || sSavedVol < 0) return;
        try {
            sAm.setStreamVolume(AudioManager.STREAM_MUSIC, sSavedVol, 0);
        } catch (Exception ignore) {}
        sSavedVol = -1;
    }
}
