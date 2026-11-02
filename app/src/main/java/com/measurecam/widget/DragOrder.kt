package com.measurecam.widget

import android.content.ClipData
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 直接移动原来的 View，保留焦点内容、字段 ID 和尚未保存的输入。 */
object DragOrder {
    private data class Drag(val row: View, val parent: LinearLayout)
    fun handle(row: View, container: LinearLayout): TextView = TextView(row.context).apply {
        text = "☰"; textSize = 22f; gravity = android.view.Gravity.CENTER
        contentDescription = "长按拖动排序；点击可上移或下移"
        val size = (48 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
        setOnLongClickListener {
            row.startDragAndDrop(ClipData.newPlainText("order", ""), View.DragShadowBuilder(row), Drag(row, container), 0)
        }
        setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setTitle("调整顺序")
                .setItems(arrayOf("上移一项", "下移一项")) { _, i ->
                    val from = container.indexOfChild(row)
                    move(container, row, (from + if (i == 0) -1 else 1).coerceIn(0, container.childCount - 1))
                }.show()
        }
        container.setOnDragListener { _, event ->
            val drag = event.localState as? Drag
            if (drag?.parent !== container) return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> { drag.row.alpha = 0.35f; true }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val target = (0 until container.childCount).firstOrNull {
                        val child = container.getChildAt(it)
                        event.y < child.top + child.height / 2f
                    } ?: container.childCount
                    val from = container.indexOfChild(drag.row)
                    move(container, drag.row, (if (target > from) target - 1 else target).coerceIn(0, container.childCount - 1))
                    var parent = container.parent
                    while (parent != null && parent !is ScrollView) parent = parent.parent
                    (parent as? ScrollView)?.let { scroll ->
                        val here = IntArray(2); val there = IntArray(2)
                        container.getLocationOnScreen(here); scroll.getLocationOnScreen(there)
                        val y = here[1] + event.y - there[1]
                        val edge = 64 * resources.displayMetrics.density
                        if (y < edge) scroll.smoothScrollBy(0, -24)
                        else if (y > scroll.height - edge) scroll.smoothScrollBy(0, 24)
                    }; true
                }
                DragEvent.ACTION_DRAG_ENDED -> { drag.row.alpha = 1f; true }
                else -> true
            }
        }
    }
    fun move(container: LinearLayout, row: View, to: Int) {
        if (container.indexOfChild(row) == to) return
        container.removeView(row); container.addView(row, to)
    }
}
