package com.measurecam.widget

import android.content.Context
import android.graphics.Color
import android.content.res.ColorStateList
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.measurecam.R
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.measurecam.data.*
import com.measurecam.render.WatermarkRenderer

/** 模板设置和单张照片编辑共享同一套控件，确认后才提交草稿。 */
object StyleDialogs {
    private fun dp(c: Context, value: Int) = (value * c.resources.displayMetrics.density).toInt()
    private fun rounded(c: Context, color: Int, border: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(c, 16).toFloat(); setColor(color)
        if (border) setStroke(dp(c, 1), ContextCompat.getColor(c, R.color.divider))
    }
    private fun column(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 12))
    }
    private fun label(box: LinearLayout, value: String) = TextView(box.context).apply {
        text = value; textSize = 13f
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        setPadding(0, dp(context, 8), 0, dp(context, 8)); box.addView(this)
    }
    private fun section(box: LinearLayout, title: String): LinearLayout {
        val c = box.context
        label(box, title).apply { textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(ContextCompat.getColor(c, R.color.brand)) }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c, Color.WHITE, true)
            setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6))
            box.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun button(box: LinearLayout, value: String, action: () -> Unit) = MaterialButton(box.context, null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = value; textSize = 13f; isAllCaps = false; cornerRadius = dp(context, 12)
        minHeight = dp(context, 48); setOnClickListener { action() }; box.addView(this)
    }
    private fun colorRow(box: LinearLayout, title: String, initial: String?, allowInherit: Boolean, done: (String?) -> Unit) {
        val c = box.context
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(c, 56) }
        row.addView(TextView(c).apply { text = title; textSize = 14f; setTextColor(ContextCompat.getColor(c, R.color.text_primary)) }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(c).apply { textSize = 11f; setTextColor(ContextCompat.getColor(c, R.color.text_secondary)); setPadding(0, 0, dp(c, 10), 0) }
        row.addView(value)
        val swatch = View(c); row.addView(swatch, LinearLayout.LayoutParams(dp(c, 26), dp(c, 26)))
        var selected = initial
        fun display() { value.text = selected ?: "默认"; swatch.background = rounded(c, WatermarkRenderer.parseColor(selected, Color.TRANSPARENT), true) }
        display()
        row.contentDescription = title
        row.isFocusable = true
        row.setOnClickListener { color(c, title, selected, allowInherit) { selected = it; display(); done(it) } }
        box.addView(row)
    }
    private fun color(c: Context, title: String, initial: String?, allowInherit: Boolean, done: (String?) -> Unit) {
        val colors = listOf("#FFFFFF", "#000000", "#1B6B4A", "#8FE3B4", "#1565C0", "#D32F2F", "#FF9800", "#FFEB3B", "#808080")
        val names = listOf("白色", "黑色", "深绿", "浅绿", "蓝色", "红色", "橙色", "黄色", "灰色")
        val box = column(c)
        val dialog = MaterialAlertDialogBuilder(c).setTitle(title).setView(box).setNegativeButton("取消", null).create()
        colors.chunked(3).forEachIndexed { rowIndex, rowColors ->
            val row = LinearLayout(c)
            rowColors.forEachIndexed { columnIndex, hex ->
                val i = rowIndex * 3 + columnIndex
                row.addView(MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = names[i]; textSize = 12f; minWidth = 0; minimumWidth = 0
                    icon = rounded(c, Color.parseColor(hex), true); iconSize = dp(c, 18); iconTint = null
                    cornerRadius = dp(c, 12)
                    setPadding(dp(c, 8), 0, dp(c, 8), 0)
                    if (initial.equals(hex, true)) backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(c, R.color.brand_soft))
                    setOnClickListener { done(hex); dialog.dismiss() }
                }, LinearLayout.LayoutParams(0, dp(c, 56), 1f).apply { marginEnd = dp(c, 4) })
            }; box.addView(row)
        }
        button(box, "自定义颜色") {
            val input = EditText(c).apply { setText(initial ?: "#FFFFFF"); hint = "#RRGGBB"; inputType = InputType.TYPE_CLASS_TEXT; selectAll() }
            val customBox = column(c).apply { addView(input) }
            val custom = MaterialAlertDialogBuilder(c).setTitle("自定义颜色").setMessage("输入 HEX 色值，例如 #FF8800")
                .setView(customBox).setPositiveButton("确定", null).setNegativeButton("取消", null).create()
            custom.setOnShowListener { custom.getButton(-1).setOnClickListener {
                val value = input.text.toString().trim().uppercase()
                if (!Regex("#[0-9A-F]{6}").matches(value)) input.error = "请输入 # 和 6 位十六进制颜色"
                else { done(value); custom.dismiss(); dialog.dismiss() }
            } }; custom.show()
        }
        if (allowInherit) button(box, "恢复默认 / 无底色") { done(null); dialog.dismiss() }
        dialog.show()
    }
    private fun slider(box: LinearLayout, title: String, maxValue: Int, initial: Int, offset: Int = 0, change: (Int) -> Unit) {
        val c = box.context
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(c).apply { text = title; textSize = 14f; setTextColor(ContextCompat.getColor(c, R.color.text_primary)) }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(c).apply { text = "${initial + offset}%"; textSize = 14f; setTextColor(ContextCompat.getColor(c, R.color.brand)); setTypeface(typeface, Typeface.BOLD) }
        row.addView(value); box.addView(row, LinearLayout.LayoutParams(-1, dp(c, 36)))
        box.addView(SeekBar(c).apply {
            max = maxValue; progress = initial; contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(v: SeekBar?, p: Int, user: Boolean) { value.text = "${p + offset}%"; if (user) change(p) }
                override fun onStartTrackingTouch(v: SeekBar?) = Unit
                override fun onStopTrackingTouch(v: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(c, 40)))
    }
    private fun preview(box: LinearLayout): TextView {
        label(box, "样式放大预览")
        val frame = FrameLayout(box.context).apply {
            background = rounded(context, Color.rgb(107, 124, 114))
            val inset = (12 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        box.addView(frame, LinearLayout.LayoutParams(-1, -2))
        return TextView(box.context).apply {
            contentDescription = "水印样式实时预览"
            typeface = Typeface.SANS_SERIF
            val inset = (8 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
            frame.addView(this, FrameLayout.LayoutParams(-2, -2))
        }
    }
    private fun showPreview(view: TextView, meta: CaptureMeta, scale: Float) {
        view.text = WatermarkRenderer.previewText(meta)
        view.textSize = 16f * scale
        view.background = GradientDrawable().apply {
            cornerRadius = 6 * view.resources.displayMetrics.density
            setColor(WatermarkRenderer.parseColor(meta.style.background, Color.BLACK))
            alpha = meta.style.alpha()
        }
    }
    fun watermark(c: Context, initial: WatermarkStyle?, initialScale: Float, initialWeather: Boolean, allowWeather: Boolean = true,
        done: (WatermarkStyle, Float, Boolean) -> Unit) {
        var style = initial ?: WatermarkStyle(); var scale = initialScale; var weather = initialWeather
        val box = column(c); val image = preview(box)
        fun redraw() {
            showPreview(image, CaptureMeta(titleLine = "现场测量", categoryName = "基平台",
                measureLines = listOf("深度: 850 mm", "平整度: 3.1 / 3.2 mm"), showTime = false, style = style,
                weather = if (weather) "天气示例：晴 24℃" else null), scale)
        }
        label(box, if (allowWeather) "当前模板在取景页可直接拖动水印。样式与位置用于之后拍摄的照片。" else "仅修改这张照片的样式；天气沿用拍摄时的快照")
        val appearanceBox = section(box, "颜色")
        colorRow(appearanceBox, "主题颜色", style.theme, false) { style = style.copy(theme = it!!); redraw() }
        colorRow(appearanceBox, "水印背景", style.background, false) { style = style.copy(background = it!!); redraw() }
        colorRow(appearanceBox, "默认文字", style.text, false) { style = style.copy(text = it!!); redraw() }
        val sizeBox = section(box, "透明度与大小")
        slider(sizeBox, "背景透明度", 100, 100 - (style.opacity * 100).toInt()) { style = style.copy(opacity = 1f - it / 100f); redraw() }
        slider(sizeBox, "水印大小", 110, ((scale - 0.7f) * 100).toInt(), 70) { scale = 0.7f + it / 100f; redraw() }
        if (allowWeather) {
            val weatherBox = section(box, "系统信息")
            weatherBox.addView(MaterialSwitch(c).apply {
                text = "显示天气"; textSize = 14f; minHeight = dp(c, 56); isChecked = weather
                setOnCheckedChangeListener { _, value -> weather = value; redraw() }
            }, LinearLayout.LayoutParams(-1, -2))
            label(weatherBox, "按定位获取天气与温度，随拍照保存。需要定位和网络；无有效缓存时显示未获取。")
            label(box, "天气数据来自 MET Norway 区域预报，非现场实测。CC BY 4.0；启用时发送约 1 公里精度的位置。")
            button(box, "天气数据来源与许可  ↗") { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://api.met.no/doc/License"))) }
        }
        redraw()
        MaterialAlertDialogBuilder(c).setTitle(if (allowWeather) "水印设置" else "照片水印样式").setView(ScrollView(c).apply { addView(box) })
            .setPositiveButton("应用") { _, _ -> done(style, scale, weather) }.setNegativeButton("取消", null).show()
    }
    fun field(c: Context, name: String, initial: FieldAppearance?, done: (FieldAppearance?) -> Unit) {
        var appearance = initial ?: FieldAppearance()
        val box = column(c); val image = preview(box)
        fun redraw() {
            showPreview(image, CaptureMeta(measureFields = listOf(
                RecordField(name, values = listOf("850 / 示例文字"), appearance = appearance)), showTime = false), 1f)
        }
        listOf(false, true).forEach { value ->
            fun current() = (if (value) appearance.value else appearance.label) ?: TextFormat()
            fun update(f: TextFormat) { appearance = if (value) appearance.copy(value = f) else appearance.copy(label = f); redraw() }
            val formatBox = section(box, if (value) "数值 / 内容" else "标签文字")
            formatBox.addView(Spinner(c).apply {
                minimumHeight = dp(c, 48)
                adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, listOf("默认粗细", "普通", "加粗"))
                setSelection(when (current().bold) { null -> 0; false -> 1; true -> 2 })
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) { update(current().copy(bold = when(i) { 1 -> false; 2 -> true; else -> null })) }
                    override fun onNothingSelected(p: AdapterView<*>?) = Unit
                }
            })
            formatBox.addView(MaterialSwitch(c).apply { text = "下划线"; textSize = 14f; minHeight = dp(c, 48); isChecked = current().underline
                setOnCheckedChangeListener { _, b -> update(current().copy(underline = b)) }
            })
            colorRow(formatBox, "文字颜色", current().color, true) { update(current().copy(color = it)) }
            colorRow(formatBox, "文字底色", current().background, true) { update(current().copy(background = it)) }
        }
        redraw()
        MaterialAlertDialogBuilder(c).setTitle("$name · 单项格式").setView(ScrollView(c).apply { addView(box) })
            .setPositiveButton("应用") { _, _ -> done(appearance) }.setNeutralButton("恢复默认") { _, _ -> done(null) }
            .setNegativeButton("取消", null).show()
    }
}
