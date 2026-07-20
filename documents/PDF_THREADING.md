# PDF 阅读：线程模型、通信与问题分析

> 基于 `PdfReadingActivity` 及相关模块源码整理（`reader` 工程）。  
> 目标：说明 PDF 阅读页有哪些执行路径、如何协作、以及当前架构下易出 bug 的点。

---

## 1. 总览

PDF 阅读同时使用 **两套解析/渲染引擎**，跑在 **多条线程** 上，通过 **锁 + 缓存 + 主线程回投** 协作：

| 引擎 | 类 | 用途 |
|------|-----|------|
| **PdfRenderer**（Android 系统） | `android.graphics.pdf.PdfRenderer` | 页面位图渲染（连续列表 / 单页 / OCR 截图） |
| **PDFBox**（`tom_roush`） | `PDDocument` via `PdfTextExtractor` | 文字提取、书内链接、目录大纲 |

二者 **互不共享内部状态**，各自有独立锁：

- `PdfReadingActivity.renderLock` → 串行访问 **唯一** `PdfRenderer` / `currentPage`
- `PdfTextExtractor.sessionLock` → 串行访问 **唯一** 会话 `PDDocument`

```
┌─────────────────────────────────────────────────────────────────┐
│                        主线程 (Main / UI)                         │
│  触摸 · RecyclerView 滚动 · bind · 改 View · 进度 · 菜单 · TTS UI │
└──────────┬──────────────────────────────▲────────────────────────┘
           │ post / runOnUiThread / 协程恢复 │
           ▼                              │
┌──────────────────────┐    ┌─────────────────────────────────────┐
│ pdf-tile-render      │    │ Kotlin 协程池                         │
│ (单线程 Executor)     │    │  Dispatchers.IO / Default              │
│ 矮页整图 + 长图 tile  │    │  打开 FD · 抽字 · 链接 · 大纲 · OCR   │
│ 全部 PdfRenderer 渲染 │    │  (PDFBox / 文件 / TFLite)             │
└──────────┬───────────┘    └──────────────────┬──────────────────┘
           │ synchronized(renderLock)          │ synchronized(sessionLock)
           ▼                                   ▼
    PdfRenderer + PFD                    PDDocument (会话)
```

---

## 2. 线程清单

### 2.1 固定 / 长期存在

| # | 名称 | 创建方式 | 生命周期 | 主要工作 |
|---|------|----------|----------|----------|
| 1 | **主线程 (Main)** | Android UI | Activity 全程 | 布局、滚动、触摸、bind 高度、挂 Bitmap、写进度、TTS 回调 UI |
| 2 | **pdf-tile-render** | `Executors.newSingleThreadExecutor`，线程名 `pdf-tile-render`，daemon | Activity 创建 → `onDestroy` 时 `shutdownNow()` | 矮页整图渲染、长图 tile 条带渲染、`ensurePageSize`（经 `renderLock`） |
| 3 | **协程 Main** | `lifecycleScope.launch { ... }` 默认 | 随 Job | 编排异步任务、回主线程更新状态 |
| 4 | **Dispatchers.IO 池** | `withContext(Dispatchers.IO)` | 任务级 | 打开 `ParcelFileDescriptor`、PDFBox 抽字/链接/大纲、OCR 流程中的 IO 段 |
| 5 | **Dispatchers.Default 池** | `withContext(Dispatchers.Default)` | 任务级 | OCR 引擎创建（`TfliteOcrEngine`）等偏 CPU 工作 |

### 2.2 附属 / 系统侧

| # | 名称 | 说明 |
|---|------|------|
| 6 | **Main Handler** | `clockHandler`（时钟 30s）、`jumpPreviewHandler`（跳页预览防抖）；`TtsManager.mainHandler`（TTS 状态/超时） |
| 7 | **TTS 引擎线程** | 系统 `TextToSpeech` 内部回调线程；经 `mainHandler.post` 回到主线程再调 `listener` |
| 8 | **RecyclerView 预取 / Choreographer** | 系统滚动与帧回调，仍在主线程；会间接触发 `onBind` / `onScrolled` |

> **没有** 独立的「文字提取线程」或「链接线程」类；这些都是 **lifecycleScope + Dispatchers.IO** 临时占池线程。

---

## 3. 各线程职责细节

### 3.1 主线程

- 打开 PDF 后：`restorePdfViewState` / `scrollToPositionWithOffset`
- 连续模式：`PdfPageAdapter.onBindViewHolder` → `bindPageSurface`
  - **同步**：`ensurePageSize`（若缓存未命中会抢 `renderLock`）、`surface.bind`（**定死页高**）
  - **异步投递**：矮页 `enqueueFullPageRender`；长页 `ensureTilesForVisible` → `onNeedTile` → `enqueueTileRender`
- 单页模式：`showSinglePage` 内 **仍同步** `synchronized(renderLock)` 整页渲染（会卡 UI）
- 滚动：`onScrolled` 更新 `pageIndex`、存进度、刷 tile、刷新选区/TTS 高亮
- 触摸：`ZoomableFrameLayout`（缩放/侧边翻页/水平滑翻页）→ 子 View（RV 滚动）

### 3.2 pdf-tile-render（单线程队列）

所有 **PdfRenderer 画 Bitmap** 的异步路径都进这一条队列（串行，避免多线程同时 openPage）：

1. `enqueueFullPageRender`：矮页整图  
2. `enqueueTileRender`：长图某一 tile 条带  
3. 任务内：`synchronized(renderLock) { openPage → render → close }`  
4. 结果：`runOnUiThread { 校验 bindGeneration → setFullBitmap / setTile }`

**注意**：主线程的 `showSinglePage`、`ensurePageSize`（缓存未命中）、`ocrOnePage` 也会抢 **同一把** `renderLock`，与本线程 **互斥**，不是「只在 tile 线程用 Renderer」。

### 3.3 协程 + Dispatchers.IO / Default

| 任务 | 入口 | 线程 | 结果如何回 UI |
|------|------|------|----------------|
| 打开文件描述符 | `loadPdf` | IO | 协程回到 Main 建 `PdfRenderer` |
| 近页抽字 | `startNearbyTextExtraction` / `prefetchNearbyText` | IO（PDFBox） | Main：`rawPageCache` + `rebuildTextFromCache` |
| 书内链接 | `loadPdfLinksAsync` | IO | Main：`pageLinks = links` |
| 目录大纲 | `preloadOutlineAsync` / 打开 TOC | IO | Main：`outlineRoots` |
| OCR | `ocrJob` | Default 建引擎 + IO 跑页 | Main：进度 Dialog、缓存、Toast |
| 书签预览补全等 | 若干 `lifecycleScope.launch` | IO | Main 更新 BookmarkStore |

### 3.4 TTS

- `TtsManager`：系统 TTS 回调线程 → `mainHandler.post` → `listener`（Activity 再 `runOnUiThread` 保险）
- 朗读高亮：Main 上 `applyTtsSentenceHighlight` → `scrollToCharRange`（可能改 RV 滚动）

---

## 4. 通信方式（线程间如何说话）

### 4.1 机制一览

| 机制 | 方向 | 用途 |
|------|------|------|
| **`tileExecutor.execute { }`** | Main → render 线程 | 投递渲染任务 |
| **`runOnUiThread { }` / `view.post { }`** | 后台 → Main | 挂 Bitmap、刷列表、恢复滚动 |
| **`lifecycleScope.launch` + `withContext`** | Main ↔ IO/Default | 结构化异步；取消随 Activity |
| **`Handler(Looper.getMainLooper())`** | 任意 → Main | 时钟、跳页预览、TTS |
| **共享内存 + 锁** | 多线程读写同一对象 | 见下节 |
| **`bindGeneration` / `pageIndex` 校验** | 无锁世代号 | 丢弃过期渲染结果，防止错页贴图 |
| **`isFinishing` / `isDestroyed` 守卫** | 后台回 UI 前 | 避免泄漏与崩溃 |
| **Job 取消** | `extractJob` / `ocrJob` | 新任务覆盖或用户取消 |

### 4.2 共享状态与同步原语

```
┌─ renderLock ─────────────────────────────────────────────┐
│  renderer: PdfRenderer?                                   │
│  currentPage: PdfRenderer.Page?  （同时只能 open 一页）     │
│  rendererPageSize: HashMap（读多写少，写入在锁内）          │
│  使用者：tile 线程、Main(showSinglePage/ensurePageSize)、  │
│          OCR 的 ocrOnePage                                │
└──────────────────────────────────────────────────────────┘

┌─ sessionLock (PdfTextExtractor) ─────────────────────────┐
│  sessionDoc: PDDocument?                                  │
│  sessionKey: String?                                      │
│  使用者：全部 Dispatchers.IO 上的抽字/链接/大纲            │
│  注意：持锁时跑 extract 可能较久，IO 任务会排队            │
└──────────────────────────────────────────────────────────┘

┌─ 主线程为主的缓存（跨线程读需小心） ──────────────────────┐
│  bitmapCache (LruCache)     矮页整图，tile 线程 put，Main 用│
│  tileCache / tilePinned     长图块；淘汰时 pin 防 recycle  │
│  rawPageCache / pageChars   文字；IO 写完后 Main 重建      │
│  pageLinks / outlineRoots   IO 完成后 Main 赋值            │
│  pageIndex / pageMode       仅应在 Main 改                 │
└──────────────────────────────────────────────────────────┘
```

### 4.3 典型数据流

#### A. 连续模式：滑到新页（矮页）

```
Main: onBind → ensurePageSize → surface.bind(定高)
    → enqueueFullPageRender(page, surface, bindGen)
         │
         ▼
pdf-tile-render: renderLock 内 render 整图
    → bitmapCache.put
    → runOnUiThread
         │
         ▼
Main: if pageIndex==page && bindGeneration==bindGen
    → surface.setFullBitmap(bmp)
```

#### B. 连续模式：长图 tile

```
Main: onScrolled / bind → refreshVisiblePageTiles
    → surface.ensureTilesForVisible → onNeedTile
    → enqueueTileRender(...)
         │
         ▼
pdf-tile-render: render 条带 → tileCache.put → pin
    → runOnUiThread → deliverTile（再校验 generation）
```

#### C. 打开书：抽字 + 链接（与渲染并行）

```
Main: loadPdf 成功
  ├─ startNearbyTextExtraction  ──IO──► openSession + extractPagesRaw
  │                                      ──Main──► rawPageCache + rebuildTextFromCache
  └─ loadPdfLinksAsync          ──IO──► extractLinksFromSession
                                         ──Main──► pageLinks
```

两路都抢 `sessionLock` 上的同一 `PDDocument`，会 **串行化**，但与 `PdfRenderer` **无直接耦合**。

#### D. OCR

```
Main: 用户确认页范围
  → ocrJob
       Default: 建 TfliteOcrEngine
       IO: 每页 ocrOnePage
            → renderLock 渲 Bitmap（与 tile 线程争用！）
            → 引擎识别 → 写 PdfOcrCacheStore
       Main: 进度 UI、合并缓存、rebuildTextFromCache
```

---

## 5. 关键时序（打开一本 PDF）

```
t0  Main: loadPdf 显示 Loading，内容 alpha=0
t1  IO:   openFileDescriptor
t2  Main: new PdfRenderer(fd)，算 pageIndex，post 恢复滚动
t3  Main: 下一帧露出内容，allowProgressSave=true
t4  并行:
      · tile 线程：可见页/邻页渲染
      · IO：openSession(整文件读入内存) + 近页抽字
      · IO：extractLinks（依赖 session）
      · IO：preloadOutline（依赖 session）
```

首屏依赖 **PdfRenderer**；文字/链接/目录依赖 **PDFBox 会话**，就绪前选字/TTS/链接可能为空。

---

## 6. 与线程相关的 Bug 形态（为何「PDF bug 很多」）

下列问题多数不是「单纯逻辑写错」，而是 **多线程 + 双引擎 + 可变高度列表** 的组合效应。

### 6.1 主线程阻塞 / 滚动回弹

| 现象 | 机制 |
|------|------|
| 滑页卡顿、闪白 | `onBind` 里曾同步整页 render；或 `ensurePageSize` 与 tile 抢 `renderLock` 等待 |
| 从 N 滑到 N-1 又弹回 N | ① 竖滑松手被水平翻页误触（`trySwipePageTurn` 看瞬时速度）；② 滚动中 `requestLayout`/改 item 高度；③ itemAnimator 动画拽位 |
| 打开先闪第 1 页再跳进度 | `setPageCount` 过早 bind 0；现用 alpha=0 + post 恢复缓解 |

### 6.2 渲染与 View 生命周期错位

| 现象 | 机制 |
|------|------|
| 错页贴图 | 异步结果回 UI 时未校验 `bindGeneration` / `pageIndex`（当前矮页/tile 路径已校验） |
| 白页 / 花屏 | `bitmapCache`/`tileCache` 淘汰时 `recycle`，但 View 仍持引用绘制；tile 用 `tilePinned` 缓解，矮页 Lru 淘汰仍可能 recycle |
| 离开页面崩溃 | `runOnUiThread` 未判 `isDestroyed`；`onDestroy` 后仍投递 |

### 6.3 双引擎竞态与重复工作

| 现象 | 机制 |
|------|------|
| 打开后长时间无选字/TTS | `openSession` 读大文件占 IO；与链接/大纲排队 `sessionLock` |
| 抽字与渲染「各干各的」 | 无统一优先级；快速滑页时 render 队列堆积，旧页任务仍执行到半途才靠 generation 丢弃 |
| 坐标对不齐 | PdfRenderer 页尺寸 vs PDFBox 坐标；靠 `rendererPageSize` + 切边过滤对齐，奇偶镜像切边更易偏 |

### 6.4 OCR 与阅读争用

| 现象 | 机制 |
|------|------|
| OCR 时列表卡顿/白块 | `ocrOnePage` 在 IO 线程持 `renderLock` 长时间渲染，tile/整图排队 |
| 进度乱 | Dialog 在 Main 更新，页循环在协程；取消时 `isActive` 与 finally dismiss 竞态 |

### 6.5 TTS 与滚动互相打断

| 现象 | 机制 |
|------|------|
| 朗读时页面乱跳 | TTS 高亮 `scrollToCharRange` 与用户手势滚动同时改 RV |
| 句界错乱 | 抽字 Job 完成 `rebuildTextFromCache` 时段落索引变化（`preserveTtsPosition` 缓解） |

### 6.6 可变高度 RecyclerView

| 现象 | 机制 |
|------|------|
| 滚动百分比不准 | `computeVerticalScrollOffset` 对未布局 item 用估算高度 |
| 恢复进度偏差 | 存的 `scrollY` 与再次打开时高度缓存不一致 |
| 滑上突然跳动 | 回收 View 复用后页高从「上一页高度」改到「本页高度」触发 relayout |

### 6.7 手势层与列表层双消费

| 现象 | 机制 |
|------|------|
| 竖滑后又翻页 | `ZoomableFrameLayout` 在 UP 上判断水平翻页，同时 RV 已消费竖滑 |
| 缩放后竖滑异常 | `continuousScrollWhenZoomed` 用 `onPanOverscroll` 转 `scrollBy`，与 scale 后坐标系换算误差 |

---

## 7. 锁与线程安全速查

| 资源 | 保护 | 可在哪些线程访问 | 风险 |
|------|------|------------------|------|
| `PdfRenderer` / `currentPage` | `renderLock` | Main + pdf-tile-render + OCR(IO) | 持锁过久卡其它路径；Android 文档要求串行 |
| `PDDocument` session | `sessionLock` | 主要是 IO 协程 | 持锁抽多页会堵链接/大纲 |
| `bitmapCache` | 无显式锁（LruCache 自身同步） | put 多在 tile 线程，get 在 Main | recycle 与绘制竞态 |
| `tileCache` + `tilePinned` | Concurrent 结构 + 主线程 deliver | tile 线程 put/pin，Main unpin | 逻辑复杂，易漏 pin |
| `rawPageCache` / `pageLinks` | 约定「仅 Main 写结果」 | IO 算出后 Main 赋值 | 勿在 IO 直接改 UI 状态 |
| `pageIndex` | 应仅 Main | — | 后台勿读来驱动 UI 跳转 |

---

## 8. 架构评价与改进方向（简）

### 现状优点

- 长图 tile + 字节上限 Lru，控制内存
- `bindGeneration` 丢弃过期帧，方向正确
- PDFBox 会话复用，避免每抽一页整本 load
- 文字懒加载，不挡首屏 PdfRenderer

### 结构性问题

1. **一个 PdfRenderer + 一把大锁**，阅读渲染、单页模式、OCR、取尺寸全挤一条队列  
2. **渲染队列无优先级/取消**：快速滑页时旧页仍占满 `pdf-tile-render`  
3. **双引擎双锁**，打开路径任务多、顺序依赖 session，难预期就绪时间  
4. **主线程仍可能 `ensurePageSize` / `showSinglePage` 同步持锁**  
5. **手势（ZoomableFrameLayout）与 RV 滚动** 职责重叠，易误触翻页  

### 建议优先级（供后续改 bug 参考）

1. **渲染队列可取消**：只保留「当前可见 ±1」任务；或按 page 距离排序  
2. **`ensurePageSize` 预取**到 tile 线程，Main bind 只读缓存，避免 onBind 抢锁  
3. **单页模式**也走异步渲染 + 占位，避免 Main 同步 `renderPageBitmap`  
4. **OCR 专用**降优先级或独立渲染路径，避免长时间霸占 `renderLock`  
5. **页高缓存**写入 Adapter/`LayoutManager`，减少可变高度跳动  
6. **统一「UI 状态机」**：用户拖动 / 程序 `restorePosition` / TTS 跟读 三选一占有滚动权  

---

## 9. 关键源码索引

| 主题 | 位置 |
|------|------|
| tile 线程与缓存常量 | `PdfReadingActivity` companion + `tileExecutor` |
| `renderLock` / 缓存 | `PdfReadingActivity` 字段区 |
| 打开与并行后台任务 | `loadPdf` → `startNearbyTextExtraction` / `loadPdfLinksAsync` |
| 连续 bind / 异步整图 / tile | `bindPageSurface` / `enqueueFullPageRender` / `enqueueTileRender` |
| 页表面与 generation | `ui/PdfPageSurface.kt` |
| 手势与水平翻页 | `ui/ZoomableFrameLayout.kt` |
| PDFBox 会话与锁 | `data/PdfTextExtractor.kt`（`sessionLock`） |
| 链接 / 大纲 | `data/PdfLinkIndex.kt`、`data/PdfOutlineLoader.kt` |
| TTS 回主线程 | `tts/TtsManager.kt`（`mainHandler`） |

---

## 10. 一句话总结

PDF 阅读页本质是：

> **主线程管交互与布局**，  
> **单线程 `pdf-tile-render` + `renderLock` 独占系统 PdfRenderer 出图**，  
> **协程 IO 池 + `sessionLock` 独占 PDFBox 做字/链/目录**，  
> 结果一律 **`runOnUiThread` / 协程回 Main**，用 **`bindGeneration` 防贴错页**。

Bug 多，是因为这三条路径共享滚动位置、页高、缓存和用户手势，**缺少统一的优先级与取消策略**，任意一侧的延迟或误触发都会表现成「跳页、白屏、卡顿、选不了字」。
