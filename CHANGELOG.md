# Changelog / 更新日志

## 1.0.2 — 2026-07-20

### English
- PDF continuous mode: Office-style right-edge scroll thumb (drag only; show while scrolling, hide 1s after stop)
- PDF scroll/render: fewer blank pages, less jank; stable seek when page heights differ
- PDF layout: fix squashed pages from wrong estimated height
- Gestures: reliable center-tap menu; side tap still turns pages (does not jump via thumb track)
- PDF render pipeline: cancellable priority queue, preview-while-scrolling, safer bitmap caching

### 中文
- PDF 连续滚动：右侧 Office 风格进度手柄（仅拖动；滚动时显示，停 1 秒后消失）
- PDF 滚动/渲染：减少白页与卡顿；页高不一致时拖动手柄更稳
- PDF 排版：修复估算页高错误导致的图片/文字压扁
- 手势：中部点菜单更可靠；侧边点按仍上/下翻页（点轨道不跳转）
- PDF 渲染管线：可取消优先队列、边滑边预览、缓存更安全

---

## 1.0.1

### English
- Initial public baseline: bookshelf, TXT/EPUB/MOBI/PDF, TTS, export, OCR, CN/EN UI

### 中文
- 首个公开基线：书架、TXT/EPUB/MOBI/PDF、朗读与导出、OCR、中英文界面
