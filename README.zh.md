# 文本阅读器

轻量 Android 阅读器：书架、TXT/PDF 阅读、系统 TTS、OCR 识图、中英文界面。

[English](README.md)

## 功能概览

- **书架**：导入 TXT/PDF 或整个文件夹；一级书架文件夹；绑定外部目录；多选移动/删除；搜索
- **TXT 阅读**：自绘虚拟滚动，适合大文本；点击左/中/右翻页或调菜单；编码与简繁在阅读页设置
- **PDF 阅读**：分页浏览、缩放、目录、裁边、选区复制、TTS；扫描版可按页 OCR 并缓存
- **排版**：字号、行距、段距、多主题与夜间模式
- **朗读（TTS）**：系统引擎、按句高亮、语速、发音人；句间 `QUEUE_ADD` 预排以减小间隔
- **文本转语音**：独立页面输入文本 → 播放 / 导出音频
- **OCR 识图**：相册/拍照 → 本地 TFLite PP-OCR → 叠加选区与全文复制
- **手势**：左右边缘上下滑可调语速/字号（偏好中可配置）
- **视角**：竖屏 / 横屏 / 自动旋转；全屏沉浸
- **语言**：界面中英文切换（设置 → 界面语言）
- **其它**：进度跳转、书签/目录、空闲自动退出、自动恢复上次阅读

### 书架

- **导入文件**：TXT / PDF，加入当前书架（顶层或文件夹内）
- **导入文件夹**：SAF 选目录，扫描其中一层文本/PDF；在顶层可建同名书架
- **绑定文件夹**：浏览外部目录树中的文件（不复制进书架）
- **新建书架**：仅顶层可建（不可嵌套）
- **多选**：全选 / 移动 / 删除（不删源文件）
- 长按文件夹：重命名 / 删除（书移到顶层）
- 长按书：移动 / 从书架删除  
  > 文本编码请在 **打开书籍后的阅读页** 设置（书架不再提供编码入口）

### TXT 阅读

- 点击正文中心：菜单（上一章/下一章、风格、偏好、跳转、目录书签、视角、自动、夜间、朗读等）
- 顶栏「码」：文件编码与简繁转换
- 编码自动识别 UTF-8 / GBK / GB18030 等，也可手动指定

### PDF 阅读

- 分页渲染、双指缩放、目录跳转
- 文本层可选中复制；支持朗读（有文本或 OCR 缓存时）
- 菜单：裁边、识别扫描版 PDF 文字（按页范围 OCR、进度可取消、结果落盘缓存）

### TTS

- Manifest 已声明 `TTS_SERVICE` queries（Android 11+ 必需）
- 高亮当前句、上一句/下一句、暂停继续、发音人、休眠定时
- 无声音时：系统设置 → 文字转语音，下载中文语音包
- 书架 ⋮ → **文本转语音**：粘贴文本播放或导出

### OCR

- 书架 ⋮ → **OCR 识图**
- 模型位于 `app/src/main/assets/ocr/`（det / cls / rec + 字典）
- 识别后可在图上拖选、复制，下方全文支持惯性滚动

## 环境要求

| 项 | 说明 |
|----|------|
| 包名 | `com.whj.reader` |
| 应用名 | 文本阅读器 |
| 语言 | Kotlin |
| minSdk | 24（Android 7.0+） |
| targetSdk / compileSdk | 34 |
| 构建 | Gradle 8.4 + AGP 8.3.2 |
| JDK | 17（可在本机 `gradle.properties` 配置 `org.gradle.java.home`，勿提交真实路径） |
| 路径 | 目录名含中文时需 `android.overridePathCheck=true`（已配置） |

### 本机配置（勿提交密钥与本机路径）

1. 在项目根目录创建 `local.properties`（已在 `.gitignore` 中忽略）：

```properties
sdk.dir=你的/Android/Sdk路径
```

2. 如需指定 JDK 17，在 **本机** 的 `gradle.properties` 中设置 `org.gradle.java.home`，或保证 `JAVA_HOME` / PATH 指向 JDK 17。

3. `adb` 需在 PATH 中（或使用 SDK 的 `platform-tools`）。

```powershell
java -version
adb devices
```

### Android Studio

标准 Android Gradle 工程，可用 Android Studio 打开、同步、编译与运行。`build.js` / `gradlew` 为命令行辅助。

1. **File → Open** 打开含 `settings.gradle.kts` 的工程根目录  
2. 等待 **Gradle Sync**  
3. debug 变体 → **Run**，或 **Build → Make Project**

| 项 | 说明 |
|----|------|
| Android Studio | 建议 Hedgehog / Iguana 及以后（AGP 8.3） |
| Gradle JDK | **Settings → Build → Gradle → Gradle JDK** → **JDK 17** |
| 签名 | 配置 `keystore.properties` + `release.keystore` 后 debug/release 可共用签名，便于覆盖安装保留数据 |

## 快速开始

在项目根目录 `reader/`：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease

# 或
node build.js release
node build.js build --debug
node build.js clean
node build.js rebuild --debug
node build.js run
node build.js devices
```

APK 位置：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/
```

### 真机安装

```powershell
node build.js run
# 多设备
node build.js run -s 你的序列号

# 等价
.\gradlew.bat installDebug
adb shell am start -n com.whj.reader/.MainActivity
```

```powershell
adb logcat --pid=$(adb shell pidof -s com.whj.reader)
adb uninstall com.whj.reader
```

## 项目结构

```
reader/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/ocr/              # TFLite OCR 模型与字典
│       ├── java/com/whj/reader/
│       │   ├── MainActivity.kt          # 书架
│       │   ├── ReadingActivity.kt       # TXT 阅读 + TTS
│       │   ├── PdfReadingActivity.kt    # PDF 阅读 / OCR / TTS
│       │   ├── OcrActivity.kt           # 识图
│       │   ├── TtsSynthActivity.kt      # 文本转语音
│       │   ├── data/                    # 加载、设置、书架、PDF/OCR 缓存等
│       │   ├── ocr/                     # TFLite 引擎与叠加层
│       │   ├── tts/                     # 系统 TTS
│       │   └── ui/
│       └── res/
│           ├── values/                  # 中文文案（默认）
│           └── values-en/               # 英文文案
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties                     # 本机 SDK，勿提交
├── keystore.properties.example
├── build.js
├── README.md
└── README.zh.md
```

## 界面语言

设置 → **界面语言** → 中文 / English（持久保存）。

## 常见问题

### TTS 无声音

- 系统是否安装文字转语音引擎  
- 是否下载中文语音包  
- 音量是否静音  

### 打开中文 TXT 乱码

应用会尝试 UTF-8 / GB18030 / GBK / Big5；也可在阅读页手动选编码。仍异常时请另存为 UTF-8。

### PDF 扫图无字可选

使用阅读菜单中的 **识别扫描版 PDF 文字**，按页范围 OCR；已识别页会缓存。

### 找不到 SDK / JDK

- 检查 `local.properties` 的 `sdk.dir`  
- 确认 JDK 17 与 `JAVA_HOME` / `org.gradle.java.home`  

## 许可与说明

个人/学习用途原型。系统 TTS 需设备已安装引擎与语音包；OCR 为本地模型，不上传图片。
