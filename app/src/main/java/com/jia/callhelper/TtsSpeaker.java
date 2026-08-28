package com.jia.callhelper;

import android.content.Context;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * 语音播报（TTS）单例。
 * - 播报时自动把媒体音量临时调高到 85%，播完恢复，确保老人听得到
 * - 中文 TTS 不可用时，上层会回退为循环响铃
 */
public final class TtsSpeaker {

    private static TextToSpeech sTts;
    private static AudioManager sAm;
    private static volatile boolean sReady = false;
    private static volatile boolean sZhOk = false;
    private static int sSavedVol = -1;

    private TtsSpeaker() {}

    public static synchronized void init(Context ctx) {
        if (sTts != null) return;
        try {
            final Context app = ctx.getApplicationContext();
            sAm = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            sTts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
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
                    }
                }
            });
        } catch (Exception ignore) {}
    }

    public static synchronized boolean isUsable() {
        return sReady && sZhOk;
    }

    public static synchronized void speak(String content) {
        if (!isUsable() || sTts == null) return;
        boostVolume();
        try {
            sTts.speak(content, TextToSpeech.QUEUE_FLUSH, null, "call_announce");
        } catch (Exception ignore) {}
    }

    public static synchronized void stop() {
        if (sTts != null) {
            try { sTts.stop(); } catch (Exception ignore) {}
        }
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
