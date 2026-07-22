package com.whj.reader.util

import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.PopupMenu

/** 在锚点下方、触点水平位置弹出菜单（保留主题白底） */
fun PopupMenu.showBelowTouchX(anchor: View, touchRawX: Float) {
    val loc = IntArray(2)
    anchor.getLocationOnScreen(loc)
    val xOff = (touchRawX - loc[0]).toInt().coerceIn(0, anchor.width.coerceAtLeast(1))
    applyTouchHorizontalOffset(xOff)
    show()
}

private fun PopupMenu.applyTouchHorizontalOffset(xOff: Int) {
    try {
        val popupField = PopupMenu::class.java.getDeclaredField("mPopup")
        popupField.isAccessible = true
        val helper = popupField.get(this) ?: return
        val listField = helper.javaClass.getDeclaredField("mPopup")
        listField.isAccessible = true
        val list = listField.get(helper) as? ListPopupWindow ?: return
        list.setDropDownGravity(Gravity.START)
        list.horizontalOffset = xOff
    } catch (_: Exception) {
    }
}
