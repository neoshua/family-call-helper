package com.jia.callhelper;

import android.content.Context;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;

import java.util.List;
import java.util.Locale;

/**
 * 语音播报（TTS）单例。
 * - 播报时自动把媒体音量临时调高到 85%，播完恢复，确保老人听得到
 * - 中文 TTS 不可用时，上层会回退为循环响铃
 * - 关键修复 1：TextToSpeech 初始化是异步的，本类在引擎就绪前会把播报内容排队，
 *   引擎 onInit 完成后再自动补播，避免「点了没声音」的竞态
 * - 关键修复 2：把「引擎加载中」和「引擎确实不可用」区分开。以前调用方只等 1.5 秒就
 *   下结论，而引擎冷启动常超过 1.5 秒，会把「装有 TTS 的手机」误报成「没装 TTS」。
 *   现在通过 getState() 暴露精确状态，由调用方轮询等待后再判定。
 */
public final class TtsSpeaker {

    /** 尚未确定：引擎还在异步加载（不代表不可用） */
    public static final int STATE_UNKNOWN = 0;
    /** 可用：引擎就绪且支持中文 */
    public static final int STATE_READY = 1;
    /** 引擎初始化失败（系统里没装/被禁用） */
    public static final int STATE_INIT_FAILED = 2;
    /** 引擎可用，但没有中文语音包 */
    public static final int STATE_NO_CHINESE = 3;

    private static TextToSpeech sTts;
    private static AudioManager sAm;
    private static Context sApp;
    private static volatile int sState = STATE_UNKNOWN;
    private static volatile boolean sEngineInstalled = false;
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
                        // getEngines() 是实例方法，只有拿到实例后才能查询，故放在这里
                        sEngineInstalled = hasInstalledEngine(sTts);
                        if (status != TextToSpeech.SUCCESS || sTts == null) {
                            sState = STATE_INIT_FAILED;
                            return;
                        }
                        sState = detectChinese(sTts) ? STATE_READY : STATE_NO_CHINESE;
                        // 引擎就绪后，把之前因为异步未初始化而排队的播报补上
                        if (sState == STATE_READY && sPending != null) {
                            String t = sPending;
                            sPending = null;
                            doSpeak(t);
                        }
                    }
                }
            });
        } catch (Exception e) {
            sState = STATE_INIT_FAILED;
        }
    }

    /**
     * 依次尝试多种中文 locale，任一可用即认为支持中文。
     * 注意 Locale.CHINA 与 Locale.SIMPLIFIED_CHINESE 同为 zh_CN，所以额外补上
     * Locale.CHINESE(zh) 与 zh-CN 语言标签，避免只认某一种写法的引擎被误判。
     */
    private static boolean detectChinese(TextToSpeech tts) {
        Locale[] candidates = new Locale[]{
                Locale.SIMPLIFIED_CHINESE,
                Locale.CHINESE,
                Locale.forLanguageTag("zh-CN"),
                Locale.forLanguageTag("zh"),
                Locale.CHINA
        };
        for (Locale loc : candidates) {
            try {
                int r = tts.setLanguage(loc);
                if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                    return true;
                }
            } catch (Exception ignore) {
            }
        }
        return false;
    }

    /** 系统里是否装有任何语音引擎（不看中文）。getEngines() 是实例方法，必须在实例化后调用 */
    private static boolean hasInstalledEngine(TextToSpeech tts) {
        if (tts == null) return false;
        try {
            List<TextToSpeech.EngineInfo> engines = tts.getEngines();
            return engines != null && !engines.isEmpty();
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 精确状态：STATE_UNKNOWN / READY / INIT_FAILED / NO_CHINESE */
    public static int getState() {
        return sState;
    }

    /** 仅在「引擎就绪且支持中文」时为 true；加载中或失败均为 false */
    public static boolean isUsable() {
        return sState == STATE_READY;
    }

    /** 手机里是否装了语音引擎（用于给出更准确的提示） */
    public static boolean isEngineInstalled() {
        return sEngineInstalled;
    }

    /** 面向老人的一句话原因说明，供设置页/试听提示使用 */
    public static String describeProblem() {
        switch (sState) {
            case STATE_NO_CHINESE:
                return "手机有语音引擎，但没装中文语音包，来电只能用铃声提醒。";
            case STATE_INIT_FAILED:
                return sEngineInstalled
                        ? "系统语音引擎启动失败，来电只能用铃声提醒。"
                        : "手机没有安装语音引擎，来电只能用铃声提醒。";
            case STATE_UNKNOWN:
            default:
                return "语音引擎响应太慢，本次先用铃声提醒。";
        }
    }

    /** 「去修复」的路径提示（能跳到系统 TTS 设置） */
    public static String fixPath() {
        return "请到「设置 → 辅助功能 → 文字转语音」中选择一个支持中文的引擎，并安装中文语音数据。";
    }

    /**
     * 播报。引擎未就绪会先排队、就绪后自动补播；无中文语音则丢弃（由上层回退响铃）。
     */
    public static synchronized void speak(String content) {
        if (sApp != null && sTts == null) init(sApp); // 兜底：尚未初始化则先初始化
        if (isUsable()) {
            doSpeak(content);
        } else if (sState == STATE_NO_CHINESE || sState == STATE_INIT_FAILED) {
            // 已确定不可用：丢弃，不排队（上层负责响铃回退）
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
