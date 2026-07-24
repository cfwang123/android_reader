package com.whj.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookLoader
import com.whj.reader.data.BookSearcher
import com.whj.reader.data.TextLoader
import com.whj.reader.databinding.ActivityBookSearchBinding
import com.whj.reader.databinding.ItemSearchResultBinding
import com.whj.reader.ui.AppTheme
import com.whj.reader.util.Toasts
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书内搜索：范围（全书/当前章/当前页/已 OCR）、上下文预览、上/下导航。
 */
class BookSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KIND = "kind"
        const val EXTRA_CURRENT_PARA = "currentPara"
        const val EXTRA_CURRENT_PAGE = "currentPage"
        const val EXTRA_CHAPTER_STARTS = "chapterStarts"
        const val KIND_TXT = "txt"
        const val KIND_PDF = "pdf"

        const val RESULT_PARA_INDEX = "paraIndex"
        const val RESULT_CHAR_OFFSET = "charOffset"
        const val RESULT_PAGE_INDEX = "pageIndex"
        const val RESULT_MATCH_LENGTH = "matchLength"

        fun intentTxt(
            activity: Activity,
            uri: String,
            title: String,
            currentParagraph: Int = 0,
            chapterStarts: IntArray = intArrayOf(),
        ): Intent =
            Intent(activity, BookSearchActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KIND, KIND_TXT)
                .putExtra(EXTRA_CURRENT_PARA, currentParagraph)
                .putExtra(EXTRA_CHAPTER_STARTS, chapterStarts)

        fun intentPdf(
            activity: Activity,
            uri: String,
            title: String,
            currentPage: Int = 0,
        ): Intent =
            Intent(activity, BookSearchActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KIND, KIND_PDF)
                .putExtra(EXTRA_CURRENT_PAGE, currentPage)
    }

    private lateinit var binding: ActivityBookSearchBinding
    private var searchJob: Job? = null
    private val adapter = ResultAdapter { hit -> selectHit(hit, scroll = true) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val uriStr: String by lazy { intent.getStringExtra(EXTRA_URI).orEmpty() }
    private val kind: String by lazy { intent.getStringExtra(EXTRA_KIND) ?: KIND_TXT }
    private val currentPara: Int by lazy {
        intent.getIntExtra(EXTRA_CURRENT_PARA, 0).coerceAtLeast(0)
    }
    private val currentPage: Int by lazy {
        intent.getIntExtra(EXTRA_CURRENT_PAGE, 0).coerceAtLeast(0)
    }
    private val chapterStarts: IntArray by lazy {
        intent.getIntArrayExtra(EXTRA_CHAPTER_STARTS) ?: intArrayOf()
    }

    private var scope: BookSearcher.SearchScope = BookSearcher.SearchScope.FULL
    private var selectedIndex: Int = -1
    private var lastQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityBookSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        setupScopeChips()
        binding.btnPrev.setOnClickListener { moveSelection(-1) }
        binding.btnNext.setOnClickListener { moveSelection(1) }
        binding.btnJump.setOnClickListener { jumpToSelected() }

        binding.etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                runSearch()
                true
            } else {
                false
            }
        }
        binding.etQuery.requestFocus()
        binding.etQuery.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etQuery, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupScopeChips() {
        val group = binding.chipScope
        group.removeAllViews()
        val scopes = if (kind == KIND_PDF) {
            listOf(
                BookSearcher.SearchScope.FULL to R.string.search_scope_all,
                BookSearcher.SearchScope.CURRENT_PAGE to R.string.search_scope_page,
                BookSearcher.SearchScope.OCR_PAGES to R.string.search_scope_ocr,
            )
        } else {
            listOf(
                BookSearcher.SearchScope.FULL to R.string.search_scope_all,
                BookSearcher.SearchScope.CURRENT_CHAPTER to R.string.search_scope_chapter,
            )
        }
        var initializing = true
        scopes.forEachIndexed { i, (s, labelRes) ->
            val chip = Chip(this).apply {
                id = android.view.View.generateViewId()
                text = getString(labelRes)
                isCheckable = true
                isChecked = i == 0
                tag = s
                setOnCheckedChangeListener { _, checked ->
                    if (checked && scope != s) {
                        scope = s
                        if (!initializing && lastQuery.isNotBlank()) runSearch()
                    }
                }
            }
            group.addView(chip)
        }
        initializing = false
        scope = scopes.first().first
    }

    private fun runSearch() {
        val q = binding.etQuery.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) {
            Toasts.show(this, R.string.search_empty_query)
            return
        }
        if (uriStr.isBlank()) {
            Toasts.show(this, getString(R.string.search_failed, "no uri"))
            return
        }
        lastQuery = q
        hideKeyboard()
        searchJob?.cancel()
        selectedIndex = -1
        binding.rowNav.isVisible = false
        binding.progress.isVisible = true
        binding.tvStatus.isVisible = true
        binding.tvStatus.setText(R.string.search_searching)
        adapter.submit(emptyList(), selectedIndex = -1, query = q)

        val active = AtomicBoolean(true)
        val pendingUi = ArrayList<BookSearcher.Hit>(24)
        var lastFlushMs = 0L
        val flushRunnable = object : Runnable {
            override fun run() {
                if (!active.get() || isFinishing || isDestroyed) return
                if (pendingUi.isEmpty()) return
                val batch = ArrayList(pendingUi)
                pendingUi.clear()
                adapter.append(batch, query = q)
                binding.tvStatus.text = getString(
                    R.string.search_result_count_live,
                    adapter.itemCount,
                )
            }
        }
        fun postHit(hit: BookSearcher.Hit) {
            mainHandler.post {
                if (!active.get() || isFinishing || isDestroyed) return@post
                pendingUi.add(hit)
                val now = SystemClock.uptimeMillis()
                if (pendingUi.size >= 10 || now - lastFlushMs >= 80L) {
                    lastFlushMs = now
                    mainHandler.removeCallbacks(flushRunnable)
                    flushRunnable.run()
                } else {
                    mainHandler.removeCallbacks(flushRunnable)
                    mainHandler.postDelayed(flushRunnable, 80L)
                }
            }
        }

        searchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val all = ArrayList<BookSearcher.Hit>(64)
                    when (kind) {
                        KIND_PDF -> {
                            val uri = Uri.parse(uriStr)
                            BookSearcher.searchPdf(
                                context = this@BookSearchActivity,
                                uri = uri,
                                fileKey = uriStr,
                                query = q,
                                scope = scope,
                                currentPage = currentPage,
                                marginsForPage = { page ->
                                    AppSettings.pdfCropMarginsForPage(
                                        this@BookSearchActivity,
                                        uriStr,
                                        page,
                                    )
                                },
                                isActive = { active.get() },
                                onHit = { hit ->
                                    all.add(hit)
                                    postHit(hit)
                                    all.size < BookSearcher.MAX_RESULTS
                                },
                            )
                            all
                        }
                        else -> {
                            val uri = Uri.parse(uriStr)
                            val book = if (uriStr.startsWith("asset://")) {
                                val path = uriStr.removePrefix("asset://")
                                TextLoader.loadFromAssets(
                                    this@BookSearchActivity,
                                    path,
                                    intent.getStringExtra(EXTRA_TITLE) ?: path,
                                )
                            } else {
                                BookLoader.loadFromUri(
                                    this@BookSearchActivity,
                                    uri,
                                    intent.getStringExtra(EXTRA_TITLE),
                                )
                            }
                            val chapters = chapterStarts.map { start ->
                                com.whj.reader.model.Chapter("", start)
                            }
                            BookSearcher.searchTxtStreaming(
                                paragraphs = book.paragraphs,
                                query = q,
                                scope = scope,
                                currentParagraph = currentPara,
                                chapters = chapters,
                                isActive = { active.get() },
                            ) { hit ->
                                all.add(hit)
                                postHit(hit)
                                all.size < BookSearcher.MAX_RESULTS
                            }
                            all
                        }
                    }
                }
            }
            active.set(false)
            mainHandler.removeCallbacks(flushRunnable)
            if (pendingUi.isNotEmpty() && !isFinishing && !isDestroyed) {
                adapter.append(ArrayList(pendingUi), query = q)
                pendingUi.clear()
            }
            binding.progress.isVisible = false
            result.onSuccess { hits ->
                if (adapter.itemCount == 0 && hits.isNotEmpty()) {
                    adapter.submit(hits, selectedIndex = 0, query = q)
                }
                binding.tvStatus.isVisible = true
                val n = maxOf(adapter.itemCount, hits.size)
                binding.tvStatus.text = when {
                    n == 0 -> getString(R.string.search_no_result)
                    n >= BookSearcher.MAX_RESULTS ->
                        getString(R.string.search_result_count_capped, n)
                    else -> getString(R.string.search_result_count, n)
                }
                if (n > 0) {
                    selectHit(adapter.itemAt(0), scroll = false)
                } else {
                    binding.rowNav.isVisible = false
                }
            }.onFailure { e ->
                binding.tvStatus.isVisible = true
                binding.tvStatus.text = getString(R.string.search_failed, e.message ?: "")
                binding.rowNav.isVisible = false
                Toasts.show(
                    this@BookSearchActivity,
                    getString(R.string.search_failed, e.message ?: ""),
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                active.set(false)
                mainHandler.removeCallbacks(flushRunnable)
            }
        }
    }

    private fun selectHit(hit: BookSearcher.Hit, scroll: Boolean) {
        val idx = adapter.indexOf(hit)
        if (idx < 0) return
        selectedIndex = idx
        adapter.setSelectedIndex(idx)
        updateNavBar()
        binding.rowNav.isVisible = adapter.itemCount > 0
        if (scroll) {
            binding.rvResults.smoothScrollToPosition(idx)
        }
    }

    private fun moveSelection(delta: Int) {
        if (adapter.itemCount == 0) return
        val next = (selectedIndex + delta).coerceIn(0, adapter.itemCount - 1)
        if (next == selectedIndex) return
        selectHit(adapter.itemAt(next), scroll = true)
    }

    private fun updateNavBar() {
        val total = adapter.itemCount
        if (total <= 0 || selectedIndex < 0) {
            binding.rowNav.isVisible = false
            return
        }
        binding.tvNavIndex.text = getString(
            R.string.search_nav_index,
            selectedIndex + 1,
            total,
        )
        binding.btnPrev.isEnabled = selectedIndex > 0
        binding.btnNext.isEnabled = selectedIndex < total - 1
    }

    private fun jumpToSelected() {
        if (selectedIndex < 0 || selectedIndex >= adapter.itemCount) return
        finishWithHit(adapter.itemAt(selectedIndex))
    }

    private fun finishWithHit(hit: BookSearcher.Hit) {
        val data = Intent()
        if (hit.isPdf) {
            data.putExtra(RESULT_PAGE_INDEX, hit.index)
            data.putExtra(RESULT_CHAR_OFFSET, hit.offset)
        } else {
            data.putExtra(RESULT_PARA_INDEX, hit.index)
            data.putExtra(RESULT_CHAR_OFFSET, hit.offset)
        }
        data.putExtra(RESULT_MATCH_LENGTH, hit.length.coerceAtLeast(1))
        setResult(RESULT_OK, data)
        finish()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etQuery.windowToken, 0)
    }

    private inner class ResultAdapter(
        private val onSelect: (BookSearcher.Hit) -> Unit,
    ) : RecyclerView.Adapter<ResultAdapter.VH>() {
        private val items = ArrayList<BookSearcher.Hit>(64)
        private var selected = -1
        private var highlightQuery = ""

        fun submit(list: List<BookSearcher.Hit>, selectedIndex: Int, query: String) {
            items.clear()
            items.addAll(list)
            selected = selectedIndex
            highlightQuery = query
            notifyDataSetChanged()
        }

        fun append(list: List<BookSearcher.Hit>, query: String) {
            if (list.isEmpty()) return
            highlightQuery = query
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
        }

        fun itemAt(index: Int): BookSearcher.Hit = items[index]

        fun indexOf(hit: BookSearcher.Hit): Int = items.indexOfFirst {
            it.index == hit.index && it.offset == hit.offset && it.isPdf == hit.isPdf
        }

        fun setSelectedIndex(index: Int) {
            val old = selected
            selected = index
            if (old >= 0) notifyItemChanged(old)
            if (index >= 0) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSearchResultBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], position == selected)
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(hit: BookSearcher.Hit, isSelected: Boolean) {
                b.tvLoc.text = when {
                    hit.isPdf && hit.fromOcr ->
                        getString(R.string.search_loc_pdf_ocr, hit.locationLabelValue)
                    hit.isPdf ->
                        getString(R.string.search_loc_pdf, hit.locationLabelValue)
                    else ->
                        getString(R.string.search_loc_txt, hit.locationLabelValue)
                }
                b.tvContext.text = highlightContext(hit.context, highlightQuery)
                val bg = if (isSelected) {
                    ContextCompat.getColor(this@BookSearchActivity, R.color.read_bg_default)
                } else {
                    android.graphics.Color.TRANSPARENT
                }
                b.root.setBackgroundColor(bg)
                b.root.setOnClickListener { onSelect(hit) }
                b.root.setOnLongClickListener {
                    selectHit(hit, scroll = true)
                    jumpToSelected()
                    true
                }
            }
        }
    }

    private fun highlightContext(context: String, query: String): CharSequence {
        if (query.isBlank()) return context
        val lowerCtx = context.lowercase()
        val lowerQ = query.lowercase()
        val idx = lowerCtx.indexOf(lowerQ)
        if (idx < 0) return context
        val span = SpannableString(context)
        val color = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            0xFF5B8C5A.toInt(),
        )
        span.setSpan(
            ForegroundColorSpan(color),
            idx,
            (idx + query.length).coerceAtMost(context.length),
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        span.setSpan(
            StyleSpan(Typeface.BOLD),
            idx,
            (idx + query.length).coerceAtMost(context.length),
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return span
    }
}
