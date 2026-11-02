package com.measurecam.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * 推拉式变焦条。
 *
 * 为什么不用点按钮循环档位：工地上要一边看构图一边推，手指在屏幕上按住往上推、
 * 画面跟着变，这叫"所见即所得"；点按钮是盲跳，跳到 4× 发现过了还得再点一圈。
 *
 * 为什么要对数映射：线性映射下 1×→2× 只占 1/9 的行程，2×→10× 占 8/9，
 * 手感完全崩了。改成 ln 映射后，每一档在轨道上占的宽度是一样的。
 */
class ZoomDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        /** 常规档位，设备不支持就自动过滤 */
        private val PRESET_VALUES = floatArrayOf(1f, 2f, 4f, 8f)
    }

    var minRatio: Float = 1f
        private set
    var maxRatio: Float = 1f
        private set

    /** 当前倍率，只在内部改动，外部通过 [setRatio] 同步 */
    private var ratio: Float = 1f

    /** 拖动 / 点档位时回调，用来实时推给相机 */
    var onRatioChanged: ((Float) -> Unit)? = null

    /** 松手时回调，用来落盘和收敛 */
    var onRatioSettled: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6FD3A8.toInt() }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val thumbHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }

    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val tickText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
    }

    private val thumbR = dp(8f)
    private val tickR = dp(2.2f)
    private val trackH = dp(3f)
    private val touchSlop = dp(14f)

    private var dragging = false

    /** onDraw 每帧都会跑，矩形对象复用，别在绘制里分配 */
    private val tmpRect = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    // ---------------- 对外接口 ----------------

    /**
     * 设定设备支持的变焦范围。范围变了（换镜头/重开相机）时调用，
     * 会把当前倍率夹到区间内。
     */
    fun setRange(min: Float, max: Float, current: Float? = null) {
        val newMin = min.coerceAtLeast(0.1f)
        val newMax = max.coerceAtLeast(newMin)
        val newRatio = (current ?: ratio).coerceIn(newMin, newMax)
        // zoomState 会随着每次变焦回调，这里不动就跳过重绘
        val changed = newMin != minRatio || newMax != maxRatio ||
                abs(newRatio - ratio) > 0.001f
        minRatio = newMin
        maxRatio = newMax
        ratio = newRatio
        if (changed) {
            contentDescription = "变焦 ${fmt(ratio)}"
            invalidate()
        }
    }

    /** 外部（双指捏合）改了倍率时同步进来 */
    fun setRatio(r: Float) {
        if (maxRatio <= minRatio) return
        val v = r.coerceIn(minRatio, maxRatio)
        if (abs(v - ratio) < 0.001f) return
        ratio = v
        contentDescription = "变焦 ${fmt(ratio)}"
        invalidate()
    }

    /** 可用档位：1/2/4/8 里设备支持的，外加设备上限 */
    fun presets(): List<Float> {
        if (maxRatio <= minRatio) return listOf(ratio)
        val out = LinkedHashSet<Float>()
        out.add(minRatio)
        PRESET_VALUES.forEach { if (it > minRatio && it < maxRatio) out.add(it) }
        out.add(maxRatio)
        return out.toList().sorted()
    }

    // ---------------- 位置换算 ----------------

    private val trackLeft: Float get() = paddingLeft + dp(24f)
    private val trackRight: Float get() = width - paddingRight - dp(24f)
    private val trackSpan: Float get() = (trackRight - trackLeft).coerceAtLeast(1f)
    private val trackY: Float get() = (height * 0.56f).coerceAtLeast(paddingTop + dp(38f))
        .coerceAtMost(height - paddingBottom - dp(24f))

    /** 倍率 → 归一化位置 0..1（对数） */
    private fun tOf(r: Float): Float {
        if (maxRatio <= minRatio) return 0f
        val a = ln(minRatio.toDouble())
        val b = ln(maxRatio.toDouble())
        return ((ln(r.coerceIn(minRatio, maxRatio).toDouble()) - a) / (b - a))
            .toFloat().coerceIn(0f, 1f)
    }

    private fun xOf(r: Float): Float = trackLeft + trackSpan * tOf(r)

    private fun ratioAt(x: Float): Float {
        if (maxRatio <= minRatio) return minRatio
        val t = ((x - trackLeft) / trackSpan).coerceIn(0f, 1f)
        return (minRatio * (maxRatio / minRatio).toDouble().pow(t.toDouble())).toFloat()
    }

    private fun fmt(r: Float): String =
        if (r >= 9.95f) String.format(java.util.Locale.US, "%.0f×", r)
        else String.format(java.util.Locale.US, "%.1f×", r)

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        val cx = xOf(ratio)

        // 轨道
        val r = trackH / 2f
        tmpRect.set(trackLeft, trackY - r, trackRight, trackY + r)
        canvas.drawRoundRect(tmpRect, r, r, trackPaint)
        // 已推过的部分
        if (cx > trackLeft) {
            tmpRect.set(trackLeft, trackY - r, cx, trackY + r)
            canvas.drawRoundRect(tmpRect, r, r, activePaint)
        }

        // 档位刻度
        val ps = presets()
        ps.forEach { p ->
            val x = xOf(p)
            val active = abs(p - ratio) < 0.08f
            canvas.drawCircle(x, trackY, if (active) tickR * 1.7f else tickR, tickPaint)
        }

        // 刻度文字要避让：8× 和 10× 在对数轴上贴得很近，一起画就糊成一团。
        // 两端（最小/最大）优先保住，中间的谁撞上谁让路。
        val reserved = ArrayList<Pair<Float, Float>>(ps.size)
        fun reserve(cx: Float, halfW: Float): Boolean {
            if (reserved.any { cx - halfW < it.second && cx + halfW > it.first }) return false
            reserved.add((cx - halfW) to (cx + halfW))
            return true
        }

        val ordered = buildList {
            if (ps.isNotEmpty()) {
                add(ps.first())
                if (ps.size > 2) addAll(ps.subList(1, ps.size - 1))
                if (ps.size > 1) add(ps.last())
            }
        }
        ordered.forEach { p ->
            val label = fmt(p)
            val half = tickText.measureText(label) / 2f + dp(3f)
            val x = xOf(p)
            // 文字太靠边会被裁掉，干脆不画（气泡里已经有当前值了）
            if (x - half < 0f || x + half > width) return@forEach
            if (reserve(x, half)) {
                canvas.drawText(label, x, trackY + dp(16f), tickText)
            }
        }

        // 手柄
        canvas.drawCircle(cx, trackY, thumbR * 2.1f, thumbHaloPaint)
        canvas.drawCircle(cx, trackY, thumbR, thumbPaint)

        // 倍率气泡：跟着手柄走，靠边时自动收进可视区
        val text = fmt(ratio)
        val tw = valueText.measureText(text)
        val bw = tw + dp(16f)
        val bh = dp(20f)
        val bCx = cx.coerceIn(bw / 2f, width - bw / 2f)
        val by = (trackY - thumbR - dp(8f) - bh).coerceAtLeast(paddingTop.toFloat())
        tmpRect.set(bCx - bw / 2f, by, bCx + bw / 2f, by + bh)
        canvas.drawRoundRect(tmpRect, bh / 2f, bh / 2f, badgePaint)
        canvas.drawText(text, bCx, by + bh / 2f - (valueText.descent() + valueText.ascent()) / 2f, valueText)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(220f).toInt(), widthMeasureSpec)
        val h = resolveSize(dp(80f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ---------------- 触摸 ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || maxRatio <= minRatio) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                // 点在档位附近就直接吸过去，省得在窄区间里微调
                val hit = presets().firstOrNull { abs(xOf(it) - event.x) <= touchSlop }
                if (hit != null) {
                    applyRatio(hit, settle = true)
                } else {
                    applyRatio(ratioAt(event.x), settle = false)
                }
                isPressed = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) applyRatio(ratioAt(event.x), settle = false)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    isPressed = false
                    dragging = false
                    onRatioSettled?.invoke(ratio)
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyRatio(v: Float, settle: Boolean) {
        val nv = v.coerceIn(minRatio, maxRatio)
        val changed = abs(nv - ratio) > 0.001f
        ratio = nv
        contentDescription = "变焦 ${fmt(ratio)}"
        invalidate()
        if (changed) onRatioChanged?.invoke(nv)
        if (settle) onRatioSettled?.invoke(nv)
    }
}
