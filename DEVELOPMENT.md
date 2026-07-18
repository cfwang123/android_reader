# 文本阅读器（whj.reader）— 命令行开发指南

| 项目 | 说明 |
|------|------|
| 包名 | `com.whj.reader` |
| 应用名 | 文本阅读器 |
| 语言 | Kotlin |
| 构建 | Gradle 8.4 + Android Gradle Plugin 8.3.2 |
| JDK | 17（Corretto，见 `gradle.properties`） |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0+） |
| 路径 | 目录名含中文时需 `android.overridePathCheck=true`（已配置） |

---

## 一、环境

与 `music-player` 对齐：

| 项 | 路径 / 版本 |
|----|-------------|
| Android SDK | `E:\ProgramFiles\androidsdk`（`local.properties`） |
| JDK 17 | `E:\ProgramFiles\HBuilderX\plugins\amazon-corretto` |
| adb | 需在 PATH 中（或使用 SDK `platform-tools`） |

检查：

```powershell
& "E:\ProgramFiles\HBuilderX\plugins\amazon-corretto\bin\java.exe" -version
Test-Path "E:\ProgramFiles\androidsdk\platforms\android-34"
adb devices
```

---

## 二、项目结构

```
reader/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/sample.txt
│       ├── java/com/whj/reader/
│       │   ├── ReaderApp.kt
│       │   ├── MainActivity.kt          # 打开文件 / 最近阅读
│       │   ├── ReadingActivity.kt       # 阅读 + TTS
│       │   ├── SettingsActivity.kt
│       │   ├── data/                    # 加载、设置、书签、最近
│       │   ├── model/
│       │   ├── tts/TtsManager.kt        # 系统 TTS
│       │   └── ui/
│       └── res/
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties
├── gradlew.bat
├── build.js
└── DEVELOPMENT.md
```

---

## 三、编译

在项目根目录 `reader/`：

```powershell
cd D:\VS_Projects\AIPrototype\安卓\reader

.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

或使用 Node 脚本：

```powershell
node build.js build --debug
node build.js build
node build.js clean
node build.js rebuild --debug
```

成功后 APK：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 四、真机安装与运行

```powershell
adb devices
# 或
node build.js devices

# 一键：编译 debug + 安装 + 启动
node build.js run

# 多设备时指定序列号
node build.js run -s 你的序列号
```

等价：

```powershell
.\gradlew.bat installDebug
adb shell am start -n com.whj.reader/.MainActivity
```

查看 logcat：

```powershell
adb logcat --pid=$(adb shell pidof -s com.whj.reader)
adb logcat | Select-String "whj.reader|AndroidRuntime"
```

卸载：

```powershell
adb uninstall com.whj.reader
```

---

## 五、功能说明

### 书架（1 层文件夹）

- **导入 TXT**：加入当前书架（顶层或进入的文件夹）
- **导入文件夹**：SAF 选择 SD 卡/目录，扫描其中一层 `.txt`，在顶层会创建同名书架
- **新建书架**：仅顶层可建（不可嵌套）
- 长按文件夹：重命名 / 删除（书移到顶层）
- 长按书：移动到其它书架或顶层 / 从书架删除（不删源文件）

### 阅读

- 点击正文中心：弹出菜单（上一章/下一章、风格、偏好、跳转、目录书签、视角、自动、夜间、朗读）
- 风格：主题与字号行距；夜间：切换夜间主题；朗读：TTS 控制条
- 编码自动识别 UTF-8 / GBK / GB18030 等

### TTS

- Manifest 已声明 `TTS_SERVICE` queries（Android 11+ 必需，否则会「TTS 未就绪」）
- 高亮当前段、上一句/下一句、暂停继续、发音人、重试/打开系统 TTS 设置
- 无声音时：系统设置 → 文字转语音，下载中文语音包

---

## 六、日常改代码流程

1. 改 `app/src/main/...` 源码或资源
2. `node build.js build --debug` 或 `.\gradlew.bat assembleDebug`
3. `node build.js run` 装到手机并启动
4. `adb logcat` 看日志

---

## 七、常见问题

### TTS 无声音

- 系统是否安装了「文字转语音」引擎（设置 → 无障碍 / 语言）
- 是否下载了中文语音包
- 音量是否静音

### 打开中文 TXT 乱码

应用会尝试 UTF-8 / GB18030 / GBK / Big5；若仍异常，请用编辑器另存为 UTF-8 后再打开。

### 找不到 SDK / JDK

与 music-player 相同：检查 `local.properties` 与 `gradle.properties` 中的路径。
