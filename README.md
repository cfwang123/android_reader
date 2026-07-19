# Text Reader (whj.reader)

A lightweight Android reader: bookshelf, TXT/PDF, system TTS, speech export (MP3/M4A/WAV), on-device OCR, Chinese/English UI.

[中文](README.zh.md)

## Features

- **Bookshelf**: import TXT/PDF or folders; one-level shelves; bind external trees; multi-select; search
- **TXT**: virtual list, font presets, encoding/Chinese conversion, sentence TTS, range speech export
- **PDF**: continuous/single page, zoom, crop, TOC (preloaded on open), in-doc links, TTS, scan OCR
- **Speech export**: TXT by line range / PDF by page range; **MP3** (arm64 LAME) / **M4A** / **WAV** + bitrate
- **OCR**: gallery/camera → TFLite PP-OCR → overlay + full text
- **Orientation**: portrait / landscape / auto (menu icon follows mode); immersive fullscreen
- **Language**: Settings → App language

### Bookshelf

- Import TXT/PDF; import folder; bind folder (browse only)
- Multi-select: move / remove (does not delete source files)
- Encoding is set on the **TXT reading screen**, not the shelf

### TXT reading

- Center menu (two-page horizontal swipe): style, prefs, jump, TOC, orientation, fullscreen, night, read, **synthesize**
- **Fonts**: default / sans / serif / mono (system typefaces)
- **Paragraphs**: one newline = one unit; only real chapter titles (e.g. “第X章”) are bold — not “一、二、三、” list lines
- **TTS**: sentence highlight; `QUEUE_ADD` prequeue; back key stops TTS without leaving the book
- **Export**: default full text; optional start/end lines; auto-append “。” if a line lacks sentence punctuation

### PDF reading

- Continuous or single-page; pinch-zoom; margin crop
- **TOC**: preloaded into memory when the PDF opens (plus disk cache)
- **Links**: tap GoTo links to jump; external URI with confirm; toolbar **history back/forward**
- Text selection when a text layer exists; **OCR scanned pages** (range, cancelable, on-disk cache)
- **Export speech** by **page range** (default current→last; “all pages”); OCR first if no text
- Menu prev/next page keeps the bottom menu open; orientation icon has 3 states

### TTS & export

| Item | Detail |
|------|--------|
| System TTS | Engine / language / voice, rate, highlight, sleep timer |
| Continuity | Prequeue next sentence with `QUEUE_ADD` |
| Export pipeline | Chunked `synthesizeToFile` → merge WAV → MP3 or M4A |
| MP3 | LAME **arm64-only** native lib; fallback M4A then WAV |
| Bitrate | 32–192 kbps for MP3/M4A |
| Standalone | Shelf ⋮ → Text to speech |

### OCR

- Shelf ⋮ → OCR
- Models: `app/src/main/assets/ocr/`
- Overlay selection; fling scrolling for full text

## Requirements

| Item | Value |
|------|--------|
| Package | `com.whj.reader` |
| Version | 1.0.1 (`app/build.gradle.kts`) |
| Language | Kotlin |
| minSdk | 24 |
| targetSdk / compileSdk | 34 |
| Build | Gradle 8.4 + AGP 8.3.2 |
| JDK | 17 |

### Local setup

1. `local.properties` with `sdk.dir=...` (gitignored)
2. JDK 17
3. `adb` on PATH

## Quick start

```powershell
cd reader
.\gradlew.bat assembleDebug
node build.js run
node build.js devices
node build.js release
```

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/reader1.0.1.apk
```

## Project layout

```
reader/
├── app/src/main/
│   ├── assets/ocr/
│   ├── java/com/whj/reader/
│   │   ├── MainActivity.kt
│   │   ├── ReadingActivity.kt      # TXT + TTS + export
│   │   ├── PdfReadingActivity.kt   # PDF + links + OCR + page export
│   │   ├── OcrActivity.kt
│   │   ├── TtsSynthActivity.kt
│   │   ├── data/
│   │   ├── ocr/
│   │   ├── tts/                    # manager, export, wav/aac/mp3
│   │   └── ui/
│   └── res/
├── build.js
├── keystore.properties.example
├── README.md
└── README.zh.md
```

## APK size (approx.)

Largest parts: TFLite natives (multi-ABI) + OCR models; then PDFBox; LAME arm64 is ~0.2 MB. The rest is app DEX/UI.

## FAQ

### No TTS audio

Install a system TTS engine and a Chinese voice pack; check volume.

### Garbled TXT

Auto-detect or set encoding in the reader.

### Scanned PDF has no text

Use **OCR scanned PDF pages**; export/read need a text layer or OCR cache.

### MP3 unavailable

Only **arm64-v8a** ships LAME; x86/emulators fall back to M4A.

### Links do nothing

Need real PDF link annotations; pure-text TOC entries use the TOC panel.

## Notes

Personal/learning prototype. TTS uses the system engine; OCR is fully on-device. LAME is third-party — check license compliance if you redistribute.
