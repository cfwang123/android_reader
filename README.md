# Text Reader (whj.reader)

A lightweight Android reader: bookshelf, TXT / EPUB / MOBI / PDF, system TTS, speech export, on-device OCR, Chinese/English UI. Stream books (TXT/EPUB/MOBI) share one self-drawn reading surface; PDF has its own pipeline.

[中文](README.zh.md)

## Features

| Area | Capabilities |
|------|----------------|
| **Bookshelf** | Import TXT/PDF/EPUB/MOBI (and AZW/AZW3/PRC) or folders; one-level shelves; bind external trees; multi-select; search; backup/restore |
| **Stream books** | TXT + EPUB + MOBI via `ReadingActivity` / `VirtualReaderView`; fast first paint, background continue-load |
| **PDF** | Continuous/single page, zoom, crop, TOC preload, in-doc links, TTS, scan OCR, page-range export |
| **TTS / export** | Sentence highlight, lock-screen continue, media controls; export **MP3** (arm64 LAME) / **M4A** / **WAV** |
| **OCR** | Gallery/camera → TFLite PP-OCR; scanned PDF page OCR cache |
| **Other** | Portrait / landscape / auto; immersive fullscreen; app language; keep-screen + idle timeout |

### Bookshelf

- Import files or folders (TXT / PDF / EPUB / MOBI…); bind folder (browse only, no copy)
- Multi-select: move / remove (does not delete source files)
- Reading history virtual entry; progress and bookmarks keyed by URI
- **Backup / import** shelves, progress, bookmarks (local only)
- Overflow: Text to speech, OCR

### Stream reading (TXT / EPUB / MOBI)

Shared virtual list:

- **Open**: EPUB/MOBI parse OPF/HTML (or PalmDOC); **stream first screen**, then continue in background with progress in the title bar
- **Themes**: default / white / green / blue / purple / sepia / night / **custom background**
- **Fonts**: default / sans / serif / mono (system typefaces); size, line spacing, paragraph spacing, letter spacing
- **TXT**: encoding detect/manual; simplified↔traditional Chinese; one newline = one paragraph
- **EPUB / MOBI rich subset**:
  - bold / italic / underline / color / background
  - **block images** and **inline images** (optical vertical center); HTML width/height / percent
  - **hyperlinks** (in-book anchors, relative paths, external with confirm)
  - tap image → **gallery** (zoom, swipe, side-tap)
- **TOC / bookmarks / jump**; title bar progress, battery, clock
- **In-book search**: streaming scan, live results, tap to jump
- **Gestures**: low-latency side-tap page turn, scroll; back can stop TTS only
- **TTS**: sentence highlight; multi-sentence prequeue; speak-text normalize (e.g. drop spaces in “第 1 段”); **lock-screen / background continue** (below); sleep timer
- **Export speech**: full text by default; optional line range; auto-append “。”; MP3/M4A/WAV + bitrate

### PDF reading

- Continuous or single-page; pinch-zoom; margin crop
- **TOC** preloaded on open (disk cache)
- **Links**: GoTo jump; external URI confirm; toolbar history back/forward
- Text selection when a text layer exists; **OCR scanned pages** (range, cancelable, on-disk cache)
- **Export speech** by page range; OCR first if no text
- Menu prev/next keeps the bottom menu open

### TTS & export

| Item | Detail |
|------|--------|
| System TTS | Engine / language / voice, rate, highlight, sleep timer |
| Continuity | `QUEUE_ADD` prequeue (depth 5) |
| Speak text | Normalize CJK/digit spacing before engine |
| Lock-screen | See **TTS persistence** below |
| Export pipeline | Chunked `synthesizeToFile` → merge WAV → MP3 or M4A |
| MP3 | LAME **arm64-only**; fallback M4A then WAV |
| Bitrate | 32–192 kbps for MP3/M4A |
| Standalone | Shelf ⋮ → Text to speech |

### TTS persistence (lock screen / background)

Goal: keep reading aloud after lock or backgrounding, with notification / lock-screen media controls.

#### Implementation

| Mechanism | Role | Where |
|-----------|------|--------|
| **Foreground service** | `mediaPlayback` FGS + ongoing notification while speaking/paused | `TtsPlaybackService` |
| **PARTIAL_WAKE_LOCK** | Keep CPU while speaking so utterance callbacks run | same |
| **MediaSession** | PLAYING/PAUSED; lock-screen panel; headset/BT keys | same + `MediaButtonReceiver` |
| **Audio focus** | `AUDIOFOCUS_GAIN` + `USAGE_MEDIA`; **ignore LOSS while screen off** (OEM quirk) | same |
| **MediaSession.onPause** | **Ignore while screen off**; force session back to PLAYING | same |
| **Sentence pipeline** | Prequeue; `onDone` / `onStop`; per-utterance timeout → force advance | `TtsManager` |
| **Watchdog** | Empty pipeline while SPEAKING → continue; long stall → re-speak | `TtsManager` |
| **Notifications** | Request `POST_NOTIFICATIONS` on Android 13+ when user starts TTS | reading activities |
| **Manifest** | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `POST_NOTIFICATIONS` | `AndroidManifest.xml` |

Activity `onPause` does **not** stop TTS; progress may still be saved.

#### If the device still kills playback

1. Allow notifications on first TTS start  
2. Settings → Apps → this app → **battery unrestricted**  
3. Lock the app in recents  
4. Install a system TTS engine + Chinese voice pack  

#### Quick test

1. Open a book → play → confirm “Speaking” notification  
2. Lock screen and listen for **1+ minutes**  
3. Use notification / lock controls: pause, resume, prev/next sentence  
4. Logs: `adb logcat -s WhjTts:V WhjTtsSvc:V`

### OCR

- Shelf ⋮ → OCR
- Models: `app/src/main/assets/ocr/`
- Backend: NNAPI → GPU → CPU; overlay selection; fling for full text

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
4. Signing: `keystore.properties` + `release.keystore` (see `keystore.properties.example`; do not commit secrets)

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
│   │   ├── ReadingActivity.kt      # TXT/EPUB/MOBI + TTS + export
│   │   ├── PdfReadingActivity.kt
│   │   ├── BookSearchActivity.kt
│   │   ├── ImageGalleryActivity.kt
│   │   ├── OcrActivity.kt
│   │   ├── TtsSynthActivity.kt
│   │   ├── data/                   # BookLoader, Epub, Mobi, Html…
│   │   ├── ocr/
│   │   ├── tts/                    # TtsManager, TtsPlaybackService (lock-screen)
│   │   └── ui/                     # VirtualReaderView, …
│   └── res/
├── build.js
├── keystore.properties.example
├── README.md
└── README.zh.md
```

### Load path (short)

```
URI → BookLoader
        ├─ TXT   → TextLoader
        ├─ EPUB  → EpubLoader (zip/OPF + HtmlRichParser, optional streamer)
        ├─ MOBI  → MobiLoader (PalmDOC + HTML subset)
        └─ PDF   → PdfReadingActivity

Stream books → ReadingActivity + VirtualReaderView

TTS          → TtsManager (pipeline) + TtsPlaybackService (FGS / WakeLock / MediaSession)
```

## APK size (approx.)

Largest parts: TFLite natives (multi-ABI) + OCR models; then PDFBox; LAME arm64 is ~0.2 MB. The rest is app DEX/UI.

## FAQ

### No TTS audio

Install a system TTS engine and a Chinese voice pack; check volume.

### TTS stops soon after lock

See **TTS persistence**: allow notifications; unrestricted battery; lock in recents. If logs show focus LOSS while the screen is on, another app may be taking focus.

### Garbled TXT

Auto-detect or set encoding in the reader.

### EPUB / MOBI slow or incomplete styling

Large books paint the first screen then load in the background. Rich text is a **subset** (b/i/u/color/bg/images/links); complex CSS/tables are unsupported. DRM-encrypted books will not open.

### Scanned PDF has no text

Use **OCR scanned PDF pages**; export/read need a text layer or OCR cache.

### MP3 unavailable

Only **arm64-v8a** ships LAME; x86/emulators fall back to M4A.

### Links do nothing

PDF needs real link annotations; EPUB needs parsed `href`. Pure-text TOC entries use the TOC panel.

## Notes

Personal/learning prototype. TTS uses the system engine; OCR is fully on-device. LAME is third-party — check license compliance if you redistribute.
