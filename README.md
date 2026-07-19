# Text Reader

A lightweight Android reader: bookshelf, TXT/PDF reading, system TTS, on-device OCR, and Chinese/English UI.

[中文](README.zh.md)

## Features

- **Bookshelf**: import TXT/PDF or a folder; one-level shelf folders; bind external folders; multi-select move/delete; search
- **TXT reading**: custom virtual list for large files; left / center / right tap zones; encoding & Chinese conversion in the reader (not on the shelf)
- **PDF reading**: pages, zoom, TOC, crop, selection/copy, TTS; optional per-page OCR for scanned PDFs (cached)
- **Typography**: font size, line/paragraph spacing, themes, night mode
- **TTS**: system engine, sentence highlight, rate, voice picker; next sentence pre-queued with `QUEUE_ADD`
- **Text-to-speech page**: paste text → play or export audio
- **OCR**: gallery/camera → local TFLite PP-OCR → overlay selection and full-text copy
- **Gestures**: edge swipe for rate / font size (configurable)
- **Orientation**: portrait / landscape / auto; immersive fullscreen
- **Language**: app UI Chinese / English (Settings → App language)
- **Other**: jump, TOC/bookmarks, idle exit, resume last book

### Bookshelf

- **Import file**: TXT / PDF into the current shelf
- **Import folder**: SAF; one-level scan; can create a same-named shelf at root
- **Bind folder**: browse external tree without copying into the shelf
- **New shelf**: root only (no nesting)
- **Multi-select**: select all / move / remove (does not delete source files)
- Long-press folder: rename / delete (books move to root)
- Long-press book: move / remove from shelf  
  > Set text encoding in the **reading screen** after opening a book (no encoding entry on the shelf)

### TXT reading

- Tap center: menu (chapter, style, prefs, jump, TOC/bookmarks, orientation, auto, night, TTS, …)
- Toolbar encoding control: file encoding and simplified/traditional Chinese
- Auto-detect UTF-8 / GBK / GB18030, etc., or set manually

### PDF reading

- Page render, pinch-zoom, TOC
- Selectable text layer when present; TTS when text or OCR cache is available
- Menu: crop margins; **OCR scanned PDF pages** (range, progress/cancel, on-disk cache)

### TTS

- Manifest declares `TTS_SERVICE` queries (required on Android 11+)
- Sentence highlight, prev/next, pause/resume, voice, sleep timer
- No sound: install a TTS engine and Chinese voice pack
- Shelf ⋮ → **Text to speech**: paste text to play or export

### OCR

- Shelf ⋮ → **OCR**
- Models under `app/src/main/assets/ocr/` (det / cls / rec + keys)
- Drag-select on the image; full text below uses fling scrolling

## Requirements

| Item | Value |
|------|--------|
| Package | `com.whj.reader` |
| App name | Text Reader |
| Language | Kotlin |
| minSdk | 24 (Android 7.0+) |
| targetSdk / compileSdk | 34 |
| Build | Gradle 8.4 + AGP 8.3.2 |
| JDK | 17 (optional local `org.gradle.java.home`; do not commit machine paths) |
| Paths | Keep `android.overridePathCheck=true` if the path is non-ASCII (already set) |

### Local setup (do not commit secrets)

1. Create `local.properties` (gitignored):

```properties
sdk.dir=path/to/Android/Sdk
```

2. Pin JDK 17 via local `gradle.properties` / `JAVA_HOME` if needed.

3. Put `adb` on PATH.

```powershell
java -version
adb devices
```

### Android Studio

Open the project root (folder with `settings.gradle.kts`), Sync, Run debug. Prefer Gradle JDK 17. With `keystore.properties` + `release.keystore`, debug/release can share a keystore so reinstalls keep app data.

## Quick start

From `reader/`:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease

node build.js release
node build.js build --debug
node build.js clean
node build.js rebuild --debug
node build.js run
node build.js devices
```

APKs:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/
```

### Device

```powershell
node build.js run
node build.js run -s YOUR_SERIAL

.\gradlew.bat installDebug
adb shell am start -n com.whj.reader/.MainActivity
```

```powershell
adb logcat --pid=$(adb shell pidof -s com.whj.reader)
adb uninstall com.whj.reader
```

## Project layout

```
reader/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/ocr/              # TFLite OCR models + dict
│       ├── java/com/whj/reader/
│       │   ├── MainActivity.kt          # bookshelf
│       │   ├── ReadingActivity.kt       # TXT + TTS
│       │   ├── PdfReadingActivity.kt    # PDF / OCR / TTS
│       │   ├── OcrActivity.kt
│       │   ├── TtsSynthActivity.kt
│       │   ├── data/
│       │   ├── ocr/
│       │   ├── tts/
│       │   └── ui/
│       └── res/
│           ├── values/                  # Chinese (default)
│           └── values-en/
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties                     # local SDK, do not commit
├── keystore.properties.example
├── build.js
├── README.md
└── README.zh.md
```

## Language

Settings → **App language** → 中文 / English.

## FAQ

### TTS silent

Install a system TTS engine and a Chinese voice pack; check volume.

### Garbled Chinese TXT

Auto-detect UTF-8 / GB18030 / GBK / Big5, or set encoding in the reader. Re-save as UTF-8 if needed.

### Scanned PDF has no selectable text

Use **OCR scanned PDF pages** in the PDF menu; results are cached.

### SDK / JDK missing

Check `sdk.dir` and JDK 17 / `JAVA_HOME`.

## Notes

Prototype for personal/learning use. System TTS needs an engine and voice pack; OCR runs fully on-device.
