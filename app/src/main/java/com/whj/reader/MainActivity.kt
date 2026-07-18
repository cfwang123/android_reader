package com.whj.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookshelfStore
import com.whj.reader.data.FolderImporter
import com.whj.reader.databinding.ActivityMainBinding
import com.whj.reader.model.ShelfBook
import com.whj.reader.model.ShelfFolder
import com.whj.reader.ui.ShelfAdapter
import com.whj.reader.util.Toasts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ShelfAdapter

    /** null = 顶层；否则在某书架文件夹内 */
    private var currentFolderId: String? = null
    private var didAutoResume = false

    private val openDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
            }
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: getString(R.string.unnamed)
            BookshelfStore.addOrUpdateBook(
                this,
                uri = uri.toString(),
                displayName = name.removeSuffix(".txt").removeSuffix(".TXT"),
                folderId = currentFolderId,
                pathHint = name,
            )
            Toasts.show(this, R.string.book_added)
            refreshShelf()
            openReading(uri, name)
        }
    }

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FolderImporter.listTxtInTree(this@MainActivity, treeUri) }
            }
            result.onSuccess { imported ->
                if (imported.files.isEmpty()) {
                    Toasts.show(this@MainActivity, R.string.import_none)
                    return@onSuccess
                }
                // 在当前顶层则新建同名书架；已在文件夹内则导入到当前文件夹
                val targetFolderId = if (currentFolderId == null) {
                    val existing = BookshelfStore.folders(this@MainActivity)
                        .firstOrNull { it.name == imported.folderName }
                    (existing ?: BookshelfStore.createFolder(this@MainActivity, imported.folderName)).id
                } else {
                    currentFolderId
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BookshelfStore.ensureMigrated(this)

        adapter = ShelfAdapter(
            onFolderClick = { openFolder(it) },
            onBookClick = { openBook(it) },
            onFolderLongClick = { showFolderMenu(it) },
            onBookLongClick = { showBookMenu(it) },
        )
        binding.rvShelf.layoutManager = LinearLayoutManager(this)
        binding.rvShelf.adapter = adapter

        binding.btnOpenFile.setOnClickListener {
            openDocLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
        }
        binding.btnImportFolder.setOnClickListener {
            openTreeLauncher.launch(null)
        }
        binding.btnNewShelf.setOnClickListener {
            if (currentFolderId != null) {
                Toasts.show(this, R.string.shelf_one_level_only)
                return@setOnClickListener
            }
            promptNewShelf()
        }
        binding.btnSample.setOnClickListener {
            val uri = "asset://sample.txt"
            BookshelfStore.addOrUpdateBook(
                this,
                uri = uri,
                displayName = getString(R.string.sample_book),
                folderId = currentFolderId,
                pathHint = getString(R.string.builtin_sample),
            )
            startActivity(
                Intent(this, ReadingActivity::class.java)
                    .putExtra(ReadingActivity.EXTRA_ASSET, "sample.txt")
                    .putExtra(ReadingActivity.EXTRA_TITLE, getString(R.string.sample_book)),
            )
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnNavBack.setOnClickListener { navigateUp() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentFolderId != null) navigateUp() else finish()
                }
            },
        )

        requestStorageIfNeeded()
        val openedExternal = handleIncomingIntent(intent)
        refreshShelf()
        // 冷启动且非外部打开文件：自动恢复上次阅读
        if (savedInstanceState == null && !openedExternal) {
            tryAutoResumeLastBook()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshShelf()
    }

    /** @return 是否已处理外部打开意图 */
    private fun handleIncomingIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data!!
            val name = intent.getStringExtra(Intent.EXTRA_TITLE)
                ?: queryDisplayName(uri)
                ?: getString(R.string.unnamed)
            BookshelfStore.addOrUpdateBook(
                this,
                uri = uri.toString(),
                displayName = name.removeSuffix(".txt").removeSuffix(".TXT"),
                folderId = null,
                pathHint = name,
            )
            openReading(uri, name)
            return true
        }
        return false
    }

    private fun openFolder(folder: ShelfFolder) {
        currentFolderId = folder.id
        refreshShelf()
    }

    private fun navigateUp() {
        currentFolderId = null
        refreshShelf()
    }

    private fun openBook(book: ShelfBook) {
        if (book.uri.startsWith("asset://")) {
            val path = book.uri.removePrefix("asset://")
            startActivity(
                Intent(this, ReadingActivity::class.java)
                    .putExtra(ReadingActivity.EXTRA_ASSET, path)
                    .putExtra(ReadingActivity.EXTRA_TITLE, book.displayName),
            )
            return
        }
        openReading(Uri.parse(book.uri), book.displayName)
    }

    private fun openReading(uri: Uri, title: String?) {
        startActivity(
            Intent(this, ReadingActivity::class.java)
                .putExtra(ReadingActivity.EXTRA_URI, uri.toString())
                .putExtra(ReadingActivity.EXTRA_TITLE, title),
        )
    }

    /** 启动时自动打开上次阅读的书（进度由 ReadingActivity 恢复） */
    private fun tryAutoResumeLastBook() {
        if (didAutoResume) return
        didAutoResume = true

        val lastUri = AppSettings.lastBookUri(this)
        val lastTitle = AppSettings.lastBookTitle(this)
        val book = when {
            !lastUri.isNullOrBlank() -> BookshelfStore.findBookByUri(this, lastUri)
            else -> null
        } ?: BookshelfStore.mostRecentBook(this)

        binding.root.post {
            when {
                book != null -> openBook(book)
                !lastUri.isNullOrBlank() && lastUri.startsWith("asset://") -> {
                    val path = lastUri.removePrefix("asset://")
                    startActivity(
                        Intent(this, ReadingActivity::class.java)
                            .putExtra(ReadingActivity.EXTRA_ASSET, path)
                            .putExtra(
                                ReadingActivity.EXTRA_TITLE,
                                lastTitle.ifBlank { getString(R.string.sample_book) },
                            ),
                    )
                }
                !lastUri.isNullOrBlank() -> {
                    openReading(
                        Uri.parse(lastUri),
                        lastTitle.ifBlank { null },
                    )
                }
            }
        }
    }

    private fun refreshShelf() {
        val items = if (currentFolderId == null) {
            binding.tvTitle.text = getString(R.string.bookshelf)
            binding.btnNavBack.isVisible = false
            binding.btnNewShelf.isVisible = true
            BookshelfStore.listRoot(this)
        } else {
            val folder = BookshelfStore.findFolder(this, currentFolderId!!)
            binding.tvTitle.text = folder?.name ?: getString(R.string.bookshelf)
            binding.btnNavBack.isVisible = true
            binding.btnNewShelf.isVisible = false
            BookshelfStore.listInFolder(this, currentFolderId!!)
        }
        adapter.submit(items)
        binding.tvEmpty.isVisible = items.isEmpty()
        binding.tvEmpty.setText(
            if (currentFolderId == null) R.string.empty_shelf else R.string.empty_folder,
        )
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

    private fun promptRenameFolder(folder: ShelfFolder) {
        val input = EditText(this).apply {
            setText(folder.name)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_shelf)
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
                BookshelfStore.deleteFolder(this, folder.id, moveBooksToRoot = true)
                if (currentFolderId == folder.id) currentFolderId = null
                refreshShelf()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBookMenu(book: ShelfBook) {
        val options = arrayOf(
            getString(R.string.move_book),
            getString(R.string.delete_book),
        )
        AlertDialog.Builder(this)
            .setTitle(book.displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showMoveBookDialog(book)
                    1 -> confirmDeleteBook(book)
                }
            }
            .show()
    }

    private fun showMoveBookDialog(book: ShelfBook) {
        val folders = BookshelfStore.folders(this)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_book)
            .setMessage(R.string.delete_book_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                BookshelfStore.removeBook(this, book.id)
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
}
