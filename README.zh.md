# 文本阅读器（whj.reader）

轻量 Android 阅读器：书架、TXT/PDF 阅读、系统 TTS、选区/按页合成导出、OCR 识图、中英文界面。

[English](README.md)

## 功能概览

- **书架**：导入 TXT/PDF 或整个文件夹；一级书架；绑定外部目录；多选移动/删除；搜索
- **TXT 阅读**：自绘虚拟列表；字体预设；编码与简繁；按句 TTS；选段合成语音导出
- **PDF 阅读**：连续/单页、缩放、切边、目录（打开即预加载）、书内链接、TTS、扫描页 OCR
- **合成语音**：TXT 按行选起终点 / PDF 按页码；导出 **MP3**（arm64 LAME）/ **M4A** / **WAV**，可选码率
- **OCR 识图**：相册/拍照 → 本地 TFLite PP-OCR → 叠加选区与全文
- **视角**：竖屏 / 横屏 / 自动（菜单图标随状态切换）；全屏沉浸
- **语言**：界面中英文（设置 → 界面语言）

### 书架

- 导入 TXT / PDF；导入文件夹；绑定文件夹（不复制进书架）
- 多选：全选 / 移动 / 删除（不删源文件）
- 长按书：移动 / 删除；**编码请在 TXT 阅读页设置**

### TXT 阅读

- 中心菜单（可横滑两屏）：风格、偏好、跳转、目录书签、视角、全屏、夜间、朗读、**合成语音**
- **字体**：默认 / 无衬线 / 衬线 / 等宽（系统 Typeface，不增大 APK）
- **分段**：一个回车一行一段；章节标题仅认「第 X 章」等，**「一、二、三、」按正文不加粗**
- **朗读**：按句高亮；`QUEUE_ADD` 预排下一句；返回键可只停播/关 TTS 条
- **合成语音**：默认全文；可选起终点行；段末无句号自动补「。」；格式 MP3/M4A/WAV + 码率

### PDF 阅读

- **浏览**：连续滚动 / 单页；双指缩放；切边（含采样自动切边）
- **目录**：打开 PDF 后后台预加载大纲到内存（并落盘缓存），点目录几乎即时
- **书内链接**：点击 GoTo 跳页；外链确认后打开；顶栏 **链接后退 / 前进**
- **文字**：有文本层可选中复制；扫描版可菜单 **识别扫描版 PDF 文字**（页范围 OCR、可取消、磁盘缓存）
- **朗读**：有文本或 OCR 缓存时可用
- **合成语音**：按 **页码范围**（默认当前页～末页，可「全部页」）；无字页需先 OCR
- 菜单上一页/下一页 **不关闭**底部菜单；视角三图标随配置变化

### TTS 与导出

| 能力 | 说明 |
|------|------|
| 系统 TTS | 引擎/语言/发音人、语速、句高亮、休眠定时 |
| 句间衔接 | 当前句播放时 `QUEUE_ADD` 预排下一句 |
| 导出 | 分段 `synthesizeToFile` → 合并 WAV → MP3 或 M4A |
| MP3 | LAME（**仅 arm64** so）；非 arm64 或失败则回退 M4A，再失败则 WAV |
| 码率 | 32 / 48 / 64 / 96 / 128 / 160 / 192 kbps（MP3/M4A） |
| 独立页 | 书架 ⋮ → 文本转语音：粘贴播放或导出 |

### OCR 识图

- 书架 ⋮ → OCR 识图
- 模型：`app/src/main/assets/ocr/`（det / cls / rec + 字典）
- 图上拖选、全文区惯性滚动

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
2. 本机 JDK 17 / 可选 `org.gradle.java.home`
3. `adb` 在 PATH 中

```powershell
java -version
adb devices
```

### Android Studio

打开含 `settings.gradle.kts` 的工程根目录 → Sync → Run。  
签名：`keystore.properties` + `release.keystore`（示例见 `keystore.properties.example`）。

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

```powershell
adb logcat --pid=$(adb shell pidof -s com.whj.reader)
adb uninstall com.whj.reader
```

## 项目结构

```
reader/
├── app/src/main/
│   ├── assets/ocr/                 # TFLite OCR
│   ├── java/com/whj/reader/
│   │   ├── MainActivity.kt         # 书架
│   │   ├── ReadingActivity.kt      # TXT + TTS + 合成
│   │   ├── PdfReadingActivity.kt   # PDF + 链接 + OCR + 按页合成
│   │   ├── OcrActivity.kt
│   │   ├── TtsSynthActivity.kt
│   │   ├── data/                   # 加载、书架、PDF 大纲/链接、OCR 缓存…
│   │   ├── ocr/
│   │   ├── tts/                    # TtsManager、Export、Wav/Aac/Mp3
│   │   └── ui/
│   └── res/
├── build.js
├── keystore.properties.example
├── README.md
└── README.zh.md
```

## 包体说明（约 60MB+）

| 部分 | 约占比 | 用途 |
|------|--------|------|
| TFLite + 原生 so（多 ABI） | 最大 | OCR |
| OCR `.tflite` 模型 | 较大 | det/rec/cls |
| PDFBox 等 | 中 | PDF 文字/目录/链接 |
| LAME `libandroidlame.so` | ~0.2MB | 仅 arm64 MP3 |
| 应用 DEX / 资源 | 其余 | UI 与业务 |

## 常见问题

### TTS 无声

安装系统 TTS 引擎与中文语音包；检查音量。

### TXT 乱码

自动 UTF-8 / GB18030 / GBK / Big5，或在阅读页选手动编码。

### PDF 扫图无字

菜单 → **识别扫描版 PDF 文字**；合成/朗读依赖文本层或 OCR 缓存。

### MP3 不可用

仅 **arm64-v8a** 带 LAME；模拟器/x86 会自动改用 M4A。

### 书内链接点不了

需 PDF 内嵌 Link 注释；纯文字目录无链接时请用「目录」面板。

## 许可与说明

个人/学习原型。TTS 依赖系统引擎；OCR 本地推理不上传图片；LAME 为第三方 LGPL 相关封装，分发时请自行合规。
