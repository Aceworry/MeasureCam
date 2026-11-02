package com.measurecam.widget

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import com.measurecam.data.WatermarkArea
import com.measurecam.data.WatermarkPosition
import kotlin.math.min
import kotlin.math.roundToInt

/** FIT_CENTER 原图上方的透明水印。手指移动时只平移这一层。 */
class PhotoWatermarkDrag(private val view: ImageView, private val changed: (WatermarkPosition) -> Unit) {
    private var stamp: BitmapDrawable? = null
    private var area: WatermarkArea? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var point = WatermarkPosition(0f, 1f)
    private var downX = 0f
    private var downY = 0f
    private var start = point
    private var moving = false
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop

    init {
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        view.setOnTouchListener { _, event -> touch(event) }
    }

    fun show(base: Bitmap, layer: Bitmap?, travel: WatermarkArea?, position: WatermarkPosition) {
        clear()
        imageWidth = base.width
        imageHeight = base.height
        area = travel
        point = position
        view.setImageBitmap(base)
        if (layer != null) {
            stamp = BitmapDrawable(view.resources, layer).also { view.overlay.add(it) }
            place()
        }
    }

    fun clear() {
        stamp?.let { view.overlay.remove(it) }
        stamp = null
        moving = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun scale(): Float = min(
        (view.width - view.paddingLeft - view.paddingRight).toFloat() / imageWidth,
        (view.height - view.paddingTop - view.paddingBottom).toFloat() / imageHeight
    )

    private fun place() {
        val layer = stamp ?: return
        val travel = area ?: return
        val scale = scale()
        val insetX = (view.width - view.paddingLeft - view.paddingRight - imageWidth * scale) / 2f + view.paddingLeft
        val insetY = (view.height - view.paddingTop - view.paddingBottom - imageHeight * scale) / 2f + view.paddingTop
        val x = insetX + travel.x(point) * scale
        val y = insetY + travel.y(point) * scale
        layer.setBounds(x.roundToInt(), y.roundToInt(), (x + layer.bitmap.width * scale).roundToInt(), (y + layer.bitmap.height * scale).roundToInt())
    }

    private fun touch(event: MotionEvent): Boolean {
        val layer = stamp ?: return false
        val travel = area ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = RectF(layer.bounds).apply { inset(-slop.toFloat(), -slop.toFloat()) }
                if (!hit.contains(event.x, event.y)) return false
                start = point
                downX = event.x; downY = event.y; moving = false
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (moving || kotlin.math.hypot(dx, dy) > slop) {
                    moving = true
                    val scale = scale()
                    if (scale > 0) point = travel.positionAt(travel.x(start) + dx / scale, travel.y(start) + dy / scale)
                    place()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (moving) changed(point) else view.performClick()
                    moving = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                point = start; moving = false; place()
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
