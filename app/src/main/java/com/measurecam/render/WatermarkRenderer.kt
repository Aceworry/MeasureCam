package com.measurecam.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import com.measurecam.data.RecordField
import com.measurecam.data.TextFormat
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.measurecam.data.CaptureMeta
import com.measurecam.data.WatermarkArea
import com.measurecam.data.WatermarkPosition
import com.measurecam.data.WatermarkAnchor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 把三层信息烧进照片像素（不是叠加预览，是真实写入）。
 *
 * 整块半透明黑底 + 圆角，内容左对齐，落点由 [WatermarkAnchor] 决定。
 *
 * 所有尺寸按**图片短边**等比缩放。用短边而不是宽度，是为了让横拍和竖拍
 * 的成片看起来一样大：竖向 3072×4096 的短边是宽，横向 4096×3072 的短边
 * 是高，两者算出来的字号、边距完全一致。如果按宽度算，横拍那张的字会
 * 突然大 33%，同一批照片摆在一起会显得没规矩。
 */
object WatermarkRenderer {

    /** 缩放基准：以 1080px 短边为参照设计字号 */
    private const val REFERENCE_WIDTH = 1080f
    private const val BASE_TEXT_SIZE = 30f
    private const val MARGIN_RATIO = 0.026f
    private const val MAX_TEXT_WIDTH_RATIO = 0.72f
    private const val ROW_GAP_RATIO = 0.20f

    private val BG_COLOR = Color.argb(0x99, 0, 0, 0)
    private val TEXT_MAIN = Color.WHITE
    private val TEXT_META = Color.parseColor("#DDEDEDED")
    /** 分类行的强调色，比正文稍暖，黑底上辨识度高又不刺眼 */
    private val TEXT_ACCENT = Color.parseColor("#FF8FE3B4")

    private data class Line(
        val text: CharSequence,
        val sizeFactor: Float,
        val color: Int,
        val bold: Boolean
    )

    /** 供取景页实时预览用；与最终成片完全同源，避免"预览和成片不一致" */
    fun previewText(meta: CaptureMeta): CharSequence = SpannableStringBuilder().apply {
        buildLines(meta).forEachIndexed { i, line ->
            if (i > 0) append("\n")
            val start = length
            append(line.text)
            // 先设置默认样式，字段自身的 span 随后覆盖。
            val text = SpannableString(line.text.toString())
            text.setSpan(ForegroundColorSpan(line.color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(if (line.bold) Typeface.BOLD else Typeface.NORMAL), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (line.text is Spanned) android.text.TextUtils.copySpansFrom(line.text, 0, line.text.length, Any::class.java, text, 0)
            replace(start, length, text)
        }
    }

    fun parseColor(value: String?, fallback: Int): Int =
        runCatching { Color.parseColor(value ?: "") }.getOrDefault(fallback)

    class ExactWeightSpan(private val bold: Boolean) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(tp: TextPaint) { tp.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL); tp.isFakeBoldText = false }
    }

    private fun styled(field: RecordField): CharSequence {
        val text = SpannableString(field.display())
        val split = (field.label.length + 2).coerceAtMost(text.length)
        fun apply(format: TextFormat?, start: Int, end: Int) {
            if (format == null || start >= end) return
            fun span(value: Any) = text.setSpan(value, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            format.bold?.let { span(ExactWeightSpan(it)) }
            if (format.underline) span(UnderlineSpan())
            format.color?.let { span(ForegroundColorSpan(parseColor(it, Color.WHITE))) }
            format.background?.let { span(BackgroundColorSpan(parseColor(it, Color.TRANSPARENT))) }
        }
        apply(field.appearance?.label, 0, split)
        apply(field.appearance?.value, split, text.length)
        return text
    }

    private fun buildLines(meta: CaptureMeta): List<Line> {
        val out = ArrayList<Line>()
        val textColor = parseColor(meta.style.text, TEXT_MAIN)
        val themeColor = parseColor(meta.style.theme, TEXT_ACCENT)
        if (meta.titleLine.isNotBlank()) out.add(Line(meta.titleLine.trim(), 1.12f, textColor, true))
        if (meta.stickyFields != null) meta.stickyFields.filter { it.display().isNotBlank() }.forEach {
            out.add(Line(styled(it), 0.92f, textColor, false))
        } else meta.stickyLines.filter { it.isNotBlank() }.forEach { out.add(Line(it, 0.92f, textColor, false)) }
        if (meta.categoryName.isNotBlank()) out.add(Line("【${meta.categoryName.trim()}】", 0.96f, themeColor, true))
        if (meta.measureFields != null) meta.measureFields.filter { it.display().isNotBlank() }.forEach {
            out.add(Line(styled(it), 1f, textColor, true))
        } else meta.measureLines.filter { it.isNotBlank() }.forEach { out.add(Line(it, 1f, textColor, true)) }
        if (meta.showTime) out.add(Line(formatTime(meta.timeMillis), 0.86f, textColor, false))
        if (meta.showAddress && !meta.address.isNullOrBlank()) out.add(Line(meta.address, 0.86f, textColor, false))
        if (meta.lat != null && meta.lon != null) out.add(Line(formatCoord(meta.lat, meta.lon), 0.84f, textColor, false))
        if (!meta.weather.isNullOrBlank()) out.add(Line(meta.weather.removeSuffix("（预报·MET Norway）"), 0.86f, textColor, false))
        return out
    }

    /**
     * 在 [src] 上绘制水印并返回结果。若 src 已是可变的 ARGB_8888 会就地绘制。
     *
     * @param anchor 九宫格落点，默认左下（v1.2 的老位置）
     * @param scale  字号缩放，1.0 为基准
     */
    fun render(
        src: Bitmap,
        meta: CaptureMeta,
        anchor: WatermarkAnchor = WatermarkAnchor.BOTTOM_LEFT,
        scale: Float = 1f,
        position: WatermarkPosition? = null,
        onLayout: ((RectF, WatermarkArea) -> Unit)? = null
    ): Bitmap {
        val lines = buildLines(meta)
        if (lines.isEmpty()) return src

        val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) {
            src
        } else {
            src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        }

        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val wmScale = scale.coerceIn(0.6f, 2.0f)
        // 短边做基准：横拍竖拍的字号才不会一大一小
        val shortSide = min(w, h)
        val base = shortSide / REFERENCE_WIDTH

        // 边距比字号更"硬"一点：位置移动时留白不要跟着剧烈变
        val margin = shortSide * MARGIN_RATIO
        val padH = 18f * base * wmScale
        val padV = 14f * base * wmScale
        val maxTextWidth = (shortSide * MAX_TEXT_WIDTH_RATIO - padH * 2f)
            .coerceAtLeast(48f * base)

        data class Measured(val layout: StaticLayout, val width: Float, val height: Int)

        val measured = lines.map { line ->
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = line.color
                textSize = BASE_TEXT_SIZE * base * line.sizeFactor * wmScale
                typeface = Typeface.create(
                    Typeface.SANS_SERIF,
                    if (line.bold) Typeface.BOLD else Typeface.NORMAL
                )
                isSubpixelText = true
            }
            val layout = StaticLayout.Builder
                .obtain(line.text, 0, line.text.length, tp, maxTextWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.14f)
                .setIncludePad(false)
                .build()

            var maxLineWidth = 0f
            for (i in 0 until layout.lineCount) {
                maxLineWidth = max(maxLineWidth, layout.getLineWidth(i))
            }
            Measured(layout, maxLineWidth, layout.height)
        }

        val gap = BASE_TEXT_SIZE * base * ROW_GAP_RATIO * wmScale
        val contentHeight = measured.sumOf { it.height }.toFloat() +
                gap * max(0, measured.size - 1)
        val blockWidth = measured.maxOf { it.width } + padH * 2f
        val blockHeight = contentHeight + padV * 2f

        val area = WatermarkArea(margin, margin,
            (w - blockWidth - 2 * margin).coerceAtLeast(0f),
            (h - blockHeight - 2 * margin).coerceAtLeast(0f))
        val point = position?.takeIf { it.x.isFinite() && it.y.isFinite() }?.bounded()
            ?: WatermarkPosition.fromAnchor(anchor)
        val left = area.x(point)
        val top = area.y(point)
        val rect = RectF(left, top, left + blockWidth, top + blockHeight)
        onLayout?.invoke(rect, area)
        val corner = 8f * base * wmScale

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = parseColor(meta.style.background, Color.BLACK)
            alpha = meta.style.alpha() }
        canvas.drawRoundRect(rect, corner, corner, bgPaint)

        var cursorY = top + padV
        val textLeft = left + padH
        measured.forEach { m ->
            canvas.save()
            canvas.translate(textLeft, cursorY)
            m.layout.draw(canvas)
            canvas.restore()
            cursorY += m.height + gap
        }

        return out
    }

    fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))

    fun formatTimeDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    fun formatCoord(lat: Double, lon: Double): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return String.format(
            Locale.US, "%s%.6f° %s%.6f°",
            ns, abs(lat), ew, abs(lon)
        )
    }
}
