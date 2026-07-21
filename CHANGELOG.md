# Changelog / 更新日志

## 1.0.4 — 2026-07-21

### English

**Fonts**
- Default reading font = system default; **removed bundled commercial typeface** (former FZ Zhengxian embed)

**TXT**
- Reading progress always shows **full-document percentage** (chapter TOC does not switch the bottom progress to “Ch. n/m”)

**App**
- Settings → About → **License** button (MIT + third-party notice, scrollable)
- License files / README clarify MIT covers original source only

### 中文

**字体**
- 默认阅读字体 = 系统默认；**移除内嵌商业字体**（原方正正纤黑）

**TXT**
- 阅读进度始终显示**全文百分比**（有目录时底栏进度也不改为「第 n/m 章」）

**应用**
- 设置 → 关于 → **许可证**按钮（MIT + 第三方说明，可滚动）
- 许可证文件 / README 明确 MIT 仅覆盖自有源码

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
- 朗读初始化/未就绪不再连弹 Toast（状态只在 TTS 栏）
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
