package com.whj.reader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.util.concurrent.atomic.AtomicInteger

/**
 * 从 PDF 内嵌大纲（Bookmarks / TOC）提取树形目录。
 *
 * 注意：[Node] 按 [id] 相等，禁止用含子树的 data class 默认 equals（会递归栈溢出）。
 */
object PdfOutlineLoader {
    private const val TAG = "PdfOutline"

    class Node(
        val id: Int,
        val title: String,
        /** 0-based 页码；未知为 -1 */
        val pageIndex: Int,
        val children: List<Node> = emptyList(),
    ) {
        override fun equals(other: Any?): Boolean = other is Node && other.id == id
        override fun hashCode(): Int = id
    }

    fun load(context: Context, uri: Uri): List<Node> {
        PdfTextExtractor.ensureInit(context)
        // 优先用已打开的阅读会话，避免整本再 load 一遍
        val fromSession = PdfTextExtractor.withSessionDocument { doc ->
            loadFromDocument(doc)
        }
        if (fromSession != null) return fromSession
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    loadFromDocument(doc)
                }
            } ?: emptyList()
        } catch (t: Throwable) {
            Log.e(TAG, "load outline failed", t)
            emptyList()
        }
    }

    /** 从已打开的 PDDocument 解析大纲（不关闭文档） */
    fun loadFromDocument(doc: PDDocument): List<Node> {
        return try {
            val outline = try {
                doc.documentCatalog?.documentOutline
            } catch (t: Throwable) {
                Log.w(TAG, "get outline failed", t)
                null
            } ?: return emptyList()
            val idGen = AtomicInteger(0)
            parseKids(outline, doc, idGen)
        } catch (t: Throwable) {
            Log.e(TAG, "loadFromDocument failed", t)
            emptyList()
        }
    }

    private fun parseKids(
        parent: PDOutlineNode,
        doc: PDDocument,
        idGen: AtomicInteger,
    ): List<Node> {
        val out = ArrayList<Node>()
        var item: PDOutlineItem? = try {
            parent.firstChild
        } catch (t: Throwable) {
            Log.w(TAG, "firstChild failed", t)
            null
        }
        var guard = 0
        while (item != null && guard++ < 50_000) {
            try {
                val title = try {
                    item.title?.trim().orEmpty()
                } catch (_: Throwable) {
                    ""
                }.ifBlank { "…" }
                val page = resolvePageIndex(item, doc)
                val children = try {
                    if (item.hasChildren()) parseKids(item, doc, idGen) else emptyList()
                } catch (t: Throwable) {
                    Log.w(TAG, "parse children failed: $title", t)
                    emptyList()
                }
                out.add(
                    Node(
                        id = idGen.incrementAndGet(),
                        title = title.take(200),
                        pageIndex = page,
                        children = children,
                    ),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "parse outline item failed", t)
            }
            item = try {
                item.nextSibling
            } catch (t: Throwable) {
                Log.w(TAG, "nextSibling failed", t)
                null
            }
        }
        return out
    }

    private fun resolvePageIndex(item: PDOutlineItem, doc: PDDocument): Int {
        return try {
            val dest = try {
                item.destination
            } catch (_: Throwable) {
                null
            } ?: try {
                (item.action as? PDActionGoTo)?.destination
            } catch (_: Throwable) {
                null
            }
            when (dest) {
                is PDPageDestination -> {
                    try {
                        val page = dest.page
                        if (page != null) {
                            val idx = doc.pages.indexOf(page)
                            if (idx >= 0) return idx
                        }
                    } catch (_: Throwable) {
                    }
                    try {
                        val n = dest.pageNumber
                        if (n >= 0) return n.coerceAtMost(doc.numberOfPages - 1)
                    } catch (_: Throwable) {
                    }
                    -1
                }
                is PDNamedDestination -> {
                    try {
                        val name = dest.namedDestination ?: return -1
                        val names = doc.documentCatalog?.names?.dests ?: return -1
                        val resolved = names.getValue(name)
                        if (resolved is PDPageDestination) {
                            val page = resolved.page
                            if (page != null) {
                                val idx = doc.pages.indexOf(page)
                                if (idx >= 0) return idx
                            }
                            val n = resolved.pageNumber
                            if (n >= 0) return n.coerceAtMost(doc.numberOfPages - 1)
                        }
                    } catch (_: Throwable) {
                    }
                    -1
                }
                else -> -1
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resolvePageIndex failed", t)
            -1
        }
    }

    /**
     * 从根到「当前页所在章节」的路径（有序：根→…→当前节）。
     */
    fun pathToCurrent(roots: List<Node>, currentPage: Int): List<Node> {
        if (roots.isEmpty() || currentPage < 0) return emptyList()
        var bestPath: List<Node> = emptyList()
        var bestPage = -1
        fun walk(nodes: List<Node>, stack: ArrayList<Node>) {
            for (n in nodes) {
                stack.add(n)
                if (n.pageIndex in 0..currentPage && n.pageIndex >= bestPage) {
                    bestPage = n.pageIndex
                    bestPath = ArrayList(stack)
                }
                if (n.children.isNotEmpty()) walk(n.children, stack)
                stack.removeAt(stack.lastIndex)
            }
        }
        walk(roots, ArrayList())
        return bestPath
    }

    /**
     * 视口内（DFS 序）第一个大纲标题：页码落在 [viewportTopPage, viewportBottomPage]。
     */
    fun firstTitleInViewport(
        roots: List<Node>,
        viewportTopPage: Int,
        viewportBottomPage: Int,
    ): Node? {
        if (roots.isEmpty() || viewportTopPage < 0) return null
        val top = viewportTopPage
        val bottom = viewportBottomPage.coerceAtLeast(top)
        var found: Node? = null
        fun walk(nodes: List<Node>) {
            for (n in nodes) {
                if (found == null && n.pageIndex in top..bottom && n.pageIndex >= 0) {
                    found = n
                }
                if (found == null && n.children.isNotEmpty()) walk(n.children)
            }
        }
        walk(roots)
        return found
    }

    /**
     * 当前最深子章节：
     * - 视口内有标题 → 限在该标题子树内取最深节（同页后续兄弟如 9.1.8 不抢 9.1.7）
     * - 无视口标题 → 正文页所属最深节
     */
    fun resolveCurrentLeaf(
        roots: List<Node>,
        viewportTopPage: Int,
        viewportBottomPage: Int,
        bodyPage: Int,
    ): Node? {
        if (roots.isEmpty() || bodyPage < 0) return null
        val firstTitle = firstTitleInViewport(roots, viewportTopPage, viewportBottomPage)
        if (firstTitle != null) {
            return deepestInSubtreeAtPage(firstTitle, bodyPage) ?: firstTitle
        }
        return pathToCurrent(roots, bodyPage).lastOrNull()
    }

    /** 子树内 pageIndex ≤ [page] 的最深节点 */
    private fun deepestInSubtreeAtPage(root: Node, page: Int): Node? {
        var best: Node? = null
        var bestPage = -1
        fun walk(n: Node) {
            if (n.pageIndex in 0..page && n.pageIndex >= bestPage) {
                bestPage = n.pageIndex
                best = n
            }
            for (c in n.children) walk(c)
        }
        walk(root)
        return best
    }

    /** 从根到指定节点的路径 */
    fun pathToNode(roots: List<Node>, target: Node): List<Node>? {
        var result: List<Node>? = null
        fun walk(nodes: List<Node>, stack: ArrayList<Node>) {
            for (n in nodes) {
                stack.add(n)
                if (n.id == target.id) result = ArrayList(stack)
                if (result == null && n.children.isNotEmpty()) walk(n.children, stack)
                stack.removeAt(stack.lastIndex)
            }
        }
        walk(roots, ArrayList())
        return result
    }

    /** 默认展开：当前节路径上有子节点的祖先 */
    fun defaultExpandedForLeaf(roots: List<Node>, leaf: Node?): MutableSet<Node> {
        val path = leaf?.let { pathToNode(roots, it) } ?: emptyList()
        return path.filter { it.children.isNotEmpty() }.toMutableSet()
    }

    /** 默认展开：当前节路径上有子节点的祖先 */
    fun defaultExpanded(roots: List<Node>, currentPage: Int): MutableSet<Node> {
        val path = pathToCurrent(roots, currentPage)
        return path.filter { it.children.isNotEmpty() }.toMutableSet()
    }
}
