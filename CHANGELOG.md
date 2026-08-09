# Changelog / 更新日志

## 1.0.6 — 2026-07-27 ~ 2026-07-28

### English

#### New
- **Auto-close**: settings option (default **1 hour**; 0 = off). After idle UI time, stop TTS and close the app. Touch / keys, notification pause-resume, and headset pause-resume **reset the timer**

#### Changed / fixed
- **PDF single-page mode**: left/right side tap (and swipe / volume keys) always turns **one full page**, even on tall pages — no in-page screen scroll first (vertical pan still works for reading long pages)
- **PDF pinch-zoom**: start scaling as soon as the second finger lands (bypass system min-span dead zone that ignored the first part of the pinch)
- **Volume keys while TTS is active** (speaking or paused): adjust system media volume instead of page turn (TXT / EPUB / MOBI / PDF)
- **TTS volume pumping**: use media/music audio attributes (avoid SPEECH AGC); pin `volume=1.0` and `STREAM_MUSIC` on every utterance
- **PDF long-press vs pan**: hold still **1 s** to select text; **any move before long-press is pan** (cancels pending select); after a short hold then drag, continuous mode scrolls **immediately** (no hitch)
- **TTS “read from selection” while speaking**: interrupt current sentence and **jump to the selected paragraph / offset** (ebook “Read from here”; PDF “Read selection”)

### 中文

#### 新增
- **自动关闭时间**：设置项（默认 **1 小时**；0 = 禁用）。界面无操作超时后退出朗读并关闭程序。触摸/按键、通知栏暂停继续、耳机暂停继续会 **重新计时**

#### 修改 / 修复
- **PDF 单页模式**：左右侧点（及滑动/音量键）始终 **整页翻 1 页**，超长图也不再先页内滚屏（页内浏览仍可用竖向拖动）
- **PDF 双指缩放**：第二指落下即开始缩放（绕过系统 minSpan 死区，避免前半段捏合无反应）
- **TTS 朗读/暂停中**：音量键改为调节系统音量，不再翻页（电子书与 PDF）
- **TTS 音量时大时小**：朗读属性改为媒体音乐流（避免 SPEECH 路径 AGC）；每次 speak 固定 `volume=1.0` 与 `STREAM_MUSIC`
- **PDF 长按选字与 pan**：需**静止按住 1 秒**才选字；**长按触发前移动一律 pan**（取消待选字）；按住片刻再拖时连续模式**立即跟手滚动**（消除顿挫）
- **朗读中选区起读**：电子书「从本段开始朗读」、PDF「朗读选区」会**打断当前句并跳到选区起点**继续往下读

---

## 1.0.5 — 2026-07-24 ~ 2026-07-26

### English

#### New
- **Custom TOC**: wildcard **`?`** matches a single character (e.g. `第?回 *`); preset “第?回”
- **Custom TOC**: pattern is **saved per book** and restored when reopening the dialog for re-edit (spinner no longer overwrites the saved pattern)
- **Imported background image**: **opacity** slider (0–100%); **background color** as a solid underlay beneath the image; **Stretch** vs **Fit center** scale mode
- **Highlight colors**: 8 presets (red / green / blue / yellow / purple / orange / black / pink) + **custom HSV picker**
- **Notes list**: sequence number, add time, reading progress; sorted by progress; title format `N. excerpt…`

#### Changed / fixed
- **Imported background image**: stretch to **full screen** (no vertical tiling / double image); only drawn on the root reading layout
- **Imported background image**: extends into the **bottom status bar**; body text is clipped above the bar
- **Imported background image**: bottom status bar (battery, clock, progress) uses the underlay color so body text no longer shows through
- **Tap zones**: left/right page-turn areas **25%** each (was 33%); **note bubble** has a larger hit area and no longer triggers page turn; tap highlight text opens note preview; bubble tint matches highlight color
- **Highlight underline**: drawn closer to the text baseline
- **PDF pinch-zoom**: second finger cancels long-press selection; pinch clears an active text selection (easier zoom on text pages)
- **Style toggle buttons**: remove stray `?` before selected labels (encoding / checkmark)
- **PDF open crash**: initialize TTS **before** export panel setup (`tts` lateinit)
- **PDF modularization**: more controllers (document load/close, page bind/tiles, text extract, chrome/mode/OCR UI); Activity slimmed; submodules hold **Activity references** (no Host narrow interfaces)
- **TXT modularization**: `ReadingActivity` split into `txt/` controllers (highlight, chrome, nav/bookmarks, TTS, settings, load, manga) — same pattern as PDF
- **Architecture**: remove remaining custom **interface**s — use function callbacks / `abstract class` / `Callbacks` data holders (system Android listeners unchanged)
- **TTS rate label** (TXT bar): fix garbled characters after the rate (e.g. `1×` showed mojibake) — multiplication sign encoding
- **TTS bar dismiss** (TXT / EPUB / MOBI): when speech ends and the control bar hides, body text under the former bar **redraws immediately** (no blank strip until you scroll)

### 中文

#### 新增
- **自定义目录**：通配符 **`?`** 匹配单个任意字符（如 `第?回 *`）；预设「第?回」
- **自定义目录**：**按书保存** 扫描模式，再次打开对话框可回填再编辑（不再被示例 Spinner 覆盖）
- **导入背景图**：可调 **图片透明度**（0–100%）；**背景颜色** 作为图片下层垫色；**拉伸** / **适应居中** 缩放模式
- **高亮颜色**：红 / 绿 / 蓝 / 黄 / 紫 / 橙 / 黑 / 粉 8 种纯色 + **自定义 HSV 取色**
- **笔记列表**：显示序号、添加时间、正文进度；按正文进度排序；标题 `序号. 摘录…`

#### 修改 / 修复
- **导入背景图**：拉伸铺满 **全屏**（不再竖向平铺成两张）；仅画在阅读根布局
- **导入背景图**：延伸到底部 **状态栏** 区域；正文在底栏上方裁剪，不进入状态栏
- **导入背景图**：底栏（电量、时钟、进度）使用垫色不透明背景，正文不再透出遮挡
- **点击区域**：左右翻页区各 **25%**（原 1/3）；**备注气泡** 扩大命中区、点击不翻页；点高亮文本直接打开备注；气泡颜色与高亮一致
- **高亮下划线**：贴近文字基线绘制
- **PDF 双指缩放**：第二指落下取消长按选区；捏合时清除文字选区（有字页更容易缩放）
- **样式切换按钮**：去掉选中项前乱码 `?`（勾选符编码问题）
- **打开 PDF 闪退**：TTS 在导出面板初始化之前创建（`tts` 未初始化）
- **PDF 模块化**：文档打开/关闭、页绑定与 tile、文字抽取、chrome/模式/OCR UI 等控制器接入；Activity 瘦身；子模块直接持有 **Activity 引用**（去掉 Host 窄接口）
- **TXT 模块化**：`ReadingActivity` 拆分为 `txt/` 下多控制器（高亮、chrome、目录/书签、TTS、设置、加载、漫画），结构与 PDF 一致
- **架构**：去掉剩余自定义 **interface**，改用函数回调 / `abstract class` / `Callbacks` 持有回调（系统 Android 监听接口不变）
- **TTS 语速标签**（电子书控制条）：修复 `1×` 后乱码（乘号编码错误）
- **结束朗读收起控制条**（TXT / EPUB / MOBI）：原控制条遮挡区域的正文 **立即重绘显示**（无需再滚动一下）

---

## 1.0.4 — 2026-07-22 ~ 2026-07-23

### English

#### New
- **MOBI**: Text / single-image / continuous view modes
- **Custom TOC scan** (TXT / EPUB / MOBI): wildcard patterns in the TOC sheet — `*` any text, `x` digit or Chinese numeral, `xxx` / `xxxx` for `001` / `0001`; presets like `第x章 *`, `卷第x *`, `Chapter x`; saved per book
- **Text selection handles** (PDF / TXT / EPUB / MOBI text): two draggable handles at selection ends; drag to top/bottom edge to auto-scroll and extend selection (gradual speed ramp)

#### Changed / fixed
- **MOBI (Chinese UTF-8)**: fix mis-decoded Chinese MOBI — trust `encoding=65001`, repair PalmDOC HTML damage (NUL / `height` fragments); parse cache v5
- **MOBI load**: after open, **prefetch the full book in the background** (like EPUB); top-left load % clears when done; chunk + full parse cache
- **Custom TOC**: matching lines get chapter title styling; fix layout overflow right after scan (invalidate layout cache when `isChapter` changes)
- **PDF / MOBI continuous images**: on portrait ↔ landscape, keep **zoom (width ratio)** and **horizontal pan ratio** (no reset to 1×)
- **Orientation**: hard-lock **portrait / landscape** (no large-screen FULL_SENSOR-only layout, no software 90° content rotate)
- **TOC sheet**: vertical scroll no longer steals horizontal tab swipe (目录 ↔ 书签); fix MIUI crash on TOC open
- **Shelf long-press menu**: correct X position; white background restored
- **Pinch release jump**: after pinch, block leftover single-finger pan/fling until the next down (manga single + PDF)
- **Tall-page OCR** (continued): screen-height strips + overlap merge; contrast / invert retries; detect **partial** tall-page results and re-OCR in strips instead of skipping; OCR dialog lists “top strip only” pages
- **PDF continuous long pages**: fix wrong scroll on open (prefetch page heights, height-table seek, scroll compensation when tiles grow)
- **PDF OCR progress**: fix black bar at former bottom-menu area while the progress dialog is shown
- **Selection handles**: dragging handles no longer triggers PDF pan; edge auto-scroll speed halved with slower ramp-up
- **TXT selection**: handles appear right after long-press (no extra scroll needed)
- Edge font size: **0.5sp** steps, **10px** edge
- **PDF**: TTS clears the TTS bar; layout panel elevation / insets

### 中文

#### 新增
- **MOBI**：正文/单图/连续图模式
- **自定义目录扫描**（TXT / EPUB / MOBI）：目录面板内通配符 — `*` 任意内容、`x` 数字或中文数字、`xxx`/`xxxx` 表 `001`/`0001`；示例 `第x章 *`、`卷第x *`、`Chapter x`；按书记忆
- **文本选区手柄**（PDF / TXT / EPUB / MOBI 正文）：选区两端拖动手柄，拖到上下边缘自动滚屏扩选（缓加速）

#### 修改 / 修复
- **MOBI 中文 UTF-8**：修复部分中文 MOBI 乱码 — 信任 `encoding=65001`、修补 PalmDOC 解压后 HTML 损伤（NUL、`height` 碎片）；解析缓存 v5
- **MOBI 加载**：打开后**后台预载全书**（与 EPUB 一致）；左上角加载百分比完成后消失；分块 + 全书解析缓存
- **自定义目录**：匹配行显示章节标题样式；扫描后立即应用时修复排版溢出（`isChapter` 变化时刷新 layout 缓存）
- **PDF / MOBI 连续图**：竖屏 ↔ 横屏保持 **缩放（相对屏宽）** 与 **水平平移比例**（不再重置为 1×）
- **视角**：真正锁定 **竖屏 / 横屏** 窗口方向（取消大屏仅 FULL_SENSOR、取消软件 90° 转内容）
- **目录面板**：竖滚不再误触横滑切换（目录 ↔ 书签）；修复 MIUI 上点目录崩溃
- **书架长按菜单**：X 坐标修正、白底恢复
- **松手跳动**：双指结束后到下一次按下前，禁止剩余单指 pan / fling（漫画单图 + PDF）
- **超长页 OCR**（延续）：按屏高分块 + 交叠合并；对比度/反色重试；识别 **局部覆盖** 的竖长页会分块重识而非跳过；对话框标注「长图仅上部」页
- **PDF 连续长页**：修复打开时滚动错位（预取页高、按高度表定位、页高变化时 scroll 补偿）
- **PDF OCR 进度框**：识别过程中原底栏区域黑条
- **选区手柄**：拖手柄不再触发 PDF 平移；边缘自动滚动减半并缓加速
- **TXT 选字**：长按选中后手柄立即显示（无需再滑一下）
- 侧边字号：**0.5sp**、**10px** 边
- **PDF**：TTS 避让控制栏；排版面板层级/留白

---

## 1.0.3 — 2026-07-21

### English

**PDF**
- Continuous TTS: highlight follows page scroll while speaking
- Paragraph merge: more tolerant line spacing / font-size differences so broken lines join into paragraphs

**Reading style**
- Remove solid theme chips; background is textures / solid color / imported image
- Preset background textures (paper, kraft, linen, grid, dots, parchment, night grain)
- Import custom background image
- Solid background color: circular presets + **Custom** (HSV picker)
- Text color: circular presets + **Custom** (HSV picker)
- Style panel scrolls; max height ~78% of screen so bottom controls stay reachable
- Speech rate removed from style panel (TTS bar only)

**Fonts**
- Install custom TTF/OTF fonts; long-press chip to uninstall

**TTS**
- No toast spam for TTS init / not ready (status on TTS bar only)
- Speech export: progress dialog with part/char counts, elapsed, ETA; live progress while synthesizing
- Full-screen image viewer: no double-tap zoom (pinch still works)

**EPUB / MOBI / TXT**
- EPUB/MOBI progress shows **“Ch. n/m  xx%”**; TXT uses full-document percentage
- On-demand load (no full background parse on open); touch near end / jump continues loading
- Lightweight **chapter index** cache; skip huge full-parse cache to avoid freezes
- **Per-spine disk cache** + seek-load to restore/jump position (fewer UI thrash; faster reopen)
- Open book: keep loading overlay until target position is ready — **no flash of first page**, then jump straight there
- TOC sheet: **scroll to current chapter** on open
- Chapter title styling: TOC jump targets / headings use **larger font + extra vertical padding** (no injected title lines, no “章节n/m” prefix; legacy prefixes stripped)
- **MOBI manga mode** (Style → View mode): ignore text, one image at a time, pinch-zoom, side-tap / swipe page turn; progress **image n / total**
- Manga mode UI: selected filled theme color + “✓”, badge **Now: Text/Manga**; panel stays open after switch
- Image-only MOBI (no real text, has images): **auto-enter manga mode** on open
- Pure-image MOBI can open even without parseable body text
- **TXT TOC**: prefer `01.` / `001.` / `0001.` / `00001.` numbered titles; if ≥2 matches, do **not** use “第x章” patterns
- Prev/next chapter: **keep bottom menu open** (same as PDF prev/next page)

**Bookshelf**
- List UI: CX-style rows (no card border, tighter spacing, multi-select checkbox on the right)
- Linked folder file list: hide relative-path second line
- Long-press book/file: **Details** (name, path, format, size, encoding, progress, last read, URI)
- Reading history: correct file extension for MOBI/EPUB (no longer mislabeled as `.txt`); progress stores extension when needed

**App**
- Check for updates from GitHub Releases (download APK and install)
- **Volume keys turn pages** (default on: Vol− next, Vol+ previous; toggle in settings)
- README: plainer wording; no package name / hardcoded version; no class-name dumps
- Remove **idle-exit** setting (keep idle screen-off only)
- **UI color themes** (16 skins, music-player style): Settings → Appearance → Color theme
- **License**: original source code under MIT (`LICENSE`); third-party libs / models keep their own terms

### 中文

**PDF**
- 连续朗读：高亮随页面滚动跟随
- 段落合并：行距/字号差异更宽松，断行更易并成整段

**阅读样式**
- 去掉「主题」色条；背景改为纹理 / 纯色 / 导入图片
- 预设背景纹理（纸纹、牛皮纸、亚麻、网格、点点、羊皮、夜色颗粒）
- 支持导入自定义背景图
- 背景纯色：圆形预设色 + **自定义**（HSV 选色器）
- 字体颜色：圆形预设色 + **自定义**（HSV 选色器）
- 样式面板可滚动，高度约屏高 78%，避免底部被裁切
- 语速从样式面板移除，仅在 TTS 栏调节

**字体**
- 可安装自定义 TTF/OTF；长按芯片卸载

**朗读**
- 朗读初始化/未就绪不再连弹 Toast（状态仅在 TTS 栏）
- 语音导出：进度对话框显示分段/字数、已用时、预计剩余；合成中实时刷新
- 全屏看图：取消双击缩放（双指缩放仍可用）

**EPUB / MOBI / TXT**
- EPUB/MOBI 进度 **「第 n/m 章 xx%」**；TXT 用全文百分比
- 按需分批续载（打开后不再后台扫完全书）；滑到末尾 / 跳转时继续加载
- 轻量 **章节索引** 缓存；过大整本解析缓存跳过，减轻卡顿
- **按 spine 磁盘缓存** + seek 连续加载，恢复/跳转少刷 UI，二次打开更快
- 打开书籍：加载遮罩保持到目标位置就绪 — **不闪首页**，完成后直接跳到进度
- 打开目录时 **滚到当前章节**
- 章节标题样式：目录可跳转标题 / 正文标题 **加大字号 + 前后留白**（不再插入附加标题行、不加「章节n/m」前缀；旧前缀自动剥掉）
- **MOBI 漫画模式**（风格 → 浏览模式）：忽略正文，一次一张图，双指缩放，侧点/滑动翻页；进度 **第 n/m 张**
- 漫画模式 UI：选中项主题色填充 +「✓」，右上角徽章 **当前：正文/漫画**；切换后面板不关
- 无有效正文、仅有图片的 MOBI：**打开时自动进入漫画模式**
- 纯图 MOBI 无正文亦可打开
- **TXT 目录**：优先识别 `01.` / `001.` / `0001.` / `00001.` 编号标题；匹配 ≥2 处时 **不再采用「第x章」**
- 上/下一章：**不关闭底部菜单**（与 PDF 上一页/下一页一致）

**书架**
- 列表 UI：CX 风格（无卡片描边、更紧凑、多选框在右侧）
- 绑定文件夹列表：不再显示路径第 2 行
- 长按书/文件：**详情**（名称、路径、格式、大小、编码、进度、上次阅读、URI）
- 阅读历史：MOBI/EPUB 扩展名正确显示（不再误标成 `.txt`）；进度可记录扩展名

**应用**
- 设置中「检查更新」：从 GitHub Releases 下载 APK 并安装
- **音量键翻页**（默认开启：音量减下一页、加上一页；设置中可关）
- README：表述通俗化；不写包名与写死版本号；去掉类名罗列
- 去掉 **空闲退出** 设置与逻辑（仅保留空闲熄屏）
- **界面颜色主题**（16 套，对齐 music-player）：设置 → 外观 → 颜色主题
- **许可证**：自有源代码采用 MIT（见 `LICENSE`）；第三方库 / 模型仍遵循各自许可

---

## 1.0.2 — 2026-07-20

### English

**PDF**
- Continuous mode: Office-style right-edge scroll thumb (drag only; show while scrolling, hide 1s after stop)
- Scroll/render: fewer blank pages, less jank; stable seek when page heights differ
- Layout: fix squashed pages from wrong estimated height
- Gestures: reliable center-tap menu; side tap still turns pages (does not jump via thumb track)
- Render pipeline: cancellable priority queue, preview-while-scrolling, safer bitmap caching

### 中文

**PDF**
- 连续滚动：右侧 Office 风格进度手柄（仅拖动；滚动时显示，停 1 秒后消失）
- 滚动/渲染：减少白页与卡顿；页高不一致时拖动手柄更稳
- 排版：修复估算页高错误导致的图片/文字压扁
- 手势：中部点菜单更可靠；侧边点按仍上/下翻页（点轨道不跳转）
- 渲染管线：可取消优先队列、边滑边预览、缓存更安全

---

## 1.0.1

### English
- Initial public baseline: bookshelf, TXT/EPUB/MOBI/PDF, TTS, export, OCR, CN/EN UI

### 中文
- 首个公开基线：书架、TXT/EPUB/MOBI/PDF、朗读与导出、OCR、中英文界面
