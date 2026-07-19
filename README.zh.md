# 文本阅读器（whj.reader）

轻量 Android 阅读器：书架、TXT / EPUB / MOBI / PDF、系统 TTS、语音合成导出、OCR 识图、中英文界面。电子书（TXT/EPUB/MOBI）共用自绘阅读页；PDF 独立渲染链路。

[English](README.md)

## 功能概览

| 模块 | 能力 |
|------|------|
| **书架** | 导入 TXT/PDF/EPUB/MOBI（及 AZW/AZW3/PRC）或整个文件夹；一级书架；绑定外部目录；多选移动/删除；搜索；数据备份/恢复 |
| **流式书** | TXT + EPUB + MOBI 共用 `ReadingActivity` / `VirtualReaderView`；大书流式首屏秒开，后台续载 |
| **PDF** | 连续/单页、缩放、切边、目录预加载、书内链接、TTS、扫描页 OCR、按页合成 |
| **朗读 / 导出** | 系统 TTS 句高亮、锁屏续播、通知/锁屏控件；导出 MP3（arm64 LAME）/ M4A / WAV |
| **OCR** | 相册/拍照 → 本地 TFLite PP-OCR；扫描 PDF 页 OCR 缓存 |
| **其它** | 竖/横/自动旋转；全屏沉浸；界面中英文；常亮与空闲息屏 |

### 书架

- 导入单文件或文件夹（识别 TXT / PDF / EPUB / MOBI 等）；绑定文件夹（不复制，实时浏览）
- 多选：全选 / 移动 / 删除（不删源文件）
- 阅读历史虚拟入口；进度与书签按 URI 记忆
- 设置中可 **备份 / 导入** 书架、进度、书签等（不上传）
- ⋮ 菜单：文本转语音、OCR 识图

### 流式阅读（TXT / EPUB / MOBI）

共用自绘虚拟列表，支持：

- **打开**：EPUB/MOBI 解析 OPF/HTML（或 PalmDOC），**流式首屏**后后台续载，顶栏显示加载进度
- **主题**：默认 / 纯白 / 护眼绿 / 淡蓝 / 淡紫 / 羊皮纸 / 夜间 / **自定义背景色**
- **字体**：默认 / 无衬线 / 衬线 / 等宽（系统 Typeface）；字号、行距、段距、字距
- **TXT**：编码检测与手动选择；简繁转换；一段一行
- **EPUB / MOBI 富文本子集**：
  - 粗体 / 斜体 / 下划线 / 字色 / 背景色
  - **块图**（独占段）与 **行内图**（垂直光学居中）；尊重 HTML width/height / 百分比
  - **超链接**（章内锚点、相对路径、外链确认）
  - 点击图片进入 **图库**（缩放、左右滑、侧边点按翻图）
- **目录 / 书签 / 跳转**；顶栏进度、电量、时钟；书签一键切换
- **书内搜索**：流式扫描，结果边搜边出，点击跳转
- **手势**：侧边点按翻页（低延迟）、滑动滚动；返回键可只停 TTS 而不退出书
- **TTS**：按句高亮；预排多句；朗读规范化（如「第 1 段」→「第1段」）；**锁屏/后台续播**（见下）；休眠定时
- **合成语音**：默认全文；可选起终点行；段末无句号自动补「。」；MP3/M4A/WAV + 码率

### PDF 阅读

- **浏览**：连续滚动 / 单页；双指缩放；切边（含采样自动切边）
- **目录**：打开后后台预加载大纲（磁盘缓存），点目录几乎即时
- **书内链接**：GoTo 跳页；外链确认；顶栏链接后退 / 前进
- **文字**：有文本层可选中复制；扫描版可 **识别扫描版 PDF 文字**（页范围 OCR、可取消、磁盘缓存）
- **朗读 / 合成**：有文本或 OCR 缓存时可用；合成按 **页码范围**
- 菜单上一页/下一页不关闭底部菜单

### TTS 与导出

| 能力 | 说明 |
|------|------|
| 系统 TTS | 引擎 / 语言 / 发音人、语速、句高亮、休眠定时 |
| 句间衔接 | `QUEUE_ADD` 预排多句（默认深度 5），减少句间空档 |
| 朗读文本 | 去掉汉字/数字夹缝空格等，再交给引擎 |
| 锁屏续播 | 见下方 **TTS 持久化** |
| 导出 | 分段 `synthesizeToFile` → 合并 WAV → MP3 或 M4A |
| MP3 | LAME（**仅 arm64** so）；非 arm64 或失败则回退 M4A → WAV |
| 码率 | 32–192 kbps（MP3/M4A） |
| 独立页 | 书架 ⋮ → 文本转语音：粘贴播放或导出 |

### TTS 持久化（锁屏 / 后台续播）

目标：锁屏、切后台后仍能连续朗读，并可用通知栏 / 锁屏媒体控件控制。

#### 实现要点（代码）

| 手段 | 说明 | 主要位置 |
|------|------|----------|
| **前台服务** | 类型 `mediaPlayback`；朗读中 / 暂停中保持 FGS + 通知 | `TtsPlaybackService` |
| **PARTIAL_WAKE_LOCK** | 朗读时持锁，避免 CPU 休眠掐断句回调 | 同上 |
| **MediaSession** | 状态 PLAYING/PAUSED；锁屏媒体面板；耳机/蓝牙键 | 同上 + `MediaButtonReceiver` |
| **音频焦点** | `AUDIOFOCUS_GAIN` + `USAGE_MEDIA`；**灭屏时忽略** OEM 误发的 LOSS | 同上 |
| **MediaSession.onPause** | **灭屏时忽略**系统误 pause，并刷回 PLAYING | 同上 |
| **句管道** | 预排多句；`onDone` / `onStop` 推进；句超时强制推进 | `TtsManager` |
| **看门狗** | 管道空了仍 SPEAKING → 续读；长时间无回调 → 重发当前句 | `TtsManager` |
| **通知权限** | Android 13+ 点朗读时申请 `POST_NOTIFICATIONS`（FGS 通知依赖） | `ReadingActivity` / `PdfReadingActivity` |
| **权限清单** | `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`WAKE_LOCK`、`POST_NOTIFICATIONS` | `AndroidManifest.xml` |

锁屏时 **不在 Activity.onPause 里 stop TTS**；进度可写盘，播控交给前台服务。

#### 用户侧（部分机型仍被杀时）

1. 首次朗读时 **允许通知**
2. 系统设置 → 应用 → 本应用 → **省电策略 / 后台耗电 → 无限制**（小米/华为等）
3. 多任务界面 **锁定** 本应用，避免被清后台
4. 确认已安装系统 TTS 引擎与中文语音包

#### 自测建议

1. 打开书 → 朗读 → 确认通知「正在朗读」
2. 锁屏听 **1 分钟以上** 是否连续
3. 通知栏 / 锁屏：暂停、继续、上一句、下一句
4. 日志：`adb logcat -s WhjTts:V WhjTtsSvc:V`（关注 `ignore AUDIOFOCUS` / `stall` / `timeout`）

### OCR 识图

- 书架 ⋮ → OCR 识图
- 模型：`app/src/main/assets/ocr/`（det / cls / rec + 字典）
- 推理优先 NNAPI → GPU → CPU；图上拖选、全文区惯性滚动

## 环境要求

| 项 | 说明 |
|----|------|
| 包名 | `com.whj.reader` |
| 版本 | 1.0.1（见 `app/build.gradle.kts`） |
| 语言 | Kotlin |
| minSdk | 24 |
| targetSdk / compileSdk | 34 |
| 构建 | Gradle 8.4 + AGP 8.3.2 |
| JDK | 17 |
| 路径 | 中文路径需 `android.overridePathCheck=true`（已配置） |

### 本机配置（勿提交密钥与路径）

1. `local.properties`：`sdk.dir=...`（已 gitignore）
2. 本机 JDK 17
3. `adb` 在 PATH 中
4. 签名：`keystore.properties` + `release.keystore`（示例见 `keystore.properties.example`，勿提交真实密钥）

## 快速开始

```powershell
cd reader
.\gradlew.bat assembleDebug
node build.js run          # 编译 debug + 安装 + 启动
node build.js devices
node build.js release
```

APK：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/reader1.0.1.apk   # 有签名时
```

## 项目结构

```
reader/
├── app/src/main/
│   ├── assets/ocr/                 # TFLite OCR
│   ├── java/com/whj/reader/
│   │   ├── MainActivity.kt         # 书架
│   │   ├── ReadingActivity.kt      # TXT/EPUB/MOBI + TTS + 合成
│   │   ├── PdfReadingActivity.kt   # PDF + 链接 + OCR + 按页合成
│   │   ├── BookSearchActivity.kt   # 书内搜索
│   │   ├── ImageGalleryActivity.kt # 插图浏览
│   │   ├── OcrActivity.kt
│   │   ├── TtsSynthActivity.kt
│   │   ├── data/                   # BookLoader / Epub / Mobi / Html 解析…
│   │   ├── ocr/
│   │   ├── tts/                    # TtsManager、TtsPlaybackService（锁屏续播）
│   │   └── ui/                     # VirtualReaderView 等
│   └── res/
├── build.js
├── keystore.properties.example
├── README.md
└── README.zh.md
```

### 阅读链路（简）

```
URI → BookLoader
        ├─ TXT   → TextLoader
        ├─ EPUB  → EpubLoader（zip/OPF + HtmlRichParser，可 streamer）
        ├─ MOBI  → MobiLoader（PalmDOC + 子集 HTML）
        └─ PDF   → PdfReadingActivity（独立）

流式书 → ReadingActivity + VirtualReaderView
         （段落 / 富文本 span / 块图 / 行内图 / 链接）

TTS    → TtsManager（句管道）+ TtsPlaybackService（FGS / WakeLock / MediaSession）
```

## 包体说明（约 60MB+）

| 部分 | 约占比 | 用途 |
|------|--------|------|
| TFLite + 原生 so（多 ABI） | 最大 | OCR |
| OCR `.tflite` 模型 | 较大 | det/rec/cls |
| PDFBox 等 | 中 | PDF 文字 / 目录 / 链接 |
| LAME `libandroidlame.so` | ~0.2MB | 仅 arm64 MP3 |
| 应用 DEX / 资源 | 其余 | UI 与业务 |

## 常见问题

### TTS 无声

安装系统 TTS 引擎与中文语音包；检查音量。

### 锁屏后 TTS 很快停

见 **TTS 持久化**：允许通知；省电设为无限制；多任务锁定。日志中若频繁 `AUDIOFOCUS_LOSS` 且非灭屏忽略，可能是其它应用抢焦点。

### TXT 乱码

自动 UTF-8 / GB18030 / GBK / Big5，或在阅读页选手动编码。

### EPUB / MOBI 打开慢或样式不全

大书会先出首屏再后台加载；富文本仅为子集（b/i/u/色/背景/图/链接），复杂 CSS / 嵌套表格等不支持。加密 DRM 书无法打开。

### PDF 扫图无字

菜单 → **识别扫描版 PDF 文字**；合成/朗读依赖文本层或 OCR 缓存。

### MP3 不可用

仅 **arm64-v8a** 带 LAME；模拟器/x86 会自动改用 M4A。

### 书内链接点不了

PDF 需内嵌 Link 注释；EPUB 依赖解析出的 href。纯文字目录请用「目录」面板。

## 许可与说明

个人/学习原型。TTS 依赖系统引擎；OCR 本地推理不上传图片；LAME 为第三方相关封装，分发时请自行合规。
