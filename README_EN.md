# Text Reader (whj.reader)

A lightweight Android TXT reader: bookshelf, virtual scrolling for large files, system TTS, and Chinese/English UI.

[中文说明](README.md) · See [DEVELOPMENT.md](DEVELOPMENT.md) for build details (Chinese).

## Features

- **Bookshelf**: import a single TXT or a whole folder; one-level shelf folders
- **Reading**: custom virtual list for large texts; left / center / right tap zones
- **Typography**: font size, line spacing, paragraph spacing, themes and night mode
- **TTS**: system engine, sentence split by periods, rate control, voice picker
- **Gestures**: swipe up/down on left/right edges to change rate or font size (configurable)
- **Orientation**: portrait / landscape / auto-rotate; fullscreen immersive mode
- **Language**: switch app UI between Chinese and English (Settings → App language)
- **Other**: progress jump, TOC/bookmarks, idle exit, resume last book

## Requirements

| Item | Value |
|------|--------|
| Package | `com.whj.reader` |
| Language | Kotlin |
| minSdk | 24 (Android 7.0+) |
| targetSdk / compileSdk | 34 |
| Build | Gradle 8.4 + AGP 8.3.2 |
| JDK | 17 |

## Quick start

```powershell
cd reader

# Debug build
.\gradlew.bat assembleDebug

# Install on a connected device
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Launch
adb shell am start -n com.whj.reader/.MainActivity
```

Set `sdk.dir` in `local.properties` before the first build.

## Project layout

```
reader/
├── app/src/main/
│   ├── java/com/whj/reader/   # app code
│   │   ├── MainActivity       # bookshelf
│   │   ├── ReadingActivity    # reader + TTS
│   │   ├── SettingsActivity   # preferences
│   │   ├── data/              # loaders, settings, shelf
│   │   ├── tts/               # system TTS
│   │   └── ui/                # VirtualReaderView, etc.
│   ├── res/values/            # Chinese strings (default)
│   ├── res/values-en/         # English strings
│   └── assets/sample.txt      # sample text
├── DEVELOPMENT.md
├── README.md
└── README_EN.md
```

## Switching language

1. Open **Prefs / Settings**
2. Tap **App language**
3. Choose **中文** or **English**

The choice is saved and applied on next launch.

## Notes

Prototype for personal/learning use. System TTS requires a TTS engine and voice pack installed on the device.
