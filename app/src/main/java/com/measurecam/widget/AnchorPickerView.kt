package com.measurecam.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.measurecam.data.WatermarkAnchor

/**
 * 九宫格位置选择器。
 *
 * 画成 3×3 的方格，点哪格水印就落哪格。比下拉框直观得多——
 * "放右上"这件事，用户脑子里想的是画面，不是列表项。
 */
class AnchorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var anchor: WatermarkAnchor = WatermarkAnchor.BOTTOM_LEFT
        private set

    var hasCustomPosition = false
        set(value) {
            field = value
            invalidate()
        }

    var onAnchorChanged: ((WatermarkAnchor) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x0D000000
        style = Paint.Style.FILL
    }
    private val cellStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1F000000
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E9E72.toInt()
        style = Paint.Style.FILL
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4D000000
        style = Paint.Style.FILL
    }

    private val gap = dp(6f)
    private val radius = dp(7f)

    init {
        isClickable = true
        contentDescription = "水印位置"
    }

    fun setAnchor(a: WatermarkAnchor, notify: Boolean = false) {
        if (a == anchor) { if (notify) onAnchorChanged?.invoke(a); return }
        anchor = a
        invalidate()
        if (notify) onAnchorChanged?.invoke(a)
    }

    private fun gridSize(): Float =
        minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()

    private fun cellSize(): Float = (gridSize() - gap * 2f) / 3f

    /** 绘制和命中测试都会反复算格子，结果直接写进 [out] 复用，不新建对象 */
    private fun cellRect(row: Int, col: Int, out: RectF): RectF {
        val cell = cellSize()
        val total = cell * 3f + gap * 2f
        val left0 = paddingLeft + (width - paddingLeft - paddingRight - total) / 2f
        val top0 = paddingTop + (height - paddingTop - paddingBottom - total) / 2f
        val l = left0 + col * (cell + gap)
        val t = top0 + row * (cell + gap)
        out.set(l, t, l + cell, t + cell)
        return out
    }

    private val tmpCell = RectF()
    private val tmpBar = RectF()

    override fun onDraw(canvas: Canvas) {
        for (row in 0..2) {
            for (col in 0..2) {
                val rect = cellRect(row, col, tmpCell)
                val isActive = !hasCustomPosition && anchor.row == row && anchor.col == col
                canvas.drawRoundRect(rect, radius, radius, if (isActive) activePaint else cellPaint)
                if (!isActive) canvas.drawRoundRect(rect, radius, radius, cellStroke)

                // 格子里画个小条示意水印块，选中格是白条
                val barW = rect.width() * 0.44f
                val barH = rect.height() * 0.16f
                val barLeft = when (col) {
                    0 -> rect.left + rect.width() * 0.14f
                    2 -> rect.right - rect.width() * 0.14f - barW
                    else -> rect.centerX() - barW / 2f
                }
                val barTop = when (row) {
                    0 -> rect.top + rect.height() * 0.16f
                    2 -> rect.bottom - rect.height() * 0.16f - barH
                    else -> rect.centerY() - barH / 2f
                }
                tmpBar.set(barLeft, barTop, barLeft + barW, barTop + barH)
                canvas.drawRoundRect(
                    tmpBar, barH / 2f, barH / 2f,
                    if (isActive) markPaint else barPaint
                )
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(132f).toInt(), widthMeasureSpec)
        val h = resolveSize(dp(132f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val probe = RectF()
            for (row in 0..2) {
                for (col in 0..2) {
                    if (cellRect(row, col, probe).contains(event.x, event.y)) {
                        setAnchor(WatermarkAnchor.of(row, col), notify = true)
                        performClick()
                        return true
                    }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
