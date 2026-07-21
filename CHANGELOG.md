# Changelog / 更新日志

## 1.0.2 — 2026-07-21

### English

**PDF**
- Continuous mode: Office-style right-edge scroll thumb (drag only; show while scrolling, hide 1s after stop)
- Scroll/render: fewer blank pages, less jank; stable seek when page heights differ
- Layout: fix squashed pages from wrong estimated height
- Gestures: reliable center-tap menu; side tap still turns pages (does not jump via thumb track)
- Render pipeline: cancellable priority queue, preview-while-scrolling, safer bitmap caching

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

**Bookshelf**
- Linked folder file list: hide relative-path second line

**App**
- Check for updates from GitHub Releases (download APK and install)
- **Volume keys turn pages** (default on: Vol− next, Vol+ previous; toggle in settings)
- README: plainer wording; no package name / hardcoded version; no class-name dumps

### 中文

**PDF**
- 连续滚动：右侧 Office 风格进度手柄（仅拖动；滚动时显示，停 1 秒后消失）
- 滚动/渲染：减少白页与卡顿；页高不一致时拖动手柄更稳
- 排版：修复估算页高错误导致的图片/文字压扁
- 手势：中部点菜单更可靠；侧边点按仍上/下翻页（点轨道不跳转）
- 渲染管线：可取消优先队列、边滑边预览、缓存更安全

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

**朗读 / 合成**
- TTS 初始化失败 / 未就绪不再弹 Toast（只在 TTS 栏显示状态）
- 合成语音：进度对话框（段数/字数、用时、预估剩余）；合成过程中进度实时推进
- 全屏看图：去掉双击放大（保留双指缩放）

**书架**
- 绑定文件夹列表：不再显示路径第 2 行

**应用**
- 设置中「检查更新」：从 GitHub Releases 下载 APK 并安装
- **音量键翻页**（默认开启：音量减下一页、加上一页；设置中可关）
- README：表述通俗化；不写包名与写死版本号；去掉类名罗列

---

## 1.0.1

### English
- Initial public baseline: bookshelf, TXT/EPUB/MOBI/PDF, TTS, export, OCR, CN/EN UI

### 中文
- 首个公开基线：书架、TXT/EPUB/MOBI/PDF、朗读与导出、OCR、中英文界面
