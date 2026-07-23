package com.whj.reader.pdf.link

/**
 * PDF 书内链接前进/后退历史栈。
 */
class PdfLinkNavigator {

    private val backStack = ArrayDeque<Int>()
    private val forwardStack = ArrayDeque<Int>()

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    fun clear() {
        backStack.clear()
        forwardStack.clear()
    }

    /**
     * 从 [fromPage] 跳到 [targetPage]：压入后退栈并清空前进栈。
     * @return 是否实际跳转（页码不同）
     */
    fun pushJump(fromPage: Int, targetPage: Int): Boolean {
        if (fromPage == targetPage) return false
        backStack.addLast(fromPage)
        forwardStack.clear()
        return true
    }

    /** @return 要回到的页；无历史返回 null */
    fun goBack(currentPage: Int): Int? {
        if (backStack.isEmpty()) return null
        val target = backStack.removeLast()
        forwardStack.addLast(currentPage)
        return target
    }

    fun goForward(currentPage: Int): Int? {
        if (forwardStack.isEmpty()) return null
        val target = forwardStack.removeLast()
        backStack.addLast(currentPage)
        return target
    }
}
