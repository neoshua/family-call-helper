package com.jia.callhelper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语音播报（TTS）单例。
 * - 播报时自动把媒体音量临时调高到 85%，播完恢复，确保老人听得到
 * - 中文 TTS 不可用时，上层会回退为循环响铃
 *
 * 关于「明明装了引擎却提示没装」这个问题，本类做了四层防护：
 *
 * 1.【根因】安卓 11+ 的「包可见性」限制：应用默认看不到别的应用，连系统语音引擎
 *   也看不到，导致绑定引擎失败 / 查不到引擎列表。必须在 AndroidManifest 里声明
 *   <queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>
 *   否则 targetSdk >= 30 的 App 在小米等机型上会直接报「没有可用的语音引擎」。
 *
 * 2. 系统默认引擎连不上时，自动改用手机里其它语音引擎（逐个尝试），
 *    避免「默认引擎被禁用/损坏 → 整个语音播报失效」。
 *
 * 3. 语言探测（setLanguage）只是参考，不是判决。部分厂商引擎对 zh_CN 的返回值
 *    不准确，所以探测不通过时，会真的说一句探测语，用 UtteranceProgressListener
 *    的 onStart 回调来确认「引擎确实出声了」，出声了就认定可用。
 *
 * 4. 初始化是异步的，引擎加载完成前排队的播报会在 onInit 后自动补播，避免
 *    「点了没声音」的竞态；调用方通过 getState() 轮询，不再用固定 1.5 秒误判。
 */
public final class TtsSpeaker {

    /** 尚未确定：引擎还在异步加载（不代表不可用） */
    public static final int STATE_UNKNOWN = 0;
    /** 可用：引擎就绪且能播中文 */
    public static final int STATE_READY = 1;
    /** 引擎初始化失败（系统里没装/被系统限制） */
    public static final int STATE_INIT_FAILED = 2;
    /** 引擎可用，但语言探测认为没有中文语音包（会用探测语再确认） */
    public static final int STATE_NO_CHINESE = 3;

    /** 探测语：引擎真的念出这句，就说明语音播报可用 */
    private static final String PROBE_TEXT = "语音播报已经打开";
    private static final String PROBE_UTTERANCE_ID = "tts_probe";

    private static TextToSpeech sTts;
    private static AudioManager sAm;
    private static Context sApp;
    private static volatile int sState = STATE_UNKNOWN;
    private static volatile boolean sInitDone = false;
    private static volatile boolean sSpeakVerified = false;
    private static volatile String sEnginePkg = null;
    private static volatile String sPending = null; // 引擎加载完成前排队的待播文本
    private static volatile String sLastError = "";
    private static int sSavedVol = -1;

    /** 待尝试的语音引擎包名（第一个是系统默认引擎） */
    private static List<String> sCandidates = new ArrayList<String>();
    private static int sNextCandidate = 0;

    private TtsSpeaker() {}

    // ---------------- 对外接口 ----------------

    public static synchronized void init(Context ctx) {
        if (sTts != null) return;
        try {
            sApp = ctx.getApplicationContext();
            if (sAm == null) sAm = (AudioManager) sApp.getSystemService(Context.AUDIO_SERVICE);
            if (sCandidates.isEmpty()) {
                sCandidates = listEnginePackages(sApp);
            }
            sNextCandidate = 0;
            createTts(null); // null = 交给系统选默认引擎
        } catch (Throwable t) {
            sLastError = describe(t);
            sState = STATE_INIT_FAILED;
        }
    }

    /**
     * 彻底重连一次（设置页「重新检测」用）。
     * 上一次失败的状态会被清掉，避免「用户已经在系统里改好了，App 却还记着旧结果」。
     */
    public static synchronized void recheck(Context ctx) {
        try {
            if (sTts != null) sTts.shutdown();
        } catch (Throwable ignore) {}
        sTts = null;
        sState = STATE_UNKNOWN;
        sInitDone = false;
        sSpeakVerified = false;
        sCandidates = new ArrayList<String>();
        sNextCandidate = 0;
        sLastError = "";
        init(ctx);
    }

    /** 精确状态：STATE_UNKNOWN / READY / INIT_FAILED / NO_CHINESE */
    public static int getState() {
        return sState;
    }

    /** 仅在「引擎就绪且支持中文」时为 true；加载中或失败均为 false */
    public static boolean isUsable() {
        return sState == STATE_READY;
    }

    /** 引擎是否真的念出过声音（onStart 回调），最可靠的可用性证据 */
    public static boolean isSpeakVerified() {
        return sSpeakVerified;
    }

    /** 当前使用的语音引擎包名，用于排查问题 */
    public static String getEnginePackage() {
        return sEnginePkg;
    }

    /** 手机里检测到的语音引擎数量 */
    public static int getEngineCount() {
        return sCandidates.size();
    }

    /** 面向老人的一句话原因说明，供设置页/试听提示使用 */
    public static String describeProblem() {
        switch (sState) {
            case STATE_NO_CHINESE:
                return "手机的语音引擎没能读出中文，来电只能用铃声提醒。";
            case STATE_INIT_FAILED:
                return "本应用没能连上手机的语音引擎，来电只能用铃声提醒。";
            case STATE_UNKNOWN:
            default:
                return "语音引擎响应太慢，本次先用铃声提醒。";
        }
    }

    /** 诊断信息：出问题时贴给开发者看，便于定位是哪一步被系统挡了 */
    public static String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("引擎 ").append(sEnginePkg == null ? "（未知）" : sEnginePkg);
        sb.append("，共检测到 ").append(sCandidates.size()).append(" 个");
        if (sSpeakVerified) sb.append("，已确认能出声");
        if (sLastError.length() > 0) sb.append("，错误：").append(sLastError);
        return sb.toString();
    }

    /** 「去修复」的路径提示（能跳到系统 TTS 设置） */
    public static String fixPath() {
        return "请到「设置 → 更多设置 → 无障碍 → 文字转语音」中选择支持中文的引擎。";
    }

    /**
     * 播报。引擎未就绪会先排队、就绪后自动补播；确认无引擎则丢弃（由上层回退响铃）。
     */
    public static synchronized void speak(String content) {
        if (sApp != null && sTts == null) init(sApp); // 兜底：尚未初始化则先初始化
        if (sState == STATE_INIT_FAILED && sTts == null) return; // 确实没有引擎，交给上层响铃
        if (sTts == null || !sInitDone) {
            sPending = content; // 引擎还在加载：先排队，onInit 里会补播
            return;
        }
        playPendingOr(content);
    }

    /** 主动说一句探测语，用真实发声来确认引擎可用（设置页「重新检测 / 试听」用） */
    public static synchronized void probe() {
        if (sTts == null) return;
        try {
            boostVolume();
            sTts.speak(PROBE_TEXT, TextToSpeech.QUEUE_FLUSH, null, PROBE_UTTERANCE_ID);
        } catch (Throwable t) {
            sLastError = describe(t);
        }
    }

    public static synchronized void stop() {
        if (sTts != null) {
            try { sTts.stop(); } catch (Exception ignore) {}
        }
        sPending = null;
        restoreVolume();
    }

    // ---------------- 内部实现 ----------------

    private static final TextToSpeech.OnInitListener sInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            synchronized (TtsSpeaker.class) {
                if (status != TextToSpeech.SUCCESS || sTts == null) {
                    // 默认引擎连不上：逐个试手机里其它语音引擎，
                    // 避免「默认引擎被禁用/损坏」导致整个语音播报失效
                    String next = nextEngine();
                    if (next != null) {
                        try { if (sTts != null) sTts.shutdown(); } catch (Throwable ignore) {}
                        sTts = null;
                        createTts(next);
                        return;
                    }
                    sLastError = "onInit(" + status + ")";
                    sState = STATE_INIT_FAILED;
                    return;
                }

                sInitDone = true;
                sEnginePkg = safeDefaultEngine();
                attachListener();
                sState = detectChinese(sTts) ? STATE_READY : STATE_NO_CHINESE;

                if (sState == STATE_READY) {
                    flushPending();
                } else {
                    // 语言探测只是参考。有些厂商引擎对 zh_CN 的返回值不准，
                    // 所以这里真说一句探测语：onStart 回调到了就改判为可用。
                    probe();
                }
            }
        }
    };

    /** 新建 TTS 实例；enginePkg 为 null 时用系统默认引擎 */
    private static void createTts(String enginePkg) {
        try {
            sEnginePkg = enginePkg;
            sTts = enginePkg == null
                    ? new TextToSpeech(sApp, sInitListener)
                    : new TextToSpeech(sApp, sInitListener, enginePkg);
        } catch (Throwable t) {
            sLastError = describe(t);
            sTts = null;
            sState = STATE_INIT_FAILED;
        }
    }

    /** 取下一个待尝试的引擎包名（跳过已经试过的） */
    private static String nextEngine() {
        while (sNextCandidate < sCandidates.size()) {
            String p = sCandidates.get(sNextCandidate++);
            if (p != null && !p.equals(sEnginePkg)) return p;
        }
        return null;
    }

    /**
     * 挂播报进度回调：只要引擎真的开始念（onStart），就认定可用。
     * 这是最可靠的判据——比任何语言探测都准。
     */
    private static void attachListener() {
        try {
            sTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    synchronized (TtsSpeaker.class) {
                        sSpeakVerified = true;
                        if (sState != STATE_READY) {
                            // 引擎确实出声了 → 修正为可用，并补播之前被扣下的内容
                            sState = STATE_READY;
                            flushPending();
                        }
                    }
                }

                @Override
                public void onDone(String utteranceId) {
                    // 播报正常结束，无需处理
                }

                @Override
                public void onError(String utteranceId) {
                    if (PROBE_UTTERANCE_ID.equals(utteranceId)) {
                        synchronized (TtsSpeaker.class) {
                            sLastError = "probe onError";
                        }
                    }
                }
            });
        } catch (Throwable ignore) {
            // 个别老机型不支持进度回调，不影响主流程
        }
    }

    /** 把排队的播报内容补播出去 */
    private static void flushPending() {
        if (sPending == null) return;
        String t = sPending;
        sPending = null;
        doSpeak(t);
    }

    private static void playPendingOr(String content) {
        if (sPending != null) {
            // 已有排队内容，按队列顺序播（QUEUE_FLUSH 会覆盖，这里取最新一条即可）
            sPending = null;
        }
        doSpeak(content);
    }

    private static void doSpeak(String content) {
        boostVolume();
        try {
            sTts.speak(content, TextToSpeech.QUEUE_FLUSH, null, "call_announce");
        } catch (Throwable t) {
            sLastError = describe(t);
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
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

    private static String safeDefaultEngine() {
        try {
            String p = sTts.getDefaultEngine();
            if (p != null) return p;
        } catch (Throwable ignore) {}
        return sEnginePkg;
    }

    /**
     * 列出手机里的语音引擎包名，系统默认引擎排第一。
     *
     * 这里依赖 AndroidManifest 里的 <queries> 声明 TTS_SERVICE，
     * 否则安卓 11+ 的包可见性限制会让这个方法返回空列表。
     */
    private static List<String> listEnginePackages(Context ctx) {
        List<String> out = new ArrayList<String>();
        try {
            // 系统当前选定的默认引擎（读系统设置，任何应用都可读）
            String def = null;
            try {
                def = Settings.Secure.getString(ctx.getContentResolver(),
                        Settings.Secure.TTS_DEFAULT_SYNTH);
            } catch (Throwable ignore) {}
            if (def != null && def.length() > 0) out.add(def);

            // 所有声明了 TTS_SERVICE 的引擎，按系统设置里的展示顺序
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            PackageManager pm = ctx.getPackageManager();
            List<ResolveInfo> list = pm.queryIntentServices(intent, 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    ServiceInfo si = ri.serviceInfo;
                    if (si == null || si.packageName == null) continue;
                    if (!out.contains(si.packageName)) out.add(si.packageName);
                }
            }
            // 兜底：从引擎自身接口再取一遍
            if (sTts != null) {
                List<TextToSpeech.EngineInfo> infos = sTts.getEngines();
                if (infos != null) {
                    for (TextToSpeech.EngineInfo ei : infos) {
                        if (ei != null && ei.name != null && !out.contains(ei.name)) out.add(ei.name);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
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
