# 亲情接听助手 · 开发手册

> **这份文档给谁看**：给下一个接手这个项目的 AI agent 或开发者。
> 读完你应该能：知道每个类是干什么的、知道改动前必须避开哪些雷、知道怎么编译出包、知道用户报告"没自动接听"时该看哪里。
>
> 代码里有大量中文注释解释「为什么这么写」，那些注释是**踩过的坑的一手记录**。
> 本手册是它们的索引。**改任何一处之前，请先读那一段注释。**

---

## 0. 三分钟上手

```
app/src/main/java/com/jia/callhelper/    # 全部 Java 源码（18 个类，约 6600 行）
├── CallSessionManager.java              # ★ 大脑：一次来电的完整状态机
├── CallHelperAccessibilityService.java  # ★ 眼睛+手：读微信界面、点接听键
├── WeChatClicker.java                   # ★ 重试链：反复点，直到确认接通
├── WeChatCallListenerService.java       # ★ 耳朵：通知栏监听，发现来电
├── GuideOverlay.java                    # 屏幕上给老人看的绿圈 / 提示 / 停止按钮
├── CalibrationOverlay.java              # 让用户自己拖绿圈的校准浮层
├── AnswerPointPrefs.java                # 接听键坐标（存的比例）
├── TtsSpeaker.java                      # 语音播报
├── SpeakScript.java                     # 播报文案（可自定义）
├── WhiteListManager.java                # 家人名单 + 来电人名匹配
├── CallDiag.java                        # 运行记录（排查唯一依据）
├── PermissionStatus.java                # 权限判定
├── HomeActivity / SettingsActivity / AddContactActivity /
    ContactDetailActivity / SpeakScriptActivity / DiagActivity   # 界面层
└── StopAlertReceiver.java               # 「停止提醒」按钮落点
```

**一句话理解这个 App 干什么**：
监听微信来电通知 → 开一个会话，语音播报"XX 来视频电话了" → 在屏幕上圈出微信那个绿色接听按钮 →
如果这个人在名单里且开了自动接听，等 N 秒后用**无障碍手势**替用户点那个绿按钮 → 接上了就闭嘴。

**最关键的技术约束（背下来）**：
1. **不能联网**。这是个给老人用的隐私向工具，任何网络请求都不许加。
2. **必须全自动**。使用者是不会操作手机的老人，**任何"请用户点一下"的设计都是缺陷**。
3. **不额外响铃**。微信来电本身就在响，我们再叠一个铃声只会吵。
4. 微信的界面是 **整页自绘** 的，无障碍经常一个节点都读不到 —— 本项目 80% 的复杂度都来自这一条。

---

## 1. 构建与发布

### 1.1 本地编译

```bash
cd /workspace/wechat-call-helper
BT=/tmp/bt34 JAR=/tmp/android34.jar \
  VERSION_NAME=1.20 VERSION_CODE=28 \
  bash tools/build_apk.sh
```

产出 `dist/亲情接听助手-v1.20.apk`（已 zipalign + 签名）。

工具链缺失时环境变量怎么填：

| 变量 | 含义 | 典型值 |
|---|---|---|
| `BT` | Android build-tools 目录（要有 `aapt2`/`d8`/`zipalign`/`apksigner`） | `/tmp/bt34` |
| `JAR` | `android.jar`（SDK 34） | `/tmp/android34.jar` |
| `VERSION_NAME` | 版本名，同时写进清单与 APK 文件名 | `1.20` |
| `VERSION_CODE` | 版本号 | `28` |

> **版本号的对应规则：`versionCode = 小版本号 + 8`**（`1.20 ↔ 28`）。见 §12.1 的来历，别写错。

### 1.2 构建脚本里几个不能动的细节

这些每一条都是真踩过的坑，改 `tools/build_apk.sh` 前请读脚本内的注释：

- **版本号必须注入清单副本**，不能只靠 `aapt2 --version-code`。清单里已写 `versionCode/versionName` 时清单属性优先级更高，会覆盖命令行参数 → 出现「包名叫 v1.6，装完显示 1.1.0」。
- **每次必须清空 `build/obj`、`build/gen`、`build/dex`**。残留的 `.class` 会被 d8 一起打进 dex，把删掉的死代码又塞回安装包。
- **必须清空 `dist/*.apk`**。CI 若用"取第一个 apk"发布，旧包会被当成新包发出去。
- `javac` 用 **`-classpath`** 而不是 `-bootclasspath`（JDK 9+ 已忽略后者，部分 JDK 上还会屏蔽 `java.lang.String`，满屏 `cannot find symbol`）。
- 校验收尾时禁止 `aapt2 dump badging ... | head -8`：head 读够就退出，aapt2 收 SIGPIPE（退出码 141），在 `set -o pipefail` 下整个脚本判失败 —— 包其实是好的。脚本里改成先收进变量再 head。
- debug 签名：`tools/keystore.jks`（alias `callhelper`，口令 `callhelper2024`），脚本缺失时会自动生成。**别把它当发布 key 泄露出去。**

### 1.3 CI 与发布

`.github/workflows/build.yml`，push 到 `main`/`master` 触发，构建后自动发布到 Release 的 `latest`。

> CI 里刻意不用 `android-actions/setup-android@v3`：它被强制跑在 Node 24 上，近期常在安装阶段直接失败，会把后续编译全部跳过。现在直接用 runner 预装 SDK。

---

## 2. 一条来电的完整数据流

```
微信来电
  │
  ├─[通道 A] NotificationListenerService            WeChatCallListenerService
  │     判定"这是来电邀请"（不是普通消息）
  │     → CallSessionManager.startCall(caller, video, openIntent)
  │
  └─[通道 B] AccessibilityService 周期性扫描         CallHelperAccessibilityService
        识别到"邀请你视频通话"等特征
        → CallSessionManager.onIncomingViaA11y(...)

                            ▼
              CallSessionManager.startCall()   ← 唯一的会话入口
                            │
        ┌───────────────────┼────────────────────┐
        │                   │                    │
   语音播报             屏幕指引              自动接听（名单 && 总开关）
  TtsSpeaker          GuideOverlay             等 N 秒后 performAccept()
                                                     │
                                        CallSessionManager.ensureFullScreenStep()
                                        （每轮：拉起界面 + 点一次，最多 8 轮）
                                                     │
                                             WeChatClicker.answerWithRetry()
                                                     │
                                      CallHelperAccessibilityService.answerCall()
                                        → dispatchGesture 真实点坐标
                                                     │
                                             确认已接通 → markAnswered()
                                                     │
                                        cleanup()：停声、撤浮层、撤通知
```

**会话的三种终态**：`markAnswered()`（接通了）/ `markEnded()`（结束了）/ 用户点「停止提醒」。
三者最终都走 `cleanup(Context)` —— **它会把 Session.ended 置 true，并 removeCallbacks 掉所有排队的定时任务**。

---

## 3. 核心状态机：CallSessionManager

这是全项目最容易改坏的地方。一次来电 = 一个 `Session` 对象 + 一组静态 Handler 任务。

### 3.1 Session 的关键字段

| 字段 | 含义 | 谁在读 / 决定什么 |
|---|---|---|
| `caller` | 微信显示的名字（匹配名单前的原值） | 决定是否要重建会话 |
| `displayName` | 播报时用的称呼（名单命中则为配置名） | TTS |
| `autoAnswer` / `autoAnswerAt` | 是否自动接听 / 到点时刻 | `sAutoRun` 的触发 |
| `handled` | 接听流程**已启动**（不是"已接上"！） | 去重用；也是"证据不足别判结束"的判据 |
| `answered` | 已确认接通 | 停止播报的判据 |
| `ended` | 会话已结束（终态） | 所有 Runnable 的早退判据 |
| `fullScreenSeen` | 是否出现过全屏来电界面 | 决定指引是"只提示"还是"画圈" |
| `wechatUiSeen` / `goneTicks` | 见过来电界面吗 / 连续几次不见了 | 判"界面消失"（对方挂断） |
| `weakEvidenceTicks` | **v1.20** 证据不足时的宽限次数 | `allowEndOnWeakEvidence()` |
| `stoppedByUser` | 用户按了停止 | — |

> ⚠️ **`handled` 曾经一度被当成"停止播报"的条件，这是错的**，它只表示"流程启动过"。
> 历史 bug：有了它，自动接听进行中反而不画圈（`!s.handled` 拦住了），
> 结果自动化失灵时老人既没接上、也没圈可点。详见代码内 v1.14 注释。

### 3.2 定时任务的账（改动前必须理解）

| Runnable | 间隔 / 延时 | 作用 |
|---|---|---|
| `sAnnounceLoop` | 2500ms，最多 `MAX_ANNOUNCE=30` 次 | 反复播报 |
| `sWatchdog` | 1500ms | 判接通 / 判界面消失 / 升级指引 |
| `sAutoRun` | 一次性（`auto_delay_sec`，默认 8 秒） | 到点启动 `performAccept()` |
| `sEnsureFullScreen` | 由上层回调驱动，`PULL_INTERVAL_MS=1500` 兜底 | 一轮"拉起 + 点击" |
| `sClickWatchdog` | 一次性 `CLICK_CHAIN_BUDGET_MS=11000` | 内层点击链卡住时兜底推下一轮 |
| `sRetryAccept` | 一次性 `RETRY_AFTER_FAIL_MS=4000` | 失败重试，最多 `MAX_ACCEPT_RETRY=4` |
| `sNotifyGoneConfirm` | 一次性 2500ms | 通知消失的二次确认 |
| `sTimeout` | 一次性 `HARD_TIMEOUT_MS=120000` | 会话生命周期上限 |

**`HARD_TIMEOUT_MS` 管的是"会话活多久"，不是"吵多久"** —— 吵多久由 `MAX_ANNOUNCE` 控制。
这个值必须足够大：开场延时 + 8 轮 ×(1.5s + 2.5s) ≈ 32s，再算上 4 次失败重试轻松破 100 秒。
90 秒时曾出现"重试跑不完就被揪掉"，**v1.20 提到 120 秒**。

---

## 4. 微信来电界面：三种形态 + "读不到界面"

微信来电在屏幕上有三种形态，**只有第 ① 种上面才有接听键**：

| 形态 | 说明 | 能否点接听键 |
|---|---|---|
| ① 全屏来电界面 | 整个屏幕是微信通话页，底部一红一绿两个圆钮 | ✅ 唯一能点的形态 |
| ② 顶部横幅（heads-up） | 屏幕顶部一条通知 banner，几秒后自动收起 | ❌ |
| ③ 下拉通知栏里的通知 | 要手动下拉才看得到 | ❌ |

②/③ 必须先把全屏界面拉起来：`ensureFullScreenStep()` 会依次尝试
① 发通知的 `contentIntent` ② 启动微信 ③ 模拟上滑 ④ 点一下顶部横幅。

### 4.1 「整页自绘」——本项目最大的麻烦

**微信 8.0.x 的通话界面是自绘的，无障碍常常一个节点都读不到。**
此时 `root == null`、`isRinging() == false`、`isInCall() == false` ——
**看起来和"对方已经挂断"一模一样**。

这条假象害出了一串 bug：

- 早版本因为"读不到节点"就放弃点击 → 从不自动接听（v1.12/1.15 修）
- `isWeChatForeground()` 只查节点，导致日志里出现"微信明明在前台却显示在前台=false"（v1.18 修）
- "读不到"被当成"来电已消失"，直接 `markEnded()` 把整个会话掐掉（**v1.20 修，见 §8 trap #1**）

**因此有一条贯穿全局的原则**：
> 凡是"没检测到"类的证据，都不能单独用来做**否定性**结论（不能据此判"没来电了/没接通"）。
> 否定性结论必须有额外佐证（通知也被移除 / 音频 mode / 超时）。

### 4.2 判断"微信是不是在前台"必须用三级

`CallHelperAccessibilityService.isWeChatForeground()` 是三级判定：

1. 找得到 **微信窗口** 且根节点非空 → 在前台
2. 遍历 `getWindows()`，包名匹配 → 在前台
3. `getRootInActiveWindow()` 包名匹配 → 在前台

**为什么必须三级**：我们自己画的指引浮层会盖在微信上面，干扰"活动窗口"的判定。
实测日志里出现过 `微信在前台=false 窗口=[0,0][1220,2712]` —— 全屏窗口明明就在眼前。

---

## 5. 接听键坐标体系

### 5.1 为什么只能用坐标

微信 8.0.78 的接听键 **对无障碍 `ACTION_CLICK` 无响应** —— ACTION_CLICK 返回成功、日志写着"已点击(精确定位)"，
呼叫却一直在响铃。**只有 `dispatchGesture` 下发真实手势点坐标才生效**（v1.15 定位到）。

另外节点的 `getBoundsInScreen()` 在部分 ROM 下坐标空间失真
（曾出现 `[987,136,2085,2576]` 这种右边缘远超物理屏宽的 case），**节点中心不可信**。

### 5.2 默认值怎么来的

基准机型：小米 `2407FRK8EC` / Android 16 (API 36) / **1220×2712** / density 3.25 / 微信 8.0.78(3180)。

对来电界面截图做像素分析：

```
白色电话图标中心 = (978, 2403)      ← 图标必然在按钮圆心
实心绿色按钮横向 = 844 ~ 1115        → 中心 x=979.5、直径 271、半径 135
红挂断键中心     = (240, 2403)
```

于是（都存在 `AnswerPointPrefs`，单位是**比例**，不是像素）：

```
DEF_X      = 0.802      横向 978/1220
DEF_BOTTOM = 0.114      距底部 (2712-2403)/2712
DEF_RADIUS = 0.1025     半径按 125px 取，略小于实测半径，让圈套在按钮内侧
```

> **注意 `screenSize()` 必须用 `getRealMetrics()`**（真实的 1220×2712）。
> 用 `getDefaultDisplay().getMetrics()` 拿到的是 1220×2522（扣掉导航栏），
> 按它算会整体偏低 —— 而按钮半径只有 ~135px，偏差 138px 就等于点空。

### 5.3 用户可以自己校准

设置页「◎ 校准接听键位置」→ 拖动左手边的把手 → 保存 → **同时**作用于"画的圈"和"真的点下去的点"。

⚠️ **改默认值必须同时 bump `AnswerPointPrefs.CURRENT_GEN`**（当前 = 2），
否则老用户机器上的旧校准值会继续生效，你的改动对他完全无效。见 §8 trap #5。

### 5.4 【v1.22】不再猜：截图看一眼真实的绿钮在哪 ★

**上面三节的一切，本质都是"猜"**：默认比例是猜的、用户校准是"上一次量到的一个数"。
只要屏幕上的现实一变（微信改版、视频来电而非语音、换导航方式、换机型），
猜出来的坐标就不再是绿钮的真实位置 —— 这就是长期"有时候接得上、有时候接不上"的底层原因。
而微信整页自绘导致**连"读节点校正一次"的机会都没有**。

v1.22 增加了一条不看运气的路：**无障碍服务自己截一张图，按颜色把按钮找出来**。

| 环节 | 实现 |
|---|---|
| 截屏 | `AccessibilityService.takeScreenshot(Display.DEFAULT_DISPLAY, ...)`，需 API 30+ 并且在 `accessibility_service_config.xml` 里声明 `android:canTakeScreenshot="true"` |
| 硬件位图转换 | `Bitmap.wrapHardwareBuffer()` 拿到的是 HARDWARE 位图，**不能直接 `getPixel`**，必须先用 `Canvas.drawBitmap()` 画进一张 ARGB_8888 软件位图（顺带缩到 420px 宽，省内存也更快） |
| 识别 | `ScreenLook`：逐行求"最长连续同色段"→ 以最长那一行作为直径所在 → 上下扩展成外接矩形 → 取中心。再用**尺寸/形状/位置/填充率**四道门槛挡掉误命中 |
| 颜色 | 绿钮 `#07C160` 系：`g>=130 && g>=r+90 && g>=b+30`；红钮 `#FA5151` 系：`r>=120 && r>=g+70 && r>=b+70`（纯整数比较，不用 HSV） |
| 系统限流 | 连续截屏会被系统拒（`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`），所以做了 ≥800ms 节流 + 同一时刻只允许一次在飞 |
| 自干扰 | 我们的指引层画的**也是一个绿圈**，截图前必须 `GuideOverlay.suspendForShot(320)` 临时藏起来，否则认出来的是自己画的那圈 |

**正确性有测试兜底**：`ScreenLook` 的行扫描抽成了 `RowSource` 接口，可以在电脑上用合成图直接跑
（Demo 见 §14）：模拟 1220×2712 来电页，绿钮真实中心 (980,2403) 半径 109，
算法还原出的中心是 **(982, 2403)** —— 误差 2px，远小于按钮半径 135px，"点空"不再可能。

三条使用原则：

1. **看得见时永远优先相信眼睛**：`answerCall()` 第 0 级就是"点截图里的圆心"，原来那四级降为兜底。
2. **顺手自愈**：同屏看到红 + 绿两个钮几乎不可能是误判，此时把位置写进 `AnswerPointPrefs`，
   下次还没来得及截图就有准坐标了（只有和用户校准值差 5% 屏宽以上才覆盖，避免来回抖动）。
3. **"没看到"永远不等于"不在响"**：截图会失败、会被限流、微信哪天也可能把按钮换个颜色。
   所以 `lookProvesRinging()` 只有正面版、**故意不做否定版** —— 截图的空结果不参与任何否定结论。

---

## 6. 模块职责清单

### 6.1 CallHelperAccessibilityService（约 1200 行）— 眼睛与手

| 对外方法 | 作用 |
|---|---|
| `answerCall(boolean allowBlind)` | 四级定位点接听键，返回 5 种结果码 |
| `callUiState()` | 返回 `UI_NONE` / `UI_RINGING` / `UI_IN_CALL` |
| `isFullScreenCallUi()` / `isRinging()` / `isInCall()` | 形态判定 |
| `isWeChatForeground()` | 三级前台判定 |
| `tapAt(x, y)` / `tapAnswerByRatio()` | 真实手势点击 |
| `answerPoint(ctx)` | **静态**，算出当前生效的坐标（供 GuideOverlay 画圈） |

结果码：`RESULT_CLICKED_PRECISE` / `RESULT_CLICKED_BLIND` / `RESULT_NOT_WECHAT` /
`RESULT_NO_WINDOW` / `RESULT_NOT_RINGING`。

**`RINGING_KEYS = {"邀请你", "邀请对方", "接听"}` —— 绝对不能再加「挂断」**。
通话中的界面也有挂断键，加进去会把"已接通"误判成"正在响铃"，
表现就是经典的「接听后还在一直提示」（v1.16 的根因）。

同样，`scanNow()` 必须**先判 `isInCall` 再判 `isRinging`**。

### 6.2 WeChatClicker（约 300 行）— 重试链

`answerWithRetry(attempts, intervalMs, callback)`：

- 精确点击成功 → 1200ms 后校验：在通话中? 还在响? → 最多 `MAX_PRECISE_CLICKS=3` 次
- **盲点**成功 → 2500ms 后确认 + 5500ms 后复核
- 「油门」`sBlindUsedThisCall`：**整通来电只允许盲点一次**。
  盲点无法确认点中了什么，重复戳右下角有碰到左边挂断键的风险。

### 6.3 WeChatCallListenerService（约 200 行）— 耳朵

判定"这是来电邀请"的规则（**别再引入 ongoing/priority 硬要求**）：
包名 == `com.tencent.mm` → 拼所有文本 → 含"邀请"**且**含"通话" →
命中 `STRONG_INVITE` 之一即认定，否则再看 flags/channelId 兜底。

> 安卓 8.0 起 `Notification.priority` 恒为 0（由 channel 决定），微信各版本 channel 名还不一样。
> 曾经要求"必须 ongoing 或高优先级或 channel 含 voip"，结果大量来电被当普通消息丢掉。

**通知被移除必须处理**：微信在对方挂断/接听/取消时都是**直接移除通知**，不改文字。
不实现 `onNotificationRemoved` 就会出现「挂断后铃声还在响」（v1.10 的根因）。

### 6.4 GuideOverlay / CalibrationOverlay — 浮层

- 两者都是 `WindowManager` 浮层；绘制层整层 `FLAG_NOT_TOUCHABLE`（触摸穿透到微信）
- **API ≥ 26 用 `TYPE_APPLICATION_OVERLAY`**，否则 `TYPE_PHONE`
- 三个独立开关：`guide_ring` / `guide_tip` / `guide_stopbar`；三者全关 = 「纯语音模式」，
  **一个悬浮窗都不创建，连悬浮窗权限都不需要**（推荐给介意浮层干扰的用户）
- CalibrationOverlay 的拖动手柄**放在圆圈左边**：放在圈里会挡住微信接听键，
  自动接听点下去落到把手上，等于把刚修好的东西弄坏

### 6.5 其余

| 类 | 一句话 | 备注 |
|---|---|---|
| `TtsSpeaker` | 语音播报 | 播报时把 `STREAM_MUSIC` 抬到 85%，播完恢复 |
| `SpeakScript` | 播报文案集中管理 | 新增文案**只改这个枚举**，设置页自动生成界面 |
| `WhiteListManager` | 名单存储 + 匹配 + "为什么没匹配上" | 见 §7 |
| `CallDiag` | 运行记录 | 见 §9 |
| `PermissionStatus` | 权限判定 | 改服务类名/包名必须同步这里 |
| **`Screen`** | **屏幕尺寸与 dp 换算的唯一实现** | v1.21 新增。所有需要屏幕尺寸/dp 的代码**只准用它**，见 trap #13 |

---

## 7. 名单匹配（WhiteListManager）

存储：`SharedPreferences "call_helper"`，每个家人一条 `wl_<时间戳>` → `"称呼|备注名或号码|是否自动接听(0/1)"`。

匹配优先级（`match()`）：

1. **备注名/微信号全等**
2. 备注名长度 ≥2 且**来电人包含**该备注名
3. 称呼全等
4. 称呼长度 ≥2 且包含
5. 兜底 `matchLoose()`：归一化后重做上述四轮

> 为什么不按"称呼"优先：称呼是给人听的（如"儿子"），备注名才是照着微信抄的。
> 先匹配称呼会出现「微信里有个叫『儿子的同事』的陌生人」这种误命中。

`normalize()` 处理：零宽字符（`\u200b\u200c\u200d\uFEFF\u2060\u00AD`）、全角→半角、
全角空格、所有空白、常见中英文标点、统一小写。

`explainNoMatch()` 与 `unmatched_callers`（最多 8 条）是给**用户自己排查**用的：
> 用户实测反馈：填了「老婆」但微信显示「hh」，app 匹配不上，
> 表现为"既没自动接听、播报还是微信昵称"，而用户完全不知道问题出在哪。

---

## 8. 已知陷阱清单（按"真出过 bug"排序）

改动任何一处之前，**先看这14条里有没有重合的**。

### trap #1 · 「读不到界面」≠「对方挂断了」★ 最高危

整页自绘时无障碍连续几秒读不到任何东西，`isRinging()=false`、`isInCall()=false`，
和"挂断了"长得一模一样。此时如果 `markEnded()`，`cleanup()` 会把
`sAutoRun`/`sEnsureFullScreen`/`WeChatClicker` 排队任务**全部撤销**，自动接听彻底泡汤。

**用户反馈的「有时候能自动接、有时候不能」，这条是最大贡献者** ——
能不能接上取决于恰好哪几秒读得到界面，完全随机。

修法（v1.20）：所有"证据不足"的结束判定必须先过 `allowEndOnWeakEvidence(s)`。
只要流程已启动且未接通，就给最多 `MAX_UNCERTAIN_GRACE=6` 次宽限（约 15 秒）。
真的挂断了，最多少25秒干净；换来的是不再有"莫名其妙不接听"。

### trap #2 · 延时任务互相顶掉

`WeChatClicker` 原本用**全局单槽** `sPending`：每 post 一个新任务就 `cancel()` 掉上一个。
而盲点分支连续排了两个任务（2.5s 确认 + 5.5s 复核）—— 第二个直接把第一个 `removeCallbacks` 掉。

后果链：确认回调永远不执行 → 上层 `sClickCallbackFired` 永假 → 11 秒看门狗每轮必然超时代跑 →
每一轮白等 11 秒 → 90 秒硬超时只跑得完 1~2 轮。**这也直接解释了"有时接得上有时接不上"。**

修法（v1.20）：改成 `HashMap<String, Runnable>` 按标签记账（`TAG_VERIFY`/`TAG_RECHECK`/`TAG_NEXT`），
并用 `Once` 包装回调 —— 回调只发一次，发出即 `cancel()` 掉所有排队任务，
避免"已经接上了还在继续点"。

### trap #3 · 会话被重复重建，把倒计时清零

`startCall()` 是唯一入口，微信响铃期间会通过通知/无障碍反复触发。
只要 caller 字符串**差一个字符**就走 `cleanup()` 重建会话 ——
而重建会把 `sPullAttempt`、`retryCount` 归零，`autoAnswerAt` 重新算一遍完整延时，
`sAutoRun` 也被排到 N 秒之后。**倒计时被反复清零 = 永远轮不到接听。**

修法（v1.20）：① 用 `WhiteListManager.normalize()` 归一化后再比较 caller；
② 自动接听已到点启动时，15 秒窗口内**一律不重建**，哪怕名字真的不同。

### trap #4 · 「成功」了却没推进状态机

`ensureFullScreenStep()` 的 `onResult(true)` 分支曾经**只打一行日志就 return**，
把"已经接通"这件事丢给后面的轮询去发现。
可自绘界面常常轮询不出来 → **明明已经接上了，还在让老人自己点，一直播报到超时**。

修法（v1.20）：收到成功回调必须直接 `markAnswered()`。
> 教训：不要把"确认状态"这件事外包给不可靠的检测。谁确认的就谁负责推进。

### trap #5 · 改了默认值，老用户纹丝不动

`AnswerPointPrefs.ratios()` 曾经直接读 `KEY_X/KEY_BOTTOM/KEY_RADIUS`，**完全绕过代次检查**。
`isCustomized()` 虽然把 `KEY_CUSTOM` 置回 false，但那三个浮点值还躺在 SharedPreferences 里。

表现：用户日志长期出现「接听键位置=用户校准值 → 中心=(980,2265)」—— 那正是当年偏了 138px 的坐标。

修法（v1.20）：`ratios()` 一律先过 `isCustomized()`，不通过就返 `DEF_*`。
**并且：以后改默认值必须同时 bump `CURRENT_GEN`。**

### trap #6 · 浮层泄漏 / 反复重建

`GuideOverlay.show()` 的 catch 分支曾经只把静态引用置空就返回，
**不 `removeViewImmediate`**。已经挂上 WindowManager 的全屏浮层从此再也拿不到句柄：
① 一直盖在微信上，干扰前台判定 ② `onDraw` 以 25fps 空转到进程结束 ③ 下次 show 又叠一层。

同理 `performAccept()` 里 `hide()` 与 watchdog 每 1.5s 的 `show()` 会**互相打架**，
造成浮层闪烁 + 每次重建瞬间把窗口判定搞乱（v1.20：performAccept 不再 hide）。

### trap #7 · 浮层开关被无视

watchdog 里"全屏来电界面仍在 → 重新画上"这个分支曾经不看用户设的三个开关：
用户勾了"关掉所有屏幕提示"，App 照样每 1.5 秒画回来 + 每 1.5 秒刷一条日志。
更糟的是这又把 v1.18 刚修好的"浮层干扰前台判定"请了回来。

修法（v1.20）：先判 `isVoiceOnly()` 和 `canOverlay()`，用户不要就不画，且不刷日志。

### trap #8 · 盲点闸门被绕过

`answerCall()` 里 `root == null` 的分支（自绘界面**最常走**的一支）曾经无视 `allowBlind` 直接戳坐标，
于是"整通来电只准盲点一次"的闸门形同虚设 —— 每一轮都戳，一旦中途接上就可能碰到左侧挂断键。
修法（v1.20）：该分支改成先过 `allowBlind`，且如实返回 `RESULT_CLICKED_BLIND` 让上层计入配额。

### trap #9 · `callUiState()` 过宽

"会话激活 + 微信在前台"就报 `UI_RINGING`，哪怕微信此刻停在聊天列表 ——
那会让上层往右下角坐标戳，点在陌生人的输入框上。
修法（v1.20）：只在**读不到内容**或**窗口确实铺满**时才允许按中间态处理。

### trap #10 · ACTION_CLICK 对微信接听键无效

见 §5.1。**必须用手势**，且坐标用 `getRealMetrics()` 算。

### trap #11 · 权限判定里的硬编码

`PermissionStatus.isAccessibility()` 会拼 `包名 + "/" + CallHelperAccessibilityService.class.getName()`。
**改服务类名或包名必须同步改这里**，否则设置页永远显示"未开启"。

### trap #12 · Manifest 的 `<queries>` 不能删

安卓 11+ 包可见性限制：`targetSdk >= 30` 的应用看不到系统文字转语音引擎，
会在小米等机型上误报「没有可用的语音引擎」，语音播报直接失效。

### trap #13 · 屏幕尺寸必须只有一个实现 ★ v1.21 已解决

历史上工程里有 **5 份**各自写的"拿屏幕尺寸"代码，而且**基准不一致**：
前 4 份用 `getRealMetrics`（物理屏，本机 1220×**2712**，含导航栏），
第 5 份 `CallDiag.snapshot()` 用 `getResources().getDisplayMetrics()`（应用可用区，1220×**2522**）。

后果非常具体：用户把运行记录发来排查坐标问题时，日志里写的「屏幕=1220x2522」
和自动点击真正用的 1220×2712 对不上，**直接把判断带偏**。

现在统一收口到 **`Screen.java`**（`Screen.realSize(ctx)` / `Screen.dp(ctx, v)`），
所有 6 个调用点都走它。**新增任何需要屏幕尺寸的代码，只准用 `Screen`，
不许再手写 `WindowManager` + `DisplayMetrics`。**

### trap #14 · `AccessibilityNodeInfo` 没有 `recycle()` ★ v1.21 已收敛

无障碍返回的节点/窗口背后是跨进程句柄，不回收要等 GC 才释放，
长时间运行会累积 native 内存压力（系统日志会刷 `Instances not recycled`）。

v1.21 的处理原则：**只回收"确定没人再用"的那些** ——
不是微信的窗口、后台不可见的窗口、被临时取出来只为读个包名的活动窗口。

> ⚠️ **千万别去回收 `findWeChatWindow()` 返回的那个节点。**
> v1.21 给它加了 150ms 短缓存，同一个节点会在一次决策链上被好几个调用方拿到，
> 这时候 recycle 等于 use-after-recycle，是必崩的。被选中的节点由 GC 兜底，这是有意保留的取舍。

### trap #15 · 「通话已接通」可能被聊天页面的时间文字误判

`isInCall()` 里有一条是"看到 `\d{1,2}:[0-5]\d` 这种通话时长格式"。
但聊天会话里的**时间分隔符**（页面中间的「14:30」）和**语音消息时长**（「0:15」）也是这个格式。

后果：刚结束一通电话后回到聊天页，立刻被判成"仍在通话中"，
而 `SAME_CALL_GUARD_MS`（30 秒）会把接下来这通真来电当成"上一通的延续"忽略掉。

**修复**：时长格式只有在旁边同时出现「挂断」时才算 `IN_CALL`。

### trap #16 · NotificationListenerService 里吞异常 = 来电完全没反应

`onNotificationPosted` 是**唯一的来电入口**。以前它是 `catch (Exception ignore) {}`：
里面出任何异常都被吞得干干净净，现象是"来电了 App 一点反应都没有"，
而且运行记录里**一条痕迹都没有**，无从查起。

**规则**：这条链路上任何异常都必须写进 `CallDiag`，哪怕看起来无关紧要。
同理适用于 `postCallNotification()` —— 那条通知的 `setFullScreenIntent`
负责把微信通话页从后台拽到前台，它失败会导致后面所有点击尝试全部空转。

### trap #17 · 别让先到的延时任务把后面的全部顶掉（v1.21 变体）

（这一类在 §8 trap #2 讲过一次，这里补 v1.21 的另一个变体）

盲点分支曾经同时排了 2.5s 确认和 5.5s 复核。但 2.5s 那次会无条件回调
`Once` → `Once` 一触发就 `cancel()` 掉所有排队任务 → **5.5s 那次从来没执行过**，
是彻底的死代码。更糟的是它还一味地判"失败"。

**规则**：一次决策链上排了多个延时任务时，先问一句
"先到的那个会不会把后面的全取消掉？"如果两个都要活，先到的必须**只在有结论时才回调**。

### trap #18 · 「没证据」不等于「失败」，也不该消耗配额

本机（微信整页自绘）上，坐标盲点是唯一真正送出点击的路径，而每次盲点之后
往往拿不到任何可读证据 —— 既看不到"通话中"、也读不到一个字。
旧代码一旦盲点就把配额置 1，于是**一通电话只真正点了一下**，
之后的每一轮都被配额挡住，日志却照常打印「第 N/8 轮 → 尝试点击接听键」，
看着像一直在努力。

**规则**：拿不到证据时要把盲点配额还回去；配额用来防止"已经接通还继续乱戳"，
而不是用来惩罚"界面不配合"。

### trap #19 · 「打开微信就播报来电」——把聊天记录的通话留痕当成真来电 ★ v1.22 修复

**现象（用户实测）**：打开微信 / 打开某个聊天框，App 偶尔会播报"微信视频电话来了"，
而这些"未匹配来电人"里的人根本没打过电话 —— 他们只是出现在了聊天记录里。

**根因有两层，都得堵上**：

1. **无障碍这一路**：微信把通话留痕以灰字留在聊天记录里，内容恰好是
   「XXX 邀请你语音通话 / 邀请你视频通话」，和真来电页上的字**一模一样**。
   单看文字永远分不开。所以"是不是真来电"必须要求**结构性证据**
   （挂断/接听控件、VoIP 类名，v1.22 又加了截图里红绿两个圆钮），见 `realIncoming()`。
2. **通知这一路（v1.21 自己挖的坑）**：v1.21 曾把 `isIncomingInvite` 放宽成
   "出现 邀请/来电/呼叫/通话 任意一词 + 高优先级或 ongoing 就算来电"。
   但**普通微信消息通知通常就是高优先级的**，于是"我们在通话吗"这种聊天内容
   也会被当成来电，把发消息的人塞进未匹配列表。v1.22 已回退为
   "完整话术匹配 + 黑名单短语"（`INVITE_PHRASES` / `NOT_AN_INVITE`），并加了总开关闸门。

**规则**：判定"这是一通新来电"时，**任何一路都不允许**只靠一句模糊的词 ——
要么有完整话术，要么有结构证据，要么有画面证据。三者取其一即可，但零个不行。

### trap #20 · 无障碍截屏的四个坑（v1.22 引入"看屏幕"能力时踩过/预判的）

1. **必须声明 `android:canTakeScreenshot="true"`**，否则调用直接抛
   `SecurityException: Services don't have the capability of taking the screenshot`。
2. **`takeScreenshotOfWindow()` 是 API 34 才有的**（`takeScreenshot()` 是 API 30），
   两者都要 `Build.VERSION` 守卫；本项目主路径用全屏 `takeScreenshot(DEFAULT_DISPLAY)`。
3. **回调里拿到的是 `HardwareBuffer`**，`Bitmap.wrapHardwareBuffer()` 出来的
   HARDWARE 位图**不能 `getPixel()`** —— 必须 `Canvas.drawBitmap()` 画进一张
   ARGB_8888 软件位图再读（顺带缩到 420px 宽，省内存也快）。
4. **系统按 1 秒左右限流**，连续截屏会回调 `onFailure(ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)`，
   所以必须节流（`LOOK_MIN_INTERVAL_MS = 800`）+ 同一时刻只允许一次在飞。

另一个**必须防的自坑**：我们的指引层画的就是一个绿圈，截屏前不藏起来
（`GuideOverlay.suspendForShot(320)`），识别器认出来的就是自己画的那一圈。

**规则**：截图结果**只做正面证据**（看见了红绿两钮 → 确认在响铃、确认圆心在哪），
"没看到"绝不能反推"不在响" —— 截图会失败、会被限流、微信也可能改按钮颜色。

---

## 9. 排查：怎么读运行记录

用户看不懂 adb。**唯一可用的排查材料**是：设置 → 「📋 查看运行记录」（`CallDiag`）。

- 存储：内存 `ArrayDeque`（`MAX_MEM_LINES=1500` 行）+ 文件 `filesDir/call_diag.log`（>400KB 整体重写）
- 格式：`MM-dd HH:mm:ss [标签] 消息`，`dump()` 按时间正序
- 标签：`通知` `来电` `接听` `会话` `提醒` `指引` `无障碍` `环境` `校准`
- 每次来电都会调 `snapshot()` 记环境快照：机型 / 系统 / 屏幕 / 微信版本 /
  **通知权限·无障碍·悬浮窗** / 家人数 / 总开关 / 屏幕指引

> 为什么每条来电都记快照：出问题的多是"换了手机 / 升级微信 / 权限被系统收回"，
> 只看单条日志没法判断环境是不是变了。

### 日志行 → 含义对照表

| 看到这句 | 说明 | 下一步 |
|---|---|---|
| `忽略这次来电判定：上一通刚刚接通/结束…` | 同通话保护生效（30 秒内） | 正常，别把它当 bug |
| `本次接听请求被忽略（handled=… ended=…）` | `performAccept` 被去重拦下 | 看是不是 `startCall` 被重复触发了 |
| `拿不到微信节点（整页自绘），但微信确在前台` | 走坐标盲点那一支 | 正常；若后面一直重复说明坐标没点上 |
| `仍是整页自绘界面，但本通来电的坐标兜底已用过一次` | 盲点配额用完 | 正常；坐标不准时需要校准 |
| `坐标盲点后确认：界面完全读不到内容，无法确认` | 无法确认接没接上 | 上层会再试一轮（第二次只走精确定位） |
| `坐标盲点后确认：判别失败` | 判定为没接上 | 检查坐标是否偏了 → 让用户校准 |
| `界面已离开来电状态，视为已接通` | 间接证据判定接通 | 正常，会立刻 `markAnswered` |
| `自动接听失败 → 改为语音+震动+屏幕指引` | 点了但没接上 | 看前面几轮的 verBlind 结果定位原因 |
| `接听后还在提示` 类现象 | `isRinging` 把通话中判成了来电 | 检查 `RINGING_KEYS` 有没有混进「挂断」 |
| `权限都不信了=微信在前台=false` 但窗口是全屏 | 浮层干扰了活动窗口判定 | 确认 `isWeChatForeground()` 三级判定没被改回去 |
| `微信来电通知消失 → 延时确认` | 微信把来电通知直接移除 | 正常；2.5 秒后二次确认是挂断还是只换了条通知 |
| `第 N/6 次宽限，不判结束` | 证据不足的宽限在生效 | 正常；超 6 次仍无证据才会收场 |
| `截图里同时看到红色「挂断」与绿色「接听」两个圆钮` | 画面证据确认为真来电页 | 正常；同时会拿到接听键的准确圆心 |
| `截图里没找到接听/挂断圆钮（屏幕=…）` | 那一刻屏幕上没有来电页 | 若发生在"打开微信"之后，说明误报已被正确拦下 |
| `已把这次看到的圆钮位置记为校准值` | 截图自愈生效 | 正常；下次不用截图也有准坐标 |
| `按截图里那个绿色圆钮的中心点按 (x,y)` | 用真实圆心而不是比例估算去点 | 正常；这是最优先的点击方式 |
| `截图失败：两次截图间隔太短（系统限流）` | 截屏被系统限流 | 正常；800ms 后会再试，不影响主流程 |
| `读到「邀请你…通话」字样，但不满足真来电的判定条件` | 聊天记录留痕被拦下 | 正常；这正是"打开微信不误报"的那道闸 |

---

## 10. 改动红线 Checklist

提交前逐条对照：

- [ ] **没有加任何网络请求**（权限表 / 第三方 SDK / 埋点都不行）
- [ ] 任何"没检测到"的判断，**没有**被单独用来做否定性结论（见 §4.1）
- [ ] 新增的延时任务**用了标签记账**，并检查是否有别的 `postDelayed` 会把它顶掉
- [ ] 涉及接听状态的成功路径，**真的调用了 `markAnswered()`**（不要只打日志）
- [ ] 改动了接听键默认值 → 同步 bump `AnswerPointPrefs.CURRENT_GEN`
- [ ] 涉及浮层 → catch 分支里有 `removeViewImmediate`，且尊重三个开关 + `canOverlay()`
- [ ] `screenSize()` 用 `getRealMetrics()`，与自动点击同一坐标基准
- [ ] 涉及判断逻辑修改 → 确认 `RINGING_KEYS` 没混进「挂断」，且 `isInCall` 先于 `isRinging`
- [ ] 版本号：`versionCode = 小版本号 + 8`，文件名与清单一致
- [ ] 编译过一遍 `bash tools/build_apk.sh`，并确认结尾打印的版本号与文件名一致
- [ ] 没把 keystore / token / 任何密钥写进仓库

---

## 11. SharedPreferences 全表

**所有配置共用一个文件 `call_helper`**（`WhiteListManager.prefs(ctx)` 是统一入口）。

| key | 类型 / 默认 | 用途 |
|---|---|---|
| `auto_answer_master` | boolean / **false** | 自动接听总开关 |
| `auto_delay_sec` | int / **8**，钳制 [3,60] | 自动接听前等待秒数 |
| `guide_overlay` | boolean / true | 屏幕指引总开关 |
| `guide_ring` | boolean / true | 是否画绿圈 |
| `guide_tip` | boolean / true | 是否显示顶部文字 |
| `guide_stopbar` | boolean / true | 是否显示「停止提醒」按钮 |
| `answer_x_ratio` / `answer_bottom_ratio` / `answer_radius_ratio` | float / 0.802 / 0.114 / 0.1025 | 接听键坐标 |
| `answer_point_custom` | boolean / false | 是否用户自己校准过 |
| `answer_point_gen` | int / 2 | 校准值代次（改默认值必须+1） |
| `app_enabled` | boolean / **true** | 【v1.22】总开关：关掉后监听/播报/指引/自动接听全停 |
| `only_whitelist` | boolean / **true** | 【v1.22】名单外的人完全不理会（不播报不进未匹配列表） |
| `wl_<时间戳>` | String / `称呼\|备注名\|0或1` | 家人名单条目 |
| `unmatched_callers` | String / 空，最多 8 条 | 最近未匹配的来电人（给用户自查） |
| `speak_greeting` `speak_auto_wait` `speak_guide_full` `speak_guide_notify` `speak_repeat_full` `speak_repeat_notify` `speak_accept_failed` `speak_test` | String | 见 `SpeakScript.Item.def` |

> `auto_answer_master` 目前是**字面量不是常量**，改名要同时动
> `HomeActivity` / `SettingsActivity` / `CallSessionManager` / `CallDiag` 四处。

---

## 12. 版本历史与「为什么以前改不好」

### 12.1 版本号规则

`versionCode = 小版本号 + 8`。
CI 推出的版本号是 `1.$(run_number - 8)` —— 早期仓库跑过若干次 workflow 才定下这个脚本，
`-8` 是为了让 CI 跑出来的小版本号从 1 开始。**这是历史包袱，不是 bug，别改。**

### 12.2 迭代方法论的反思（写给自己和后来的 agent）

用户问过一句很尖锐的话：「**你为啥老是改不好这几个问题？**」

复盘下来的答案是：**我们一直在做症状驱动的局部修补。**

这个项目之前的迭代模式是：
用户报一个现象 → 我在最像的那个位置加一个 `if` → 现象消失（或没消失）→ 下一个现象。
结果是每个 patch 都对上了一个症状，但它们之间**互相冲突**：

- v1.14 为了"别遗漏"把 `!s.handled` 去掉了，v1.16 又为了"别重复"加了 30 秒同通话保护 —— 两者作用在相邻的条件上；
- v1.12 加了"担心白等"的实体店看门狗，v1.18 又加了"别因为读不到就放弃"的分支 —— 后者恰好绕开了前者的预算假设；
- **最致命的是：没有任何一个 patch 去核对"状态机的每一条出口是不是都真的推进了状态"**，
  于是 §8 trap #4（成功回调只打日志不推进）能在代码里躺好几个版本没人发现。

**v1.20 的做法**：先做全量代码评审，列出问题的严重度排序，再从"能让整条链路跑不完的结构性缺陷"开始改
（任务互相顶掉 / 会话被重建 / 成功不推进 / 宽限期缺失），而不是从"这条日志看着不对"开始改。

**给后来者的建议**：
这个项目里**任何一个新的 `if` 都可能让另一条路径悄悄失效**。
改之前先回答三个问题：
① 它改变了哪条否定性判断？② 它会不会让某个延时任务被提前取消？③ 成功路径有没有真的推进状态机？
回答不上来就先别改，先把状态图画出来。

### 12.3 v1.21 第二轮评审：两轮收益递减，但第二轮挖到的更深

v1.20 做了第一次全量评审，v1.21 又从头扫了一遍 18 个文件约 6600 行。
**实际改出来的东西里，最有价值的几条恰恰是自己 v1.20 的补丁没覆盖到的地方。**

值得记住的三件事：

1. **补丁本身会引入新的缝隙。**
   v1.20 加的"疑罪从无"宽限（`allowEndOnWeakEvidence`）只检查了"流程已启动"，
   而流程要等用户设定的 8 秒倒计时结束才启动 —— 中间那 8 秒是**完全没有保护的**。
   微信恰恰在这段空档里撤掉来电通知，于是还没排队的接听任务被一起清掉。
   **修得更隐蔽了，因为"看起来应该被保护"的那段其实没被保护。**

2. **先等看完整个文件的调用关系，再删看起来无害的分支。**
   "下拉通知栏找来电"这条路径，v1.21 才删掉，而它从被写出来就是错的：
   代码里从来没有任何地方会把通知栏收回去。
   它之所以活了这么久，是因为每次都只看"这段会不会崩"，没人问"拉下来之后谁来收"。

3. **日志会骗人。**
   "第 N/8 轮 → 尝试点击接听键"一排整整齐齐地打印着，看起来它在努力，
   实际上真正执行判定的只有最早那一次（后面的复核是死代码），
   而真正的点击因为配额被"没证据"吃掉，一整通电话只发生了一次。
   **日志的密度不等于执行的有效性。**

另外一个反复出现的教训：**已经承诺给用户的东西要在代码里同步改干净**。
v1.21 删除铃声链路时发现，用户可见文案里还写着"来电只能用铃声提醒" ——
这句话会让用户以为真有兜底。删行为就要删承诺。

### 12.4 v1.22：从"猜坐标"到"看屏幕"，以及自己 v1.21 的一次翻车

用户实测反馈四件事，这一版全部处理：

1. **打开微信 / 聊天框会误播报来电**（见 trap #19）。
   根因是两层的：无障碍一路读到了聊天记录里的通话留痕；通知一路是 v1.21 自己
   放宽的 `isIncomingInvite` 把普通聊天消息也当成了来电 —— **这次修复推翻了
   我上一版自己的改动**，回退为完整话术匹配。
2. **还是不能自动接听**。底层原因在 §5.4：所有坐标都是"猜"，而整页自绘让"读节点校正"
   也没机会。v1.22 引入无障碍截屏 + 颜色识别（`ScreenLook`），
   用真实圆心替代一切估算，并配了可在电脑上跑的合成图测试（§14）。
3. **名单外的人完全不理会**：`only_whitelist`（默认开），未匹配的人不播报、
   不震动、不进「未匹配来电人」列表。
4. **总开关**：`app_enabled`（默认开），在通知监听入口、无障碍入口、`startCall`
   三个水路上各设一道闸，关掉后监听/播报/指引/自动接听全停。

方法论收获：**"识别不出来"的时候，与其继续调阈值，不如换一种感知通道**。
无障碍的节点树这条路已经被微信的整页自绘堵死了，继续在文字匹配上修修补补
收益趋近于零；而截屏这条路和节点树完全正交 —— 它不依赖微信暴露任何东西。
以后再遇到"读不到"的问题，先问一句：**有没有第二条感知通道可走？**

---

## 13. 待办 / 已知未解

| 优先级 | 事项 | 说明 |
|---|---|---|
| 高 | **v1.22 真机验证**：真实微信来电 → 日志里应出现 `[看图] 绿色接听钮 中心=(…) 半径=…` | 这是"能不能自动接上"的关键一环；若一直是 `截图失败：…`，说明机型不支持无障碍截屏，需要换思路 |
| 高 | **v1.22 真机验证**：打开微信 / 聊天框不应再播报 | 观察 `[无障碍] 读到「邀请你…通话」字样，但不满足真来电的判定条件` 是否出现 |
| 中 | `CLICK_ATTEMPTS` 与 `MAX_PULL_ATTEMPTS` 两套重试并存 | 语义重叠，容易误解与调优困难 |
| 低 | `findWeChatWindow()` 返回的节点交给 GC | 有意保留（见 trap #14），若将来加了 recycle 务必先去掉 150ms 缓存 |
| 低 | `CallSessionManager` 1400 行、`CallHelperAccessibilityService` 1250 行 | 建议按职责拆：判定 / 定位 / 状态推进 |
| 低 | `CallDiag` 内存仍保留 1500 行 | 有意为之：用户明确要求"记录多一点便于排查"，别擅自调小 |

---

## 14. 电脑上跑识别算法的测试

`ScreenLook` 的行扫描抽成了 `RowSource` 接口（只依赖 `int[]` 行数据），
所以识别逻辑可以脱离手机、在电脑上用合成图直接验证 —— 改动判定阈值/颜色公式后**必须**跑一遍：

```bash
mkdir -p /tmp/looktest/com/jia/callhelper /tmp/looktest/out
cp app/src/main/java/com/jia/callhelper/ScreenLook.java /tmp/looktest/com/jia/callhelper/
# 再补两个替身：CallDiag.java（静默版）与 LookTest.java（合成图用例）
cd /tmp/looktest
javac -nowarn -cp /tmp/android34.jar -d out com/jia/callhelper/*.java
java -cp out:/tmp/android34.jar com.jia.callhelper.LookTest
```

用例（2026-09 全部通过）：完整来电页还原出绿钮 **(982,2403)** 半径 106（真值 980/2403/109，误差 2px）；
只有绿钮也能定位；聊天页不误报；直径只有真机 40px 的小绿点会被尺寸门槛挡掉。

## 附：第一次拿到崩溃/不复现问题时的排查顺序

1. 让用户导出**运行记录**（设置 → 📋 查看运行记录 → 复制）
2. 先找 `snapshot` 那几行：机型 / 微信版本 / **三项权限**有没有被系统收回
3. 找 `[来电]` 行：名单命中了吗？总开关开了吗？为什么接/为什么不接写得一清二楚
4. 找 `[接听]` 行：形态是什么？有没有"盲点配额用完"？失败原因是 verBlind 的哪一句
5. 找 `[会话]` 行：最后是怎么收场的（`markAnswered` 还是 `markEnded`，理由是什么）
6. **对照 §8 陷阱清单**，再看要不要改代码 —— 八成能在里面找到同类

**这份文档随代码更新。改了结构性逻辑，请同步更新 §8 陷阱清单。**
