# PdfReadingActivity 模块化重构 TODO

目标：把 `PdfReadingActivity.kt` 按职责拆到 `com.whj.reader.pdf` 包，Activity 只保留生命周期与接线。

原则：

- 按功能域抽 **class**，不用巨型嵌套 `object`
- 子模块通过 **窄接口 / Host** 访问 Activity 能力
- **分阶段**提交；改完 `node build.js run`
- 单文件建议 ≤ 800～1000 行再继续拆

---

## 目标包结构

```text
com.whj.reader.pdf/
  render/     # ✅ 调度、缓存、管线、位图
  layout/     # ✅ 页高表
  coord/      # ✅ ViewMapper、CropHelper
  text/       # ✅ TextCache、SelectionState、SelectionController
  link/       # ✅ LinkNavigator
  chrome/     # ✅ StatusBarHelper（时钟/电量文案）
  ocr/        # ✅ PdfPageOcrRunner（单页 OCR 流水线）
  mode/ …     # 待做（单页/连续 UI 控制器）
  tts/ …      # 待做
```

---

## Phase 1 — 渲染核心 + 页高表 ✅

`render/*`、`layout/PdfPageHeightTable` 已接入。

---

## Phase 2 — 坐标 / 文本 / 选字 / 链接 / 管线

| 状态 | 项 |
|------|----|
| [x] | `PdfViewMapper` + `pageVisibleBandInRv` |
| [x] | `PdfTextCache` |
| [x] | `PdfTextSelectionState` |
| [x] | `PdfTextSelectionController`（状态、选中文本、段落映射、边缘滚选卡住、nearestChar） |
| [x] | `PdfLinkNavigator` |
| [x] | `PdfRenderPipeline` |
| [x] | `PdfCropHelper` |
| [x] | `PdfStatusBarHelper` |
| [x] | 点击取消选区 / 拖动放行 pan |
| [ ] | hitTest / begin / extend 整段迁入 Controller（仍依赖 binding） |
| [ ] | `PdfContinuousController` / `PdfSinglePageController` |

Activity 仍保留：生命周期、loadPdf、bindPageSurface、showSinglePage、hitTest 坐标与 View、ActionMode UI、TTS/OCR 业务。

---

## Phase 3 — TTS / OCR / Chrome / Mode

| 状态 | 项 |
|------|----|
| [ ] | `PdfTtsController` + 导出面板 |
| [x] | `PdfPageOcrRunner`（ocrOnePage + 条带/合并/调试落盘；`formatPageList`） |
| [ ] | OCR 任务 UI（`showPdfOcrDialog` / `startPdfOcrJob` 进度对话框） |
| [ ] | `PdfChromeController`（菜单/沉浸/inset） |
| [ ] | 模式切换控制器（`setupPinchZoom` / continuous / single） |
| [ ] | hitTest / begin / extend 整段迁入 SelectionController |
| [ ] | Activity 迁到 `pdf/PdfReadingActivity.kt` |

Activity 当前约 **~7130 行**（OCR 页 runner 抽出约 400 行）。

---

## 进度日志

| 日期 | 内容 |
|------|------|
| 2026-07-23 | Phase1 完成 |
| 2026-07-23 | Phase2 状态/管线/选字控制器/状态栏 Helper |
| 2026-07-23 | `pdf/ocr/PdfPageOcrRunner`：单页 OCR 流水线 + Host 接线；`node build.js run` 通过 |
