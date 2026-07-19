package com.whj.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookEncodingStore
import com.whj.reader.data.BookFileType
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.FolderImporter
import com.whj.reader.data.LinkedTreeCacheStore
import com.whj.reader.data.ReadingHistoryStore
import com.whj.reader.databinding.ActivityMainBinding
import com.whj.reader.model.LinkedDirEntry
import com.whj.reader.model.LinkedFileEntry
import com.whj.reader.model.ShelfBook
import com.whj.reader.model.ShelfFolder
import com.whj.reader.model.ShelfFolderKind
import com.whj.reader.model.ShelfItem
import com.whj.reader.model.ShelfSort
import com.whj.reader.ui.ShelfAdapter
import com.whj.reader.util.OpenFailGuide
import com.whj.reader.util.StorageAccess
import com.whj.reader.util.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ShelfAdapter
    private lateinit var bookDragHelper: ItemTouchHelper

    /** null = 顶层；否则在某书架 / 绑定文件夹内 */
    private var currentFolderId: String? = null

    /**
     * 绑定文件夹内的子路径栈（从绑定根往下）。
     * 栈为空 = 绑定根目录。
     */
    private val linkedPathStack = ArrayList<LinkedDirEntry>()

    private var didAutoResume = false

    /** 从阅读页返回后，定位到该书所在文件夹与列表项 */
    private var locateOnNextResume = false
    private var pendingFocusUri: String? = null

    /** 多选 */
    private var selectionMode = false
    private val selectedBookIds = linkedSetOf<String>()
    private var dragOrderDirty = false

    /** 书架搜书名 */
    private var searchMode = false
    private var shelfQuery: String = ""

    /** OpenDocumentTree 用途：批量导入书 vs 绑定浏览文件夹 vs 失效重授权 */
    private enum class TreePickMode { IMPORT_BOOKS, BIND_FOLDER, REAUTH_FOLDER }
    private var treePickMode = TreePickMode.IMPORT_BOOKS
    /** 重新授权时对应的绑定文件夹 id */
    private var pendingReauthFolderId: String? = null
    /** 同一会话内已对某绑定夹弹过失效引导，避免反复打扰 */
    private val linkedAccessPromptedIds = mutableSetOf<String>()

    /** OpenDocument：导入 vs 打开失败后重选 */
    private enum class DocPickMode { IMPORT, REPAIR }
    private var docPickMode = DocPickMode.IMPORT
    private var pendingRepairBookId: String? = null
    private var pendingRepairOldUri: String? = null
    private var pendingRepairTitle: String? = null

    /** 授权返回后重试打开的书架书 */
    private var pendingRetryBook: ShelfBook? = null

    private val openDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            clearDocRepairState()
            return@registerForActivityResult
        }
        if (docPickMode == DocPickMode.REPAIR) {
            handleRepairedFile(uri)
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: getString(R.string.unnamed)
        val folderId = currentVirtualShelfId()
        BookshelfStore.addOrUpdateBook(
            this,
            uri = uri.toString(),
            displayName = BookFileType.stripBookExt(name),
            folderId = folderId,
            pathHint = name,
        )
        Toasts.show(this, R.string.book_added)
        refreshShelf()
        rememberFocusAndOpen(uri.toString()) {
            openBookUri(uri, name)
        }
    }

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) {
            if (treePickMode == TreePickMode.REAUTH_FOLDER) {
                pendingReauthFolderId = null
                treePickMode = TreePickMode.BIND_FOLDER
            }
            return@registerForActivityResult
        }
        when (treePickMode) {
            TreePickMode.IMPORT_BOOKS -> importBooksFromTree(treeUri)
            TreePickMode.BIND_FOLDER -> bindLinkedFolder(treeUri)
            TreePickMode.REAUTH_FOLDER -> completeLinkedFolderReauth(treeUri)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (StorageAccess.hasAllFilesAccess()) {
            val retry = pendingRetryBook
            if (retry != null) {
                pendingRetryBook = null
                Toasts.show(this, R.string.open_failed_permission_granted_retry)
                openBookInternal(retry)
                return@registerForActivityResult
            }
        } else if (pendingRetryBook != null) {
            // 仍无全盘权限：保留待重试，给用户再引导
            val book = pendingRetryBook
            pendingRetryBook = null
            if (book != null) {
                showOpenFailGuideForBook(book, OpenFailGuide.Reason.PERMISSION)
            }
        } else if (!StorageAccess.hasAllFilesAccess()) {
            Toasts.show(this, R.string.permission_required)
        }
    }

    private var pendingViewUri: Uri? = null
    private var pendingViewName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BookshelfStore.ensureMigrated(this)

        adapter = ShelfAdapter(
            onFolderClick = { openFolder(it) },
            onBookClick = { onBookItemClick(it) },
            onLinkedDirClick = { enterLinkedDir(it) },
            onLinkedFileClick = { openLinkedFile(it) },
            onFolderLongClick = { showFolderMenu(it) },
            onBookLongPress = { book, anchor -> showBookPopupMenu(book, anchor) },
            onLinkedFileLongPress = { _, _ -> },
            onStartDrag = { vh -> bookDragHelper.startDrag(vh) },
        )
        binding.rvShelf.layoutManager = LinearLayoutManager(this)
        binding.rvShelf.adapter = adapter
        setupBookReorderDrag()

        binding.btnAddMenu.setOnClickListener { v -> showAddMenu(v) }
        binding.btnMore.setOnClickListener { v -> showMoreMenu(v) }
        binding.btnSort.setOnClickListener { toggleShelfSort() }
        updateSortButton()
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener { pullToRefresh() }
        binding.btnNavBack.setOnClickListener {
            if (selectionMode) exitSelectionMode() else navigateUp()
        }
        binding.btnHome.setOnClickListener {
            if (selectionMode) exitSelectionMode()
            goShelfHome()
        }
        binding.btnSelectionCancel.setOnClickListener { exitSelectionMode() }
        binding.btnSelectAll.setOnClickListener { selectAllBooks() }
        binding.btnSelectionMove.setOnClickListener { batchMoveSelected() }
        binding.btnSelectionRemove.setOnClickListener { batchRemoveSelected() }
        binding.btnMultiSelect.setOnClickListener { startMultiSelectMode() }
        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
            if (searchMode) closeSearchMode()
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                shelfQuery = s?.toString().orEmpty()
                if (searchMode) refreshShelf()
            }
        })
        updateSelectionUi()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        selectionMode -> exitSelectionMode()
                        searchMode -> closeSearchMode()
                        currentFolderId != null || linkedPathStack.isNotEmpty() -> navigateUp()
                        else -> finish()
                    }
                }
            },
        )

        requestStorageIfNeeded()
        ensureAllFilesAccessPrompt()
        val openedExternal = handleIncomingIntent(intent)
        refreshShelf()
        if (savedInstanceState == null && !openedExternal) {
            tryAutoResumeLastBook()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingViewUri
        if (pending != null && StorageAccess.hasAllFilesAccess()) {
            val name = pendingViewName
            pendingViewUri = null
            pendingViewName = null
            ingestAndOpenExternal(pending, name)
        }
        if (locateOnNextResume) {
            locateOnNextResume = false
            restoreShelfFocus()
        } else {
            refreshShelf()
        }
    }

    /**
     * 打开阅读前记录当前位置，返回书架时进入对应文件夹/绑定路径并滚到该书。
     * 自动恢复打开时若已在根目录，不得覆盖「上次从绑定文件夹打开」的路径。
     */
    private fun rememberFocusAndOpen(uri: String, open: () -> Unit) {
        saveShelfFocusForOpen(uri)
        pendingFocusUri = uri
        locateOnNextResume = true
        open()
    }

    private fun saveShelfFocusForOpen(uri: String) {
        val browsing = currentFolderId != null || linkedPathStack.isNotEmpty()
        if (browsing) {
            AppSettings.saveShelfFocus(
                this,
                uri = uri,
                folderId = currentFolderId,
                linkedDocIds = linkedPathStack.map { it.documentId },
                linkedNames = linkedPathStack.map { it.name },
            )
            return
        }
        // 根目录打开（含冷启动自动恢复）：优先保留同 URI 的绑定路径 / 书架 folderId
        val existing = AppSettings.lastShelfFocus(this)
        if (existing != null &&
            existing.uri == uri &&
            (existing.folderId != null || existing.linkedPathRaw.isNotBlank())
        ) {
            return
        }
        val shelfBook = BookshelfStore.findBookByUri(this, uri)
        val folderId = shelfBook?.folderId
        AppSettings.saveShelfFocus(
            this,
            uri = uri,
            folderId = folderId,
            linkedDocIds = emptyList(),
            linkedNames = emptyList(),
        )
    }

    private fun restoreShelfFocus() {
        var focus = AppSettings.lastShelfFocus(this)
        val uri = focus?.uri ?: pendingFocusUri
        pendingFocusUri = null
        // 补全虚拟书架 folderId
        if (!uri.isNullOrBlank()) {
            val shelfBook = BookshelfStore.findBookByUri(this, uri)
            if (focus == null) {
                focus = AppSettings.ShelfFocus(
                    uri = uri,
                    folderId = shelfBook?.folderId,
                    linkedPathRaw = "",
                )
            } else if (focus.folderId == null &&
                focus.linkedPathRaw.isBlank() &&
                shelfBook?.folderId != null
            ) {
                focus = focus.copy(folderId = shelfBook.folderId)
            }
        }
        if (focus != null) {
            currentFolderId = focus.folderId
            linkedPathStack.clear()
            AppSettings.parseLinkedPath(focus.linkedPathRaw).forEach { (docId, name) ->
                linkedPathStack.add(
                    LinkedDirEntry(name = name, uri = "", documentId = docId),
                )
            }
        }
        refreshShelf(scrollToUri = uri)
    }

    private fun showAddMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.open_file)
        popup.menu.add(0, 2, 1, R.string.import_folder)
        popup.menu.add(0, 3, 2, R.string.new_shelf)
        popup.menu.add(0, 4, 3, R.string.bind_folder)
        // 绑定文件夹 / 阅读历史内不允许「新建书架」等无意义项
        if (isBrowseOnlyFolder()) {
            popup.menu.findItem(3)?.isEnabled = false
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openDocLauncher.launch(
                    arrayOf(
                        "text/plain",
                        "text/*",
                        "application/pdf",
                        "application/epub+zip",
                        "application/x-mobipocket-ebook",
                        "application/octet-stream",
                    ),
                )
                2 -> {
                    treePickMode = TreePickMode.IMPORT_BOOKS
                    openTreeLauncher.launch(null)
                }
                3 -> {
                    if (currentFolderId != null) {
                        Toasts.show(this, R.string.shelf_one_level_only)
                    } else {
                        promptNewShelf()
                    }
                }
                4 -> {
                    if (currentFolderId != null) {
                        // 仅顶层可绑定
                        navigateToRootThen {
                            treePickMode = TreePickMode.BIND_FOLDER
                            openTreeLauncher.launch(null)
                        }
                    } else {
                        treePickMode = TreePickMode.BIND_FOLDER
                        openTreeLauncher.launch(null)
                    }
                }
            }
            true
        }
        popup.show()
    }

    private fun navigateToRootThen(block: () -> Unit) {
        currentFolderId = null
        linkedPathStack.clear()
        refreshShelf()
        block()
    }

    private fun importBooksFromTree(treeUri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FolderImporter.listTxtInTree(this@MainActivity, treeUri) }
            }
            result.onSuccess { imported ->
                if (imported.files.isEmpty()) {
                    Toasts.show(this@MainActivity, R.string.import_none)
                    return@onSuccess
                }
                val targetFolderId = when {
                    isInsideLinked() -> null
                    currentFolderId != null -> currentFolderId
                    else -> {
                        val existing = BookshelfStore.shelfFolders(this@MainActivity)
                            .firstOrNull { it.name == imported.folderName }
                        (existing ?: BookshelfStore.createFolder(
                            this@MainActivity,
                            imported.folderName,
                        )).id
                    }
                }
                val added = BookshelfStore.addBooksBatch(
                    this@MainActivity,
                    imported.files.map { Triple(it.uri, it.displayName, it.pathHint) },
                    targetFolderId,
                )
                val shelfName = targetFolderId?.let {
                    BookshelfStore.findFolder(this@MainActivity, it)?.name
                } ?: imported.folderName
                Toasts.show(
                    this@MainActivity,
                    getString(R.string.import_done, maxOf(added, imported.files.size), shelfName),
                    android.widget.Toast.LENGTH_LONG,
                )
                refreshShelf()
            }.onFailure { e ->
                Toasts.show(
                    this@MainActivity,
                    getString(R.string.load_failed, e.message ?: ""),
                    android.widget.Toast.LENGTH_LONG,
                )
            }
        }
    }

    private fun bindLinkedFolder(treeUri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    FolderImporter.takePersistable(this@MainActivity, treeUri)
                    val name = FolderImporter.resolveTreeDisplayName(this@MainActivity, treeUri)
                    name to treeUri.toString()
                }
            }
            result.onSuccess { (name, uriStr) ->
                val before = BookshelfStore.folders(this@MainActivity)
                    .firstOrNull { it.isLinked && it.treeUri == uriStr }
                val folder = BookshelfStore.createLinkedFolder(this@MainActivity, name, uriStr)
                if (before != null && before.id == folder.id) {
                    Toasts.show(this@MainActivity, R.string.linked_folder_exists)
                    // 已绑定：仍允许后台刷新索引
                    scanLinkedTree(folder, showStartToast = true)
                } else {
                    Toasts.show(
                        this@MainActivity,
                        getString(R.string.linked_folder_created, folder.name),
                    )
                    Toasts.show(this@MainActivity, R.string.linked_scan_background)
                    scanLinkedTree(folder, showStartToast = false)
                }
                refreshShelf()
            }.onFailure { e ->
                Toasts.show(
                    this@MainActivity,
                    getString(R.string.load_failed, e.message ?: ""),
                    android.widget.Toast.LENGTH_LONG,
                )
            }
        }
    }

    /** 后台整树扫描并写入缓存 */
    private fun scanLinkedTree(folder: ShelfFolder, showStartToast: Boolean) {
        val tree = folder.treeUri ?: return
        if (showStartToast) {
            Toasts.show(this, R.string.linked_scan_start)
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LinkedTreeCacheStore.scanEntireTree(this@MainActivity, tree) }
            }
            result.onSuccess { snap ->
                Toasts.show(
                    this@MainActivity,
                    getString(R.string.linked_scan_done, snap.allFiles().size),
                    android.widget.Toast.LENGTH_LONG,
                )
                linkedAccessPromptedIds.remove(folder.id)
                // 若仍在看该绑定树或顶层，刷新数量/列表
                refreshShelf()
            }.onFailure { e ->
                if (FolderImporter.isAccessFailure(e)) {
                    promptLinkedFolderReauth(folder, force = true, detail = e.message)
                } else {
                    Toasts.show(
                        this@MainActivity,
                        getString(
                            R.string.linked_scan_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                        android.widget.Toast.LENGTH_LONG,
                    )
                }
            }
        }
    }

    /** 弹出绑定文件夹失效重授权引导 */
    private fun promptLinkedFolderReauth(
        folder: ShelfFolder,
        force: Boolean = false,
        detail: String? = null,
    ) {
        if (!folder.isLinked) return
        if (!force && folder.id in linkedAccessPromptedIds) {
            Toasts.show(this, R.string.linked_folder_access_lost)
            return
        }
        linkedAccessPromptedIds.add(folder.id)
        val msg = buildString {
            append(getString(R.string.linked_folder_access_message, folder.name))
            if (!detail.isNullOrBlank()) {
                append("\n\n")
                append(detail)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.linked_folder_access_title)
            .setMessage(msg)
            .setPositiveButton(R.string.linked_folder_reauth_go) { _, _ ->
                launchLinkedFolderReauth(folder)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchLinkedFolderReauth(folder: ShelfFolder) {
        treePickMode = TreePickMode.REAUTH_FOLDER
        pendingReauthFolderId = folder.id
        openTreeLauncher.launch(null)
    }

    /** 用户重新选择目录后，写回原绑定项并重建索引 */
    private fun completeLinkedFolderReauth(treeUri: Uri) {
        val folderId = pendingReauthFolderId
        pendingReauthFolderId = null
        treePickMode = TreePickMode.BIND_FOLDER
        if (folderId.isNullOrBlank()) {
            // 无目标则当作新建绑定
            bindLinkedFolder(treeUri)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    FolderImporter.takePersistable(this@MainActivity, treeUri)
                    BookshelfStore.updateLinkedTreeUri(
                        this@MainActivity,
                        folderId = folderId,
                        treeUri = treeUri.toString(),
                        displayName = null, // 保留用户自定义名
                    ) ?: error("绑定项不存在")
                }
            }
            result.onSuccess { folder ->
                linkedAccessPromptedIds.remove(folder.id)
                Toasts.show(
                    this@MainActivity,
                    getString(R.string.linked_folder_reauth_done, folder.name),
                )
                // 进入该绑定根目录并扫描
                currentFolderId = folder.id
                linkedPathStack.clear()
                refreshShelf()
                scanLinkedTree(folder, showStartToast = true)
            }.onFailure { e ->
                Toasts.show(
                    this@MainActivity,
                    getString(R.string.load_failed, e.message ?: ""),
                    android.widget.Toast.LENGTH_LONG,
                )
            }
        }
    }

    /** 当前是否在虚拟书架内（可导入书到此架） */
    private fun currentVirtualShelfId(): String? {
        val id = currentFolderId ?: return null
        val f = BookshelfStore.findFolder(this, id) ?: return null
        return if (f.kind == ShelfFolderKind.SHELF) id else null
    }

    private fun isInsideLinked(): Boolean {
        val id = currentFolderId ?: return false
        return BookshelfStore.findFolder(this, id)?.isLinked == true
    }

    private fun isInsideHistory(): Boolean =
        ReadingHistoryStore.isHistoryFolderId(currentFolderId)

    /** 绑定外部目录：只浏览，不支持多选 */
    private fun isBrowseOnlyFolder(): Boolean = isInsideLinked()

    /** 虚拟书架 / 阅读历史：可多选；绑定目录不可 */
    private fun supportsMultiSelect(): Boolean =
        !isInsideLinked() && !searchMode

    /** 仅虚拟书架支持拖排序；历史按时间排，不可拖 */
    private fun supportsSelectionDrag(): Boolean =
        selectionMode && supportsMultiSelect() && !isInsideHistory()

    private fun handleIncomingIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data!!
            val name = intent.getStringExtra(Intent.EXTRA_TITLE)
                ?: queryDisplayName(uri)
                ?: getString(R.string.unnamed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !StorageAccess.hasAllFilesAccess() &&
                !StorageAccess.canRead(this, uri)
            ) {
                pendingViewUri = uri
                pendingViewName = name
                promptAllFilesAccess()
            }
            ingestAndOpenExternal(uri, name)
            return true
        }
        return false
    }

    private fun ingestAndOpenExternal(uri: Uri, name: String?) {
        val display = name ?: getString(R.string.unnamed)
        lifecycleScope.launch {
            val stableUri = withContext(Dispatchers.IO) {
                StorageAccess.ensurePersistentReadable(
                    this@MainActivity,
                    uri,
                    display,
                )
            }
            if (stableUri != uri.toString() && stableUri.startsWith("file:")) {
                Toasts.show(this@MainActivity, R.string.book_copied_local)
            }
            BookshelfStore.addOrUpdateBook(
                this@MainActivity,
                uri = stableUri,
                displayName = BookFileType.stripBookExt(display),
                folderId = null,
                pathHint = display,
            )
            refreshShelf()
            openBookUri(Uri.parse(stableUri), display)
        }
    }

    private fun openFolder(folder: ShelfFolder) {
        if (selectionMode) exitSelectionMode()
        if (folder.isLinked && !FolderImporter.hasTreeAccess(this, folder.treeUri)) {
            // 权限已失效：仍进入目录（可看缓存），并引导重新授权
            currentFolderId = folder.id
            linkedPathStack.clear()
            refreshShelf()
            promptLinkedFolderReauth(folder, force = true)
            return
        }
        currentFolderId = folder.id
        linkedPathStack.clear()
        refreshShelf()
    }

    private fun enterLinkedDir(entry: LinkedDirEntry) {
        if (selectionMode) exitSelectionMode()
        linkedPathStack.add(entry)
        refreshShelf()
    }

    private fun openLinkedFile(entry: LinkedFileEntry) {
        // 绑定文件夹内打开：不写入主书架，仅直接阅读（进度见 ReadingProgressStore）
        rememberFocusAndOpen(entry.uri) {
            openBookUri(
                Uri.parse(entry.uri),
                entry.displayName,
                entry.name,
                BookEncodingStore.get(this, entry.uri),
            )
        }
    }

    private fun navigateUp() {
        if (selectionMode) {
            exitSelectionMode()
            return
        }
        if (linkedPathStack.isNotEmpty()) {
            linkedPathStack.removeAt(linkedPathStack.lastIndex)
            refreshShelf()
            return
        }
        currentFolderId = null
        refreshShelf()
    }

    /** 回到书架主页（顶层） */
    private fun goShelfHome() {
        if (selectionMode) exitSelectionMode()
        if (currentFolderId == null && linkedPathStack.isEmpty()) return
        currentFolderId = null
        linkedPathStack.clear()
        refreshShelf()
    }

    private fun openBook(book: ShelfBook) {
        rememberFocusAndOpen(book.uri) {
            openBookInternal(book)
        }
    }

    private fun openBookInternal(book: ShelfBook) {
        val encoding = BookEncodingStore.get(this, book.uri)
        if (book.uri.startsWith("asset://")) {
            val path = book.uri.removePrefix("asset://")
            startActivity(
                Intent(this, ReadingActivity::class.java)
                    .putExtra(ReadingActivity.EXTRA_ASSET, path)
                    .putExtra(ReadingActivity.EXTRA_TITLE, book.displayName)
                    .putExtra(ReadingActivity.EXTRA_ENCODING, encoding),
            )
            return
        }
        val uri = Uri.parse(book.uri)
        if (!StorageAccess.canRead(this, uri)) {
            // 缺全盘权限时优先引导授权；否则尝试复制/解析后再失败则完整引导
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !StorageAccess.hasAllFilesAccess()
            ) {
                showOpenFailGuideForBook(book, OpenFailGuide.Reason.PERMISSION)
                return
            }
            lifecycleScope.launch {
                val stable = withContext(Dispatchers.IO) {
                    StorageAccess.ensurePersistentReadable(
                        this@MainActivity,
                        uri,
                        book.displayName.ifBlank { book.pathHint },
                    )
                }
                if (!StorageAccess.canRead(this@MainActivity, Uri.parse(stable))) {
                    val reason = if (OpenFailGuide.canOfferGrantPermission(this@MainActivity)) {
                        OpenFailGuide.Reason.PERMISSION
                    } else {
                        OpenFailGuide.Reason.UNAVAILABLE
                    }
                    showOpenFailGuideForBook(book, reason)
                    return@launch
                }
                if (stable != book.uri) {
                    encoding?.let { BookEncodingStore.set(this@MainActivity, stable, it) }
                    OpenFailGuide.migrateBindings(
                        this@MainActivity,
                        book.uri,
                        stable,
                        book.id,
                    )
                    refreshShelf()
                }
                openBookUri(Uri.parse(stable), book.displayName, book.pathHint, encoding)
            }
            return
        }
        openBookUri(uri, book.displayName, book.pathHint, encoding)
    }

    private fun showOpenFailGuideForBook(book: ShelfBook, reason: OpenFailGuide.Reason) {
        OpenFailGuide.show(
            activity = this,
            reason = reason,
            bookTitle = book.displayName.ifBlank { book.pathHint },
            onGrantPermission = {
                pendingRetryBook = book
                launchGrantPermission()
            },
            onReselect = {
                launchRepairFilePicker(
                    bookId = book.id,
                    oldUri = book.uri,
                    title = book.displayName.ifBlank { book.pathHint },
                )
            },
            onClose = null,
        )
    }

    private fun launchGrantPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesAccessLauncher.launch(StorageAccess.manageAllFilesIntent(this))
        } else {
            requestStorageIfNeeded()
            // 旧版：请求后立刻重试（用户允许后权限即时生效）
            val retry = pendingRetryBook
            if (retry != null) {
                binding.root.postDelayed({
                    if (pendingRetryBook?.id == retry.id) {
                        pendingRetryBook = null
                        openBookInternal(retry)
                    }
                }, 400)
            }
        }
    }

    private fun launchRepairFilePicker(bookId: String?, oldUri: String?, title: String?) {
        docPickMode = DocPickMode.REPAIR
        pendingRepairBookId = bookId
        pendingRepairOldUri = oldUri
        pendingRepairTitle = title
        openDocLauncher.launch(
            arrayOf(
                "text/plain",
                "text/*",
                "application/pdf",
                "application/epub+zip",
                "application/x-mobipocket-ebook",
                "application/octet-stream",
            ),
        )
    }

    private fun clearDocRepairState() {
        docPickMode = DocPickMode.IMPORT
        pendingRepairBookId = null
        pendingRepairOldUri = null
        pendingRepairTitle = null
    }

    private fun handleRepairedFile(uri: Uri) {
        val bookId = pendingRepairBookId
        val oldUri = pendingRepairOldUri
        val titleHint = pendingRepairTitle
        clearDocRepairState()
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                queryDisplayName(uri) ?: uri.lastPathSegment ?: titleHint
                    ?: getString(R.string.unnamed)
            }
            val stable = withContext(Dispatchers.IO) {
                OpenFailGuide.bindReselectedFile(
                    this@MainActivity,
                    oldUri = oldUri,
                    newUri = uri,
                    displayName = name,
                    bookId = bookId,
                )
            }
            Toasts.show(this@MainActivity, R.string.open_failed_reselect_done)
            refreshShelf()
            val encoding = BookEncodingStore.get(this@MainActivity, stable)
                ?: oldUri?.let { BookEncodingStore.get(this@MainActivity, it) }
            rememberFocusAndOpen(stable) {
                openBookUri(
                    Uri.parse(stable),
                    BookFileType.stripBookExt(name),
                    name,
                    encoding,
                )
            }
        }
    }

    private fun openBookUri(
        uri: Uri,
        title: String?,
        pathHint: String? = null,
        encoding: String? = null,
    ) {
        val isPdf = BookFileType.isPdfUri(this, uri, title)
            || BookFileType.isPdf(pathHint)
        if (isPdf) {
            startActivity(
                Intent(this, PdfReadingActivity::class.java)
                    .putExtra(PdfReadingActivity.EXTRA_URI, uri.toString())
                    .putExtra(PdfReadingActivity.EXTRA_TITLE, title),
            )
        } else {
            val enc = encoding ?: BookEncodingStore.get(this, uri.toString())
            startActivity(
                Intent(this, ReadingActivity::class.java)
                    .putExtra(ReadingActivity.EXTRA_URI, uri.toString())
                    .putExtra(ReadingActivity.EXTRA_TITLE, title)
                    .putExtra(ReadingActivity.EXTRA_ENCODING, enc),
            )
        }
    }

    private fun tryAutoResumeLastBook() {
        if (didAutoResume) return
        didAutoResume = true

        val txtUri = AppSettings.lastBookUri(this)
        val txtTitle = AppSettings.lastBookTitle(this)
        val txtAt = AppSettings.lastBookAt(this)
        val pdfUri = AppSettings.lastPdfUri(this)
        val pdfTitle = AppSettings.lastPdfTitle(this)
        val pdfAt = AppSettings.lastPdfAt(this)
        val shelf = BookshelfStore.mostRecentBook(this)

        data class Cand(val uri: String, val title: String?, val at: Long, val fromShelf: ShelfBook?)

        val cands = ArrayList<Cand>()
        if (!txtUri.isNullOrBlank()) {
            val b = BookshelfStore.findBookByUri(this, txtUri)
            cands.add(Cand(txtUri, txtTitle.ifBlank { b?.displayName }, maxOf(txtAt, b?.lastOpened ?: 0L), b))
        }
        if (!pdfUri.isNullOrBlank()) {
            val b = BookshelfStore.findBookByUri(this, pdfUri)
            cands.add(Cand(pdfUri, pdfTitle.ifBlank { b?.displayName }, maxOf(pdfAt, b?.lastOpened ?: 0L), b))
        }
        if (shelf != null) {
            cands.add(Cand(shelf.uri, shelf.displayName, shelf.lastOpened, shelf))
        }
        val best = cands.maxByOrNull { it.at }
        binding.root.post {
            when {
                best?.fromShelf != null -> {
                    // 自动打开也要登记返回定位，且不覆盖绑定文件夹路径
                    rememberFocusAndOpen(best.fromShelf.uri) {
                        openBookInternal(best.fromShelf)
                    }
                }
                best != null && best.uri.isNotBlank() && !best.uri.startsWith("asset://") -> {
                    rememberFocusAndOpen(best.uri) {
                        openBookUri(Uri.parse(best.uri), best.title, best.title)
                    }
                }
            }
        }
    }

    /** 下拉刷新：重新加载当前列表；绑定目录则校验/更新缓存 */
    private fun pullToRefresh() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val folderId = currentFolderId
                    if (folderId == null) {
                        // 根：轻量刷新各绑定目录根层计数
                        BookshelfStore.folders(this@MainActivity)
                            .filter { it.isLinked }
                            .forEach { f ->
                                val tree = f.treeUri ?: return@forEach
                                runCatching {
                                    LinkedTreeCacheStore.refreshLevel(
                                        this@MainActivity,
                                        tree,
                                        parentDocumentId = null,
                                    )
                                }
                            }
                    } else {
                        val folder = BookshelfStore.findFolder(this@MainActivity, folderId)
                        if (folder?.isLinked == true) {
                            val tree = folder.treeUri
                            if (!tree.isNullOrBlank()) {
                                val docId = linkedPathStack.lastOrNull()?.documentId
                                runCatching {
                                    LinkedTreeCacheStore.refreshLevel(
                                        this@MainActivity,
                                        tree,
                                        parentDocumentId = docId,
                                    )
                                }
                            }
                        }
                    }
                }
                refreshShelf()
                if (!isFinishing && !isDestroyed) {
                    Toasts.show(this@MainActivity, R.string.shelf_refreshed)
                }
            } finally {
                if (::binding.isInitialized) {
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun refreshShelf(scrollToUri: String? = null) {
        val sort = AppSettings.shelfSort(this)
        val folderId = currentFolderId
        if (folderId == null) {
            binding.tvTitle.text = getString(R.string.bookshelf)
            setFolderPathSubtitle(null)
            binding.btnNavBack.isVisible = false
            binding.btnHome.isVisible = false
            binding.btnAddMenu.isVisible = true
            // 绑定文件夹子项统计可能较慢，放 IO 线程
            lifecycleScope.launch {
                val items = withContext(Dispatchers.IO) {
                    BookshelfStore.listRoot(this@MainActivity, sort)
                }
                if (isFinishing || isDestroyed) return@launch
                // 若用户已点进某文件夹，勿覆盖
                if (currentFolderId != null) return@launch
                val shown = applyShelfSearch(items)
                adapter.submit(shown)
                binding.tvEmpty.isVisible = shown.isEmpty()
                binding.tvEmpty.setText(
                    if (searchMode && shelfQuery.isNotBlank()) {
                        R.string.shelf_search_empty
                    } else {
                        R.string.empty_shelf
                    },
                )
                updateSortButton()
                updateSelectionUi()
                scrollShelfToUri(scrollToUri)
                binding.swipeRefresh.isRefreshing = false
            }
            return
        }

        val folder = BookshelfStore.findFolder(this, folderId)
        if (folder == null) {
            currentFolderId = null
            linkedPathStack.clear()
            refreshShelf(scrollToUri)
            return
        }

        binding.btnNavBack.isVisible = true
        binding.btnHome.isVisible = true
        binding.btnAddMenu.isVisible = true

        if (folder.isLinked) {
            // 标题：当前目录名；副标题：完整路径
            binding.tvTitle.text = if (linkedPathStack.isEmpty()) {
                folder.name
            } else {
                linkedPathStack.last().name
            }
            val fullPath = buildList {
                add(folder.name)
                linkedPathStack.forEach { add(it.name) }
            }.joinToString(" / ")
            setFolderPathSubtitle(fullPath)
            loadLinkedListing(folder, sort, scrollToUri)
        } else {
            linkedPathStack.clear()
            binding.tvTitle.text = folder.name
            setFolderPathSubtitle(folder.name)
            val items = BookshelfStore.listInFolder(this, folderId, sort)
            val shown = applyShelfSearch(items)
            adapter.submit(shown)
            binding.tvEmpty.isVisible = shown.isEmpty()
            binding.tvEmpty.setText(
                when {
                    searchMode && shelfQuery.isNotBlank() -> R.string.shelf_search_empty
                    folder.isHistory -> R.string.empty_history
                    else -> R.string.empty_folder
                },
            )
            updateSortButton()
            updateSelectionUi()
            scrollShelfToUri(scrollToUri)
            binding.swipeRefresh.isRefreshing = false
        }
    }

    /** 标题栏下方灰色完整路径；主页隐藏 */
    private fun setFolderPathSubtitle(path: String?) {
        if (!::binding.isInitialized) return
        if (path.isNullOrBlank()) {
            binding.tvFolderPath.isVisible = false
            binding.tvFolderPath.text = ""
        } else {
            binding.tvFolderPath.isVisible = true
            binding.tvFolderPath.text = path
        }
    }

    private fun scrollShelfToUri(uri: String?) {
        if (uri.isNullOrBlank()) return
        fun tryScroll(attempt: Int) {
            if (isFinishing || isDestroyed) return
            val idx = adapter.indexOfUri(uri)
            if (idx >= 0) {
                binding.rvShelf.scrollToPosition(idx)
                // 再滚一次保证异步布局后可见
                binding.rvShelf.post {
                    binding.rvShelf.smoothScrollToPosition(idx)
                }
            } else if (attempt < 8) {
                binding.rvShelf.postDelayed({ tryScroll(attempt + 1) }, 120L)
            }
        }
        binding.rvShelf.post { tryScroll(0) }
    }

    private fun loadLinkedListing(
        folder: ShelfFolder,
        sort: ShelfSort,
        scrollToUri: String? = null,
    ) {
        val treeUri = folder.treeUri
        if (treeUri.isNullOrBlank()) {
            adapter.submit(emptyList())
            binding.tvEmpty.isVisible = true
            binding.tvEmpty.setText(R.string.linked_folder_access_lost)
            promptLinkedFolderReauth(folder, force = true)
            return
        }
        // 搜索模式：展示整树缓存命中，不限当前目录
        if (searchMode && shelfQuery.isNotBlank()) {
            lifecycleScope.launch {
                val shown = withContext(Dispatchers.IO) {
                    applyShelfSearch(emptyList())
                }
                if (isFinishing || isDestroyed) return@launch
                if (currentFolderId != folder.id) return@launch
                adapter.submit(shown)
                binding.tvEmpty.isVisible = shown.isEmpty()
                binding.tvEmpty.setText(R.string.shelf_search_empty)
                updateSortButton()
                updateSelectionUi()
                scrollShelfToUri(scrollToUri)
            }
            return
        }
        val docId = linkedPathStack.lastOrNull()?.documentId
        // 1) 内存缓存：主线程立刻切换列表（无 SAF、无读盘）
        // listingOrNull：无该层键返回 null，避免「空列表」被当成命中而白等 SAF
        val memListing = LinkedTreeCacheStore.peek(treeUri)?.listingOrNull(docId)
        var shownFromCache = false
        if (memListing != null) {
            submitLinkedListing(memListing, sort, scrollToUri, fromCache = true)
            shownFromCache = true
        }

        lifecycleScope.launch {
            // 2) 内存未命中时再读盘（仅首次进入该绑定树）
            if (!shownFromCache) {
                val diskListing = withContext(Dispatchers.IO) {
                    LinkedTreeCacheStore.load(this@MainActivity, treeUri)?.listingOrNull(docId)
                }
                if (isFinishing || isDestroyed) return@launch
                if (currentFolderId != folder.id) return@launch
                val stillSameAfterDisk = linkedPathStack.lastOrNull()?.documentId == docId
                if (!stillSameAfterDisk && docId != null) return@launch
                if (diskListing != null) {
                    submitLinkedListing(diskListing, sort, scrollToUri, fromCache = true)
                    shownFromCache = true
                } else if (docId == null) {
                    scanLinkedTree(folder, showStartToast = false)
                }
            }

            // 3) 后台 SAF 校验；仅内容变化才刷 UI / 写盘
            val live = withContext(Dispatchers.IO) {
                runCatching {
                    LinkedTreeCacheStore.refreshLevel(
                        this@MainActivity,
                        treeUri,
                        parentDocumentId = docId,
                    )
                }
            }
            if (isFinishing || isDestroyed) return@launch
            if (currentFolderId != folder.id) return@launch
            val stillSame = linkedPathStack.lastOrNull()?.documentId == docId
            if (!stillSame && docId != null) return@launch
            if (linkedPathStack.isNotEmpty() && docId == null) return@launch

            live.onSuccess { result ->
                if (result.contentChanged || !shownFromCache) {
                    submitLinkedListing(result.listing, sort, scrollToUri, fromCache = false)
                }
                linkedAccessPromptedIds.remove(folder.id)
                binding.swipeRefresh.isRefreshing = false
            }.onFailure { e ->
                binding.swipeRefresh.isRefreshing = false
                val accessFail = FolderImporter.isAccessFailure(e)
                if (shownFromCache) {
                    // 有缓存可继续看，但权限失效时仍提示重授权（同会话最多一次）
                    if (accessFail) {
                        promptLinkedFolderReauth(folder, force = false, detail = e.message)
                    }
                    return@onFailure
                }
                if (accessFail) {
                    promptLinkedFolderReauth(folder, force = true, detail = e.message)
                    adapter.submit(emptyList())
                    binding.tvEmpty.isVisible = true
                    binding.tvEmpty.setText(R.string.linked_folder_access_lost)
                    return@onFailure
                }
                if (linkedPathStack.isNotEmpty()) {
                    linkedPathStack.removeAt(linkedPathStack.lastIndex)
                }
                Toasts.show(
                    this@MainActivity,
                    getString(
                        R.string.linked_folder_open_failed_detail,
                        e.message ?: e.javaClass.simpleName,
                    ),
                    android.widget.Toast.LENGTH_LONG,
                )
                if (currentFolderId != null) {
                    refreshShelf(scrollToUri)
                } else {
                    adapter.submit(emptyList())
                    binding.tvEmpty.isVisible = true
                    binding.tvEmpty.setText(R.string.empty_linked_folder)
                }
            }
        }
    }

    private fun submitLinkedListing(
        data: com.whj.reader.data.LinkedListing,
        sort: ShelfSort,
        scrollToUri: String?,
        fromCache: Boolean,
    ) {
        val progressByUri = BookshelfStore.books(this).associateBy { it.uri }
        val dirItems = data.dirs.map { ShelfItem.LinkedDir(it) }
        var fileItems = data.files.map {
            ShelfItem.LinkedFile(it, progressByUri[it.uri])
        }
        if (sort != ShelfSort.NAME) {
            fileItems = fileItems.sortedByDescending { item ->
                val store = com.whj.reader.data.ReadingProgressStore
                    .get(this, item.entry.uri)
                maxOf(store?.lastOpened ?: 0L, item.progress?.lastOpened ?: 0L)
            }
        }
        val items = dirItems + fileItems
        val shown = if (searchMode && shelfQuery.isNotBlank()) {
            applyShelfSearch(items)
        } else {
            items
        }
        // 与当前列表签名相同则跳过 notify（避免二次闪烁）
        if (listingSignature(shown) == listingSignature(adapter.currentItems())) {
            if (scrollToUri != null) scrollShelfToUri(scrollToUri)
            return
        }
        adapter.submit(shown)
        binding.tvEmpty.isVisible = shown.isEmpty()
        binding.tvEmpty.setText(
            if (searchMode && shelfQuery.isNotBlank()) {
                R.string.shelf_search_empty
            } else {
                R.string.empty_linked_folder
            },
        )
        updateSortButton()
        updateSelectionUi()
        if (!fromCache || scrollToUri != null) {
            scrollShelfToUri(scrollToUri)
        }
    }

    private fun listingSignature(items: List<ShelfItem>): String {
        return items.joinToString("|") { item ->
            when (item) {
                is ShelfItem.LinkedDir -> "d:${item.entry.documentId}:${item.entry.childCount}"
                is ShelfItem.LinkedFile -> "f:${item.entry.uri}"
                is ShelfItem.Folder -> "F:${item.folder.id}:${item.childCount}"
                is ShelfItem.Book -> "b:${item.book.id}"
            }
        }
    }

    private fun sortLabel(sort: ShelfSort): String =
        when (sort) {
            ShelfSort.LAST_OPENED -> getString(R.string.shelf_sort_by_time)
            ShelfSort.NAME -> getString(R.string.shelf_sort_by_name)
            ShelfSort.CUSTOM -> getString(R.string.shelf_sort_by_custom)
        }

    private fun updateSortButton() {
        if (!::binding.isInitialized) return
        val sort = AppSettings.shelfSort(this)
        binding.btnSort.text = sortLabel(sort)
    }

    private fun toggleShelfSort() {
        val current = AppSettings.shelfSort(this)
        val next = when (current) {
            ShelfSort.LAST_OPENED -> ShelfSort.NAME
            ShelfSort.NAME -> ShelfSort.CUSTOM
            ShelfSort.CUSTOM -> ShelfSort.LAST_OPENED
        }
        AppSettings.setShelfSort(this, next)
        updateSortButton()
        Toasts.show(this, getString(R.string.shelf_sort_current, sortLabel(next)))
        if (selectionMode) updateSelectionUi()
        refreshShelf()
    }

    private fun promptNewShelf() {
        val input = EditText(this).apply {
            hint = getString(R.string.create_shelf_hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.create_shelf_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                BookshelfStore.createFolder(this, name)
                Toasts.show(this, R.string.shelf_created)
                refreshShelf()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFolderMenu(folder: ShelfFolder) {
        if (folder.isHistory) {
            // 系统虚拟夹：不可改名/删除
            AlertDialog.Builder(this)
                .setTitle(folder.name)
                .setMessage(R.string.history_folder_hint)
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }
        if (folder.isLinked) {
            val options = arrayOf(
                getString(R.string.linked_folder_reauth),
                getString(R.string.rescan_linked_files),
                getString(R.string.rename_linked_folder),
                getString(R.string.delete_linked_folder),
            )
            AlertDialog.Builder(this)
                .setTitle(folder.name)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> launchLinkedFolderReauth(folder)
                        1 -> scanLinkedTree(folder, showStartToast = true)
                        2 -> promptRenameFolder(folder)
                        3 -> confirmDeleteLinked(folder)
                    }
                }
                .show()
        } else {
            val options = arrayOf(
                getString(R.string.rename_shelf),
                getString(R.string.delete_shelf),
            )
            AlertDialog.Builder(this)
                .setTitle(folder.name)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> promptRenameFolder(folder)
                        1 -> confirmDeleteFolder(folder)
                    }
                }
                .show()
        }
    }

    private fun promptRenameFolder(folder: ShelfFolder) {
        val input = EditText(this).apply {
            setText(folder.name)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (folder.isLinked) R.string.rename_linked_folder else R.string.rename_shelf,
            )
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    BookshelfStore.renameFolder(this, folder.id, name)
                    refreshShelf()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFolder(folder: ShelfFolder) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_shelf)
            .setMessage(R.string.delete_shelf_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                // 删除书架时同时删除其内书籍记录
                BookshelfStore.deleteFolder(this, folder.id, moveBooksToRoot = false)
                if (currentFolderId == folder.id) {
                    currentFolderId = null
                    linkedPathStack.clear()
                }
                refreshShelf()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onBookItemClick(book: ShelfBook) {
        if (selectionMode) {
            toggleBookSelection(book)
        } else {
            openBook(book)
        }
    }

    /** 工具栏多选按钮：进入多选（不预选；点选书籍勾选） */
    private fun startMultiSelectMode() {
        if (isInsideLinked()) {
            Toasts.show(this, R.string.multiselect_shelf_only)
            return
        }
        if (selectionMode) {
            exitSelectionMode()
            return
        }
        if (searchMode) closeSearchMode()
        selectionMode = true
        selectedBookIds.clear()
        updateSelectionUi()
    }

    private fun exitSelectionMode() {
        if (dragOrderDirty) {
            persistCustomOrderFromList()
            dragOrderDirty = false
        }
        selectionMode = false
        selectedBookIds.clear()
        updateSelectionUi()
    }

    private fun toggleBookSelection(book: ShelfBook) {
        if (selectedBookIds.contains(book.id)) {
            selectedBookIds.remove(book.id)
        } else {
            selectedBookIds.add(book.id)
        }
        // 多选模式由工具栏进入：清空勾选不退出，直到点取消
        updateSelectionUi()
    }

    /** 书架书长按菜单；历史内可多选 / 删除记录 */
    private fun showBookPopupMenu(book: ShelfBook, anchor: android.view.View) {
        val historyItem = isInsideHistory() ||
            ReadingHistoryStore.isHistoryBookId(book.id) ||
            ReadingHistoryStore.isHistoryFolderId(book.folderId)
        val popup = PopupMenu(this, anchor)
        if (historyItem) {
            popup.menu.add(0, 10, 0, R.string.selection_mode)
            popup.menu.add(0, 2, 1, R.string.history_delete_record)
        } else {
            popup.menu.add(0, 1, 0, R.string.move_book)
            popup.menu.add(0, 2, 1, R.string.delete_book)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                10 -> {
                    // 进入多选并勾选当前项
                    if (!selectionMode) {
                        selectionMode = true
                        selectedBookIds.clear()
                    }
                    selectedBookIds.add(book.id)
                    updateSelectionUi()
                    true
                }
                1 -> {
                    showMoveBookDialog(book)
                    true
                }
                2 -> {
                    confirmDeleteBook(book)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showMoveBookDialog(book: ShelfBook) {
        val folders = BookshelfStore.shelfFolders(this)
        val labels = mutableListOf(getString(R.string.move_to_root))
        labels.addAll(folders.map { it.name })
        AlertDialog.Builder(this)
            .setTitle(R.string.move_book)
            .setItems(labels.toTypedArray()) { _, which ->
                val targetId = if (which == 0) null else folders[which - 1].id
                BookshelfStore.moveBook(this, book.id, targetId)
                Toasts.show(this, R.string.moved)
                refreshShelf()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteBook(book: ShelfBook) {
        val isHistory = ReadingHistoryStore.isHistoryBookId(book.id) ||
            ReadingHistoryStore.isHistoryFolderId(book.folderId) ||
            isInsideHistory()
        AlertDialog.Builder(this)
            .setTitle(
                if (isHistory) R.string.history_delete_title else R.string.delete_book,
            )
            .setMessage(
                if (isHistory) R.string.history_delete_msg else R.string.delete_book_msg,
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                if (isHistory) {
                    // 删除阅读记录 + PDF OCR/目录等缓存
                    ReadingHistoryStore.removeRecord(this, book.uri)
                } else {
                    BookshelfStore.removeBook(this, book.id)
                }
                refreshShelf()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun selectAllBooks() {
        if (!supportsMultiSelect() && !selectionMode) {
            Toasts.show(this, R.string.multiselect_shelf_only)
            return
        }
        selectedBookIds.clear()
        adapter.currentItems().forEach { item ->
            if (item is ShelfItem.Book) selectedBookIds.add(item.book.id)
        }
        if (selectedBookIds.isEmpty()) {
            Toasts.show(this, R.string.selection_empty)
            return
        }
        selectionMode = true
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        if (!::binding.isInitialized || !::adapter.isInitialized) return
        val multi = selectionMode && supportsMultiSelect()
        binding.selectionBar.isVisible = multi
        binding.actionScroll.isVisible = !multi
        binding.btnMultiSelect.isVisible = supportsMultiSelect()
        binding.tvSelectionCount.text = getString(R.string.selection_count, selectedBookIds.size)
        // 历史：只删不移、不可拖排序
        val history = isInsideHistory()
        binding.btnSelectionMove.isVisible = multi && !history
        binding.btnSelectionRemove.text = getString(
            if (history) R.string.history_delete_record else R.string.delete_book,
        )
        adapter.setSelectionState(
            enabled = multi,
            selected = selectedBookIds.toSet(),
            dragEnabled = supportsSelectionDrag(),
        )
    }

    private fun selectedBooks(): List<ShelfBook> {
        return adapter.currentItems().mapNotNull { item ->
            (item as? ShelfItem.Book)?.book?.takeIf { it.id in selectedBookIds }
        }
    }

    private fun batchMoveSelected() {
        if (isInsideHistory()) {
            Toasts.show(this, R.string.history_no_move)
            return
        }
        val books = selectedBooks()
        if (books.isEmpty()) {
            Toasts.show(this, R.string.selection_empty)
            return
        }
        val folders = BookshelfStore.shelfFolders(this)
        val labels = mutableListOf(getString(R.string.move_to_root))
        labels.addAll(folders.map { it.name })
        AlertDialog.Builder(this)
            .setTitle(R.string.move_book)
            .setItems(labels.toTypedArray()) { _, which ->
                val targetId = if (which == 0) null else folders[which - 1].id
                BookshelfStore.moveBooks(this, books.map { it.id }.toSet(), targetId)
                Toasts.show(this, getString(R.string.batch_moved, books.size))
                exitSelectionMode()
                refreshShelf()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun batchRemoveSelected() {
        val books = selectedBooks()
        if (books.isEmpty()) {
            Toasts.show(this, R.string.selection_empty)
            return
        }
        val historyMode = isInsideHistory() || books.all {
            ReadingHistoryStore.isHistoryBookId(it.id) ||
                ReadingHistoryStore.isHistoryFolderId(it.folderId)
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (historyMode) R.string.history_batch_delete_title
                else R.string.batch_delete_books_title,
            )
            .setMessage(
                if (historyMode) {
                    getString(R.string.history_batch_delete_msg, books.size)
                } else {
                    getString(R.string.batch_delete_books_msg, books.size)
                },
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                if (historyMode || isInsideHistory()) {
                    // 历史页：全部按 URI 清记录
                    ReadingHistoryStore.removeRecords(this, books.map { it.uri })
                    Toasts.show(this, getString(R.string.history_batch_removed, books.size))
                } else {
                    val historyUris = books
                        .filter {
                            ReadingHistoryStore.isHistoryBookId(it.id) ||
                                ReadingHistoryStore.isHistoryFolderId(it.folderId)
                        }
                        .map { it.uri }
                    val shelfIds = books
                        .filterNot {
                            ReadingHistoryStore.isHistoryBookId(it.id) ||
                                ReadingHistoryStore.isHistoryFolderId(it.folderId)
                        }
                        .map { it.id }
                        .toSet()
                    if (historyUris.isNotEmpty()) {
                        ReadingHistoryStore.removeRecords(this, historyUris)
                    }
                    if (shelfIds.isNotEmpty()) {
                        BookshelfStore.removeBooks(this, shelfIds)
                    }
                    Toasts.show(this, getString(R.string.batch_removed, books.size))
                }
                exitSelectionMode()
                refreshShelf()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 右上角 ⋮：搜索、文本转语音、OCR 测试、设置 */
    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.shelf_search)
        popup.menu.add(0, 2, 1, R.string.tts_synth_title)
        popup.menu.add(0, 3, 2, R.string.ocr_title)
        popup.menu.add(0, 4, 3, R.string.settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (selectionMode) exitSelectionMode()
                    openSearchMode()
                    true
                }
                2 -> {
                    startActivity(Intent(this, TtsSynthActivity::class.java))
                    true
                }
                3 -> {
                    startActivity(Intent(this, OcrActivity::class.java))
                    true
                }
                4 -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleSearchMode() {
        if (searchMode) {
            closeSearchMode()
        } else {
            openSearchMode()
        }
    }

    private fun openSearchMode() {
        if (selectionMode) exitSelectionMode()
        searchMode = true
        binding.searchBar.isVisible = true
        binding.etSearch.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        refreshShelf()
    }

    private fun closeSearchMode() {
        searchMode = false
        shelfQuery = ""
        binding.etSearch.setText("")
        binding.searchBar.isVisible = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        refreshShelf()
    }

    private fun matchBookName(name: String, pathHint: String, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return name.contains(q, ignoreCase = true) ||
            pathHint.contains(q, ignoreCase = true)
    }

    /**
     * 搜索：全库书名 + 所有绑定文件夹缓存中的文件（含子目录）。
     * 非搜索：原样返回。
     */
    private fun applyShelfSearch(items: List<ShelfItem>): List<ShelfItem> {
        if (!searchMode || shelfQuery.isBlank()) return items
        val q = shelfQuery.trim()
        val sort = AppSettings.shelfSort(this)
        val progressByUri = BookshelfStore.books(this).associateBy { it.uri }
        val shelfHits = BookshelfStore.books(this)
            .filter { matchBookName(it.displayName, it.pathHint, q) }
            .let { list ->
                when (sort) {
                    ShelfSort.LAST_OPENED -> list.sortedByDescending { it.lastOpened }
                    ShelfSort.NAME -> list.sortedBy { it.displayName.lowercase() }
                    ShelfSort.CUSTOM -> list.sortedWith(
                        compareBy<ShelfBook> { it.sortOrder }.thenByDescending { it.lastOpened },
                    )
                }
            }
            .map { ShelfItem.Book(it) }
        val seen = shelfHits.map { it.book.uri }.toMutableSet()

        // 绑定文件夹整树缓存（含子目录）
        val cachedLinked = LinkedTreeCacheStore.allCachedFiles(this)
            .mapNotNull { (_, rec) ->
                if (!matchBookName(rec.displayName, rec.relativePath, q) &&
                    !matchBookName(rec.name, rec.relativePath, q)
                ) {
                    return@mapNotNull null
                }
                if (rec.uri in seen) return@mapNotNull null
                seen.add(rec.uri)
                ShelfItem.LinkedFile(
                    LinkedFileEntry(
                        name = rec.name,
                        displayName = rec.displayName,
                        uri = rec.uri,
                        isPdf = rec.isPdf,
                        sizeBytes = rec.sizeBytes,
                        relativePath = rec.relativePath,
                        documentId = rec.documentId,
                    ),
                    progressByUri[rec.uri],
                )
            }

        // 当前层实时列表中尚未进缓存的文件（兜底）
        val liveExtra = items.mapNotNull { item ->
            when (item) {
                is ShelfItem.LinkedFile -> {
                    val e = item.entry
                    if (e.uri in seen) return@mapNotNull null
                    if (matchBookName(e.displayName, e.relativePath.ifBlank { e.name }, q) ||
                        matchBookName(e.name, "", q)
                    ) {
                        seen.add(e.uri)
                        item
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
        return shelfHits + cachedLinked + liveExtra
    }

    /** 多选下拖动手柄排序；松手后写入自定义顺序 */
    private fun setupBookReorderDrag() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                if (!supportsSelectionDrag()) return 0
                val item = adapter.getItem(viewHolder.bindingAdapterPosition) ?: return 0
                if (item !is ShelfItem.Book) return 0
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                if (!supportsSelectionDrag()) return false
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val fromItem = adapter.getItem(from)
                val toItem = adapter.getItem(to)
                // 只在书与书之间交换，避免与文件夹交错
                if (fromItem !is ShelfItem.Book || toItem !is ShelfItem.Book) return false
                adapter.moveItem(from, to)
                dragOrderDirty = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (dragOrderDirty) {
                    persistCustomOrderFromList()
                    dragOrderDirty = false
                }
            }
        }
        bookDragHelper = ItemTouchHelper(callback)
        bookDragHelper.attachToRecyclerView(binding.rvShelf)
    }

    private fun persistCustomOrderFromList() {
        val bookIds = adapter.currentItems().mapNotNull { (it as? ShelfItem.Book)?.book?.id }
        if (bookIds.isEmpty()) return
        // 绑定目录 / 阅读历史不支持
        if (isBrowseOnlyFolder()) return
        val folderId = currentVirtualShelfId()
        BookshelfStore.setBookOrder(this, folderId, bookIds)
        if (AppSettings.shelfSort(this) != ShelfSort.CUSTOM) {
            AppSettings.setShelfSort(this, ShelfSort.CUSTOM)
            updateSortButton()
            Toasts.show(this, getString(R.string.shelf_sort_current, sortLabel(ShelfSort.CUSTOM)))
        }
    }

    private fun confirmDeleteLinked(folder: ShelfFolder) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_linked_folder)
            .setMessage(R.string.delete_linked_folder_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                BookshelfStore.deleteFolder(this, folder.id, moveBooksToRoot = false)
                if (currentFolderId == folder.id) {
                    currentFolderId = null
                    linkedPathStack.clear()
                }
                refreshShelf()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) return
        val need = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, need) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(need))
        }
    }

    private fun ensureAllFilesAccessPrompt() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (StorageAccess.hasAllFilesAccess()) return
        val prefs = getSharedPreferences("reader_ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_all_files", false)) return
        prefs.edit().putBoolean("asked_all_files", true).apply()
        promptAllFilesAccess()
    }

    private fun promptAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (StorageAccess.hasAllFilesAccess()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_all_files_title)
            .setMessage(R.string.permission_all_files_message)
            .setPositiveButton(R.string.permission_all_files_go) { _, _ ->
                allFilesAccessLauncher.launch(StorageAccess.manageAllFilesIntent(this))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

