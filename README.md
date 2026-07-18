# 文本阅读器（whj.reader）

轻量 Android TXT 阅读器：书架管理、虚拟列表大文件阅读、系统 TTS 朗读、中英文界面。

[English README](README_EN.md) · 开发细节见 [DEVELOPMENT.md](DEVELOPMENT.md)

## 功能概览

- **书架**：导入单个 TXT 或整个文件夹；支持一级书架文件夹
- **阅读**：自绘虚拟滚动，适合大文本；点击左/中/右翻页或调菜单
- **排版**：字号、行距、段距、多主题与夜间模式
- **朗读（TTS）**：系统引擎、按句号分句、语速调节、发音人选择
- **手势**：左右边缘上下滑可调语速/字号（偏好中可配置）
- **视角**：竖屏 / 横屏 / 自动旋转；全屏沉浸
- **语言**：界面中英文切换（设置 → 界面语言）
- **其它**：进度跳转、书签/目录、空闲自动退出、自动恢复上次阅读

## 环境要求

| 项 | 说明 |
|----|------|
| 包名 | `com.whj.reader` |
| 语言 | Kotlin |
| minSdk | 24（Android 7.0+） |
| targetSdk / compileSdk | 34 |
| 构建 | Gradle 8.4 + AGP 8.3.2 |
| JDK | 17 |

详细本机路径与命令行说明见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 快速开始

```powershell
cd reader

# 编译 Debug
.\gradlew.bat assembleDebug

# 安装到已连接设备
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 启动
adb shell am start -n com.whj.reader/.MainActivity
```

首次使用请在 `local.properties` 中配置 `sdk.dir`。

## 项目结构

```
reader/
├── app/src/main/
│   ├── java/com/whj/reader/   # 业务代码
│   │   ├── MainActivity       # 书架
│   │   ├── ReadingActivity    # 阅读 + TTS
│   │   ├── SettingsActivity   # 偏好
│   │   ├── data/              # 加载、设置、书架
│   │   ├── tts/               # 系统 TTS
│   │   └── ui/                # VirtualReaderView 等
│   ├── res/values/            # 中文文案（默认）
│   ├── res/values-en/         # 英文文案
│   └── assets/sample.txt      # 内置示例
├── DEVELOPMENT.md
├── README.md
└── README_EN.md
```

## 界面语言

1. 打开 **偏好 / 设置**
2. 点 **界面语言**
3. 选择 **中文** 或 **English**

语言偏好会持久保存，下次启动自动应用。

## 许可与说明

个人/学习用途原型项目。使用系统 TTS 时需设备已安装对应语音引擎与语音包。
