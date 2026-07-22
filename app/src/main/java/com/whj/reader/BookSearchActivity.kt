package com.whj.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.whj.reader.ui.AppTheme
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whj.reader.data.AppSettings
import com.whj.reader.data.BookLoader
import com.whj.reader.data.BookSearcher
import com.whj.reader.data.TextLoader
import com.whj.reader.databinding.ActivityBookSearchBinding
import com.whj.reader.databinding.ItemSearchResultBinding
import com.whj.reader.util.Toasts
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书内搜索：TXT 显示位置%+上下文；PDF 显示页码+上下文。
 * 超长文本边搜边展示结果。
 */
class BookSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KIND = "kind"
        const val KIND_TXT = "txt"
        const val KIND_PDF = "pdf"

        const val RESULT_PARA_INDEX = "paraIndex"
        const val RESULT_CHAR_OFFSET = "charOffset"
        const val RESULT_PAGE_INDEX = "pageIndex"

        fun intentTxt(activity: Activity, uri: String, title: String): Intent =
            Intent(activity, BookSearchActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KIND, KIND_TXT)

        fun intentPdf(activity: Activity, uri: String, title: String): Intent =
            Intent(activity, BookSearchActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_KIND, KIND_PDF)
    }

    private lateinit var binding: ActivityBookSearchBinding
    private var searchJob: Job? = null
    private val adapter = ResultAdapter { hit -> onHitClick(hit) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val uriStr: String by lazy { intent.getStringExtra(EXTRA_URI).orEmpty() }
    private val kind: String by lazy { intent.getStringExtra(EXTRA_KIND) ?: KIND_TXT }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityBookSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

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
        hideKeyboard()
        searchJob?.cancel()
        binding.progress.isVisible = true
        binding.tvStatus.isVisible = true
        binding.tvStatus.setText(R.string.search_searching)
        adapter.submit(emptyList())

        val active = AtomicBoolean(true)
        val pendingUi = ArrayList<BookSearcher.Hit>(24)
        var lastFlushMs = 0L
        val flushRunnable = object : Runnable {
            override fun run() {
                if (!active.get() || isFinishing || isDestroyed) return
                if (pendingUi.isEmpty()) return
                val batch = ArrayList(pendingUi)
                pendingUi.clear()
                adapter.append(batch)
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
                                query = q,
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
                            BookSearcher.searchTxtStreaming(
                                book.paragraphs,
                                q,
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
            // 刷掉剩余
            if (pendingUi.isNotEmpty() && !isFinishing && !isDestroyed) {
                adapter.append(ArrayList(pendingUi))
                pendingUi.clear()
            }
            binding.progress.isVisible = false
            result.onSuccess { hits ->
                if (adapter.itemCount == 0 && hits.isNotEmpty()) {
                    adapter.submit(hits)
                }
                binding.tvStatus.isVisible = true
                val n = maxOf(adapter.itemCount, hits.size)
                binding.tvStatus.text = when {
                    n == 0 -> getString(R.string.search_no_result)
                    n >= BookSearcher.MAX_RESULTS ->
                        getString(R.string.search_result_count_capped, n)
                    else -> getString(R.string.search_result_count, n)
                }
            }.onFailure { e ->
                binding.tvStatus.isVisible = true
                binding.tvStatus.text = getString(R.string.search_failed, e.message ?: "")
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

    private fun onHitClick(hit: BookSearcher.Hit) {
        val data = Intent()
        if (hit.isPdf) {
            data.putExtra(RESULT_PAGE_INDEX, hit.index)
            data.putExtra(RESULT_CHAR_OFFSET, hit.offset)
        } else {
            data.putExtra(RESULT_PARA_INDEX, hit.index)
            data.putExtra(RESULT_CHAR_OFFSET, hit.offset)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etQuery.windowToken, 0)
    }

    private inner class ResultAdapter(
        private val onClick: (BookSearcher.Hit) -> Unit,
    ) : RecyclerView.Adapter<ResultAdapter.VH>() {
        private val items = ArrayList<BookSearcher.Hit>(64)

        fun submit(list: List<BookSearcher.Hit>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun append(list: List<BookSearcher.Hit>) {
            if (list.isEmpty()) return
            val start = items.size
            items.addAll(list)
            notifyItemRangeInserted(start, list.size)
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
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(hit: BookSearcher.Hit) {
                b.tvLoc.text = if (hit.isPdf) {
                    getString(R.string.search_loc_pdf, hit.locationLabelValue)
                } else {
                    getString(R.string.search_loc_txt, hit.locationLabelValue)
                }
                b.tvContext.text = hit.context
                b.root.setOnClickListener { onClick(hit) }
            }
        }
    }
}
