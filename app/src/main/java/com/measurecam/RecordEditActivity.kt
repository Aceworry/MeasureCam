package com.measurecam

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.measurecam.data.CaptureMeta
import com.measurecam.data.CaptureRecord
import com.measurecam.data.RecordField
import com.measurecam.data.RecordRepository
import android.graphics.RectF
import com.measurecam.widget.PhotoWatermarkDrag
import com.measurecam.widget.ReadingDialogs
import com.measurecam.util.ReadingChecks
import com.measurecam.data.WatermarkArea
import com.measurecam.data.WatermarkPosition
import com.measurecam.data.WatermarkAnchor
import com.measurecam.data.FieldType
import com.measurecam.data.WatermarkStyle
import com.measurecam.widget.StyleDialogs
import com.measurecam.databinding.ActivityRecordEditBinding
import com.measurecam.databinding.ItemEditFieldBinding
import com.measurecam.render.WatermarkRenderer
import com.measurecam.storage.OriginalStore
import com.measurecam.storage.PhotoSaver
import com.measurecam.util.Chainage
import com.measurecam.util.ImageLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 改一张已经拍好的照片。
 *
 * 前提是这张照片留了无水印原图（v1.3 起都留）：水印是烧进像素的，
 * 改数值只能从原图重画，否则新水印会叠在旧水印上，成废片。
 *
 * 改完覆盖相册里同名的那张，文件名不变，记录不用重新关联。
 */
class RecordEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "record_id"

        /** 预览底图宽度：够看清布局，又不至于每次改数都卡一下 */
        private const val PREVIEW_WIDTH = 720
        private const val PREVIEW_DEBOUNCE_MS = 240L
    }

    private lateinit var binding: ActivityRecordEditBinding
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var record: CaptureRecord? = null

    private val fields = mutableListOf<RecordField>()
    private val stickies = mutableListOf<RecordField>()

    private var customPosition: WatermarkPosition? = null
    private lateinit var drag: PhotoWatermarkDrag
    private val originalValues = java.util.IdentityHashMap<RecordField, List<String>>()
    private var anchor = WatermarkAnchor.BOTTOM_LEFT
    private var wmScale = 1f
    private var wmStyle = WatermarkStyle()

    /** 缩小版原图，常驻内存 */
    private var previewBase: Bitmap? = null

    /** 当前显示的那张预览，换新图时回收旧的 */
    private var shownPreview: Bitmap? = null

    /** 渲染是异步的，用代号丢掉过期结果，避免快速输入时旧图盖新图 */
    private var previewGeneration = 0

    private var saving = false

    private val previewTask = Runnable { renderPreview() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        drag = PhotoWatermarkDrag(binding.ivPreview) {
            customPosition = it
            updateAnchorLabel()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.btnSave.isEnabled = false
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        val id = intent.getStringExtra(EXTRA_RECORD_ID)
        if (id.isNullOrBlank()) {
            finish()
            return
        }

        io.execute {
            val r = runCatching { RecordRepository.find(this, id) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r == null) {
                    toast(getString(R.string.edit_record_missing))
                    finish()
                    return@runOnUiThread
                }
                bind(r)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(previewTask)
        previewGeneration++
        drag.clear()
        binding.ivPreview.setImageDrawable(null)
        shownPreview = null
        // 原图仍可能被后台渲染使用，等队列里的渲染结束后再释放。
        io.execute { previewBase?.recycle(); previewBase = null }
        io.shutdown()
    }

    override fun onSupportNavigateUp(): Boolean { confirmLeave(); return true }

    private fun confirmLeave() {
        if (saving) { toast(getString(R.string.edit_saving_wait)); return }
        val r = record
        val changed = r != null && (fields != r.fieldList() || stickies != r.stickyList() ||
            wmStyle != (r.watermarkStyle ?: WatermarkStyle()) || anchor != r.anchor() || customPosition != r.watermarkPosition || kotlin.math.abs(wmScale - (r.watermarkScale ?: 1f)) > 0.005f)
        if (!changed) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_unsaved_title)
            .setMessage(R.string.edit_unsaved_message)
            .setPositiveButton(R.string.edit_save) { _, _ -> save() }
            .setNeutralButton(R.string.edit_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.edit_keep_editing, null)
            .show()
    }

    // ---------------- 初始化 ----------------

    private fun bind(r: CaptureRecord) {
        record = r
        fields.clear()
        fields.addAll(r.fieldList().map { it.copy(values = it.valueList().toList()) })
        originalValues.clear()
        fields.forEach { originalValues[it] = it.valueList().toList() }
        stickies.clear()
        stickies.addAll(r.stickyList().map { it.copy(values = it.valueList().toList()) })

        wmStyle = r.watermarkStyle ?: WatermarkStyle()
        anchor = r.anchor()
        customPosition = r.watermarkPosition
        wmScale = (r.watermarkScale ?: 1f).coerceIn(0.7f, 1.8f)

        binding.tvCategory.text = r.categoryName.ifBlank { getString(R.string.edit_no_category) }
        binding.tvCategory.visibility =
            if (r.categoryName.isBlank()) View.GONE else View.VISIBLE

        binding.anchorPicker.setAnchor(anchor)
        updateAnchorLabel()
        binding.anchorPicker.onAnchorChanged = { a ->
            anchor = a
            customPosition = null
            updateAnchorLabel()
            schedulePreview()
        }

        binding.seekScale.progress = ((wmScale - 0.7f) * 100f).toInt().coerceIn(0, 110)
        updateScaleLabel()
        binding.seekScale.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    wmScale = 0.7f + p / 100f
                    updateScaleLabel()
                    if (fromUser) schedulePreview()
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
            }
        )

        binding.btnWatermarkStyle.setOnClickListener {
            StyleDialogs.watermark(this, wmStyle, wmScale, false, allowWeather = false) { style, scale, _ ->
                wmStyle = style; wmScale = scale
                binding.seekScale.progress = ((wmScale - 0.7f) * 100).toInt()
                updateScaleLabel(); schedulePreview()
            }
        }
        binding.btnAddField.setOnClickListener { addField() }
        binding.btnCancel.setOnClickListener { confirmLeave() }
        binding.btnSave.setOnClickListener { save() }

        rebuildFieldRows()
        rebuildStickyRows()

        if (!r.editable()) {
            // 没有原图：能看能删，但改不了数值，改了也画不上去
            binding.tvHint.text = getString(R.string.edit_no_original_hint)
            binding.btnAddField.visibility = View.GONE
            binding.btnSave.isEnabled = false
            binding.btnSave.alpha = 0.4f
            binding.seekScale.isEnabled = false
            loadFallbackPreview()
            return
        }

        binding.tvHint.text = getString(R.string.edit_hint)
        loadPreviewBase()
    }

    private fun updateAnchorLabel() {
        binding.anchorPicker.hasCustomPosition = customPosition != null
        val names = resources.getStringArray(R.array.anchor_names)
        binding.tvAnchorValue.text = customPosition?.let {
            String.format(Locale.getDefault(), "自定义 · %.0f%% / %.0f%%", it.x * 100, it.y * 100)
        } ?: names.getOrElse(anchor.ordinal) { "" }
    }

    private fun updateScaleLabel() {
        binding.tvScaleValue.text = String.format(Locale.US, "%.1f×", wmScale)
    }

    private fun loadPreviewBase() {
        val r = record ?: return
        io.execute {
            val file = OriginalStore.readable(this, r.originalName)
            val src = file?.let { runCatching { ImageLoader.load(it, PREVIEW_WIDTH) }.getOrNull() }
            if (src == null) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.tvHint.text = getString(R.string.edit_original_lost)
                    binding.btnSave.isEnabled = false
                    loadFallbackPreview()
                }
                return@execute
            }
            previewBase = src
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.btnSave.isEnabled = true
                schedulePreview(immediate = true)
            }
        }
    }

    /** 没有可编辑原图时，退而求其次把相册里那张显示出来，至少能对照着看 */
    private fun loadFallbackPreview() {
        val r = record ?: return
        io.execute {
            val uri = PhotoSaver.findUri(this, r.photoName)
            val bmp = uri?.let { runCatching { ImageLoader.load(this, it, PREVIEW_WIDTH) }.getOrNull() }
            runOnUiThread {
                if (isFinishing || isDestroyed) { bmp?.recycle(); return@runOnUiThread }
                shownPreview = bmp
                if (bmp != null) binding.ivPreview.setImageBitmap(bmp)
                else binding.ivPreview.setImageResource(R.drawable.bg_watermark)
            }
        }
    }

    // ---------------- 字段编辑 ----------------

    private fun rebuildFieldRows() {
        val box = binding.fieldContainer
        box.removeAllViews()

        if (fields.isEmpty()) {
            val tv = android.widget.TextView(this).apply {
                text = getString(R.string.edit_no_fields)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(6), 0, dp(6))
            }
            box.addView(tv)
            return
        }

        fields.forEachIndexed { index, f ->
            box.addView(buildRow(f, onRemove = {
                fields.removeAt(index)
                rebuildFieldRows()
                schedulePreview()
            }, onValueChanged = { schedulePreview() }))
        }
    }

    private fun rebuildStickyRows() {
        val box = binding.stickyContainer
        box.removeAllViews()

        if (stickies.isEmpty()) {
            val tv = android.widget.TextView(this).apply {
                text = getString(R.string.edit_no_sticky)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(6), 0, dp(6))
            }
            box.addView(tv)
            return
        }

        stickies.forEachIndexed { index, f ->
            box.addView(buildRow(f, textOnly = true, onRemove = {
                stickies.removeAt(index)
                rebuildStickyRows()
                schedulePreview()
            }, onValueChanged = { schedulePreview() }))
        }
    }

    /** 一行 = 标签 + N 个值输入框（平整度这类一个标签两个读数） + 单位 + 删除 */
    private fun buildRow(
        field: RecordField,
        textOnly: Boolean = false,
        onRemove: () -> Unit,
        onValueChanged: () -> Unit
    ): View {
        val row = ItemEditFieldBinding.inflate(layoutInflater, binding.fieldContainer, false)
        row.tvLabel.text = field.label
        row.btnFormat.setOnClickListener {
            StyleDialogs.field(this, field.label, field.appearance) { field.appearance = it; onValueChanged() }
        }
        row.tvUnit.text = field.unit
        row.btnRemove.setOnClickListener { onRemove() }

        val values = field.valueList().ifEmpty { listOf("") }
        row.valueContainer.removeAllViews()

        values.forEachIndexed { vi, initial ->
            val et = EditText(this).apply {
                setText(initial)
                isSingleLine = true
                textSize = 15f
                minHeight = dp(48)
                contentDescription = if (values.size > 1) "${field.label} ${vi + 1}" else field.label
                setPadding(dp(7), dp(6), dp(7), dp(6))
                background = ContextCompat.getDrawable(context, R.drawable.bg_value_box)
                hint = if (values.size > 1) getString(R.string.edit_value_index, vi + 1) else null
                inputType = if (textOnly) InputType.TYPE_CLASS_TEXT else inputTypeFor(field)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val list = field.valueList().toMutableList()
                    while (list.size <= vi) list.add("")
                    list[vi] = s?.toString().orEmpty()
                    field.values = list
                    et.error = readingWarning(field, vi, list[vi])
                    onValueChanged()
                }
            })
            // 点回来就全选。
            // 现场改一个数基本都是整值覆盖（850 → 900），光标落在末尾的话直接输入
            // 会接成 "850900"——速录面板踩过同样的坑。
            //
            // 不能用 setOnFocusChangeListener / setSelectAllOnFocus / setOnClickListener：
            // 前两个在 ACTION_DOWN 时触发，紧接着的 ACTION_UP 会把光标重新定位到手指
            // 位置，全选当场取消；EditText 的触摸由内部 Editor 消费，onClick 不会被调用。
            // 只能自己监听，在 ACTION_UP 之后补一次。
            et.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    v.post {
                        if (et.selectionStart == et.selectionEnd && et.text.isNotEmpty()) {
                            et.setSelection(0, et.text.length)
                        }
                    }
                }
                false   // 交回给 EditText，光标、键盘、长按选择都还要它自己处理
            }
            row.valueContainer.addView(
                et,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (vi > 0) marginStart = dp(4)
                }
            )
        }
        return row.root
    }

    private fun readingWarning(field: RecordField, slot: Int, raw: String): String? {
        if (field.inputFieldType() == FieldType.TEXT || field.isChainage()) return null
        val before = originalValues[field]?.getOrNull(slot)
        if (before == raw) return null
        return ReadingChecks.warning(raw, before, field.warningRange)
    }

    private fun inputTypeFor(field: RecordField): Int {
        return when (field.inputFieldType()) {
            FieldType.TEXT -> InputType.TYPE_CLASS_TEXT
            FieldType.INTEGER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            FieldType.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
    }

    private fun addField() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, dp(8), p, 0)
        }

        fun input(hintRes: Int, textType: Int = InputType.TYPE_CLASS_TEXT): EditText =
            EditText(this).apply {
                hint = getString(hintRes)
                inputType = textType
                isSingleLine = true
            }

        val etLabel = input(R.string.edit_field_label_hint)
        val etValue = input(R.string.edit_field_value_hint)
        val etUnit = input(R.string.edit_field_unit_hint)

        box.addView(etLabel)
        box.addView(etValue)
        box.addView(etUnit)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_add_field)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = etLabel.text.toString().trim()
                if (label.isEmpty()) {
                    etLabel.error = getString(R.string.edit_field_label_required)
                    return@setOnClickListener
                }
                fields.add(
                    RecordField(
                        label = label,
                        unit = etUnit.text.toString().trim(),
                        values = listOf(etValue.text.toString().trim())
                    )
                )
                rebuildFieldRows()
                schedulePreview()
                dialog.dismiss()
            }
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
            etLabel.requestFocus()
            etLabel.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etLabel, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }
        dialog.show()
    }

    // ---------------- 预览 ----------------

    private fun schedulePreview(immediate: Boolean = false) {
        if (isFinishing || isDestroyed || saving) return
        previewGeneration++
        main.removeCallbacks(previewTask)
        if (immediate) main.post(previewTask)
        else main.postDelayed(previewTask, PREVIEW_DEBOUNCE_MS)
    }

    private fun renderPreview() {
        val base = previewBase ?: return
        val gen = previewGeneration
        val s = wmScale
        val meta = buildMeta()

        io.execute {
            val transparent = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            var travel: WatermarkArea? = null
            var rect: RectF? = null
            WatermarkRenderer.render(transparent, meta, WatermarkAnchor.TOP_LEFT, s, onLayout = { bounds, area ->
                rect = bounds; travel = area
            })
            val layer = rect?.let {
                val left = it.left.toInt().coerceIn(0, base.width - 1)
                val top = it.top.toInt().coerceIn(0, base.height - 1)
                val right = kotlin.math.ceil(it.right).toInt().coerceIn(left + 1, base.width)
                val bottom = kotlin.math.ceil(it.bottom).toInt().coerceIn(top + 1, base.height)
                Bitmap.createBitmap(transparent, left, top, right - left, bottom - top)
            }
            if (layer !== transparent) transparent.recycle()
            runOnUiThread {
                if (isFinishing || isDestroyed || gen != previewGeneration) {
                    // 期间又改了输入，这张已经过期
                    layer?.recycle()
                    return@runOnUiThread
                }
                drag.show(base, layer, travel, customPosition ?: WatermarkPosition.fromAnchor(anchor))
            }
        }
    }

    private fun buildMeta(): CaptureMeta {
        val r = record ?: return CaptureMeta()
        return CaptureMeta(
            titleLine = r.titleLine,
            stickyLines = stickies.map { it.display() }.filter { it.isNotBlank() },
            categoryName = r.categoryName,
            measureLines = fields.map { it.display() }.filter { it.isNotBlank() },
            timeMillis = r.timeMillis,
            lat = r.lat,
            lon = r.lon,
            address = r.address,
            showTime = r.showTime ?: true,
            showAddress = r.showAddress ?: true,
            style = wmStyle, stickyFields = stickies.map { it.copy(values = it.valueList().toList()) }, measureFields = fields.map { it.copy(values = it.valueList().toList()) }, weather = r.weather
        )
    }

    // ---------------- 保存 ----------------

    private fun save() {
        val r = record ?: return
        if (saving) return
        if (!r.editable()) {
            toast(getString(R.string.edit_no_original_hint))
            return
        }

        val warnings = fields.flatMap { field ->
            field.valueList().mapIndexedNotNull { i, value ->
                readingWarning(field, i, value)?.let { field.label + "（" + (i + 1) + "） " + value + " · " + it }
            }
        }
        ReadingDialogs.confirm(this, warnings) { saveConfirmed(r) }
    }

    private fun saveConfirmed(r: CaptureRecord) {
        if (saving) return
        saving = true
        setFormEnabled(false)
        main.removeCallbacks(previewTask)
        previewGeneration++
        binding.btnSave.isEnabled = false
        binding.btnSave.text = getString(R.string.saving)

        // 先把界面上的值收干净，再回写桩号
        val newFields = fields.map { it.copy(values = it.valueList().map(String::trim)) }
        val newStickies = stickies.map { it.copy(values = it.valueList().map(String::trim)) }

        val chainField = newFields.firstOrNull { it.isChainage() }
        val chainText = chainField?.valueList()?.firstOrNull()?.trim().orEmpty()

        val updated = r.copy(
            sticky = newStickies,
            fields = newFields,
            chainage = if (chainField != null || r.fieldList().any { it.isChainage() }) chainText else r.chainage,
            chainageMeters = if (chainField != null || r.fieldList().any { it.isChainage() }) Chainage.parse(chainText) else r.chainageMeters,
            watermarkAnchor = anchor.key,
            watermarkPosition = customPosition,
            watermarkScale = wmScale,
            watermarkStyle = wmStyle
        )

        val meta = CaptureMeta(
            titleLine = updated.titleLine,
            stickyLines = newStickies.map { it.display() }.filter { it.isNotBlank() },
            categoryName = updated.categoryName,
            measureLines = newFields.map { it.display() }.filter { it.isNotBlank() },
            timeMillis = updated.timeMillis,
            lat = updated.lat,
            lon = updated.lon,
            address = updated.address,
            showTime = updated.showTime ?: true,
            showAddress = updated.showAddress ?: true,
            style = wmStyle, stickyFields = newStickies, measureFields = newFields, weather = updated.weather
        )

        io.execute {
            val srcFile = OriginalStore.readable(this, r.originalName)
            val src = srcFile?.let { runCatching { ImageLoader.load(it) }.getOrNull() }
            if (src == null) {
                finishWith(getString(R.string.edit_original_lost))
                return@execute
            }

            val rendered = runCatching { WatermarkRenderer.render(src, meta, updated.anchor(), updated.watermarkScale ?: 1f, updated.watermarkPosition) }.getOrNull()
            if (rendered == null) {
                src.recycle()
                finishWith(getString(R.string.edit_write_failed))
                return@execute
            }
            val tmp = File(cacheDir, "edit_${System.currentTimeMillis()}.jpg")
            val compressed = runCatching {
                tmp.outputStream().use { out ->
                    rendered.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }.getOrDefault(false)

            if (compressed) writeExif(tmp, meta)

            val ok = compressed && PhotoSaver.overwrite(this, tmp, r.photoName)

            runCatching { tmp.delete() }
            if (rendered !== src) runCatching { rendered.recycle() }
            runCatching { src.recycle() }

            if (!ok) {
                finishWith(getString(R.string.edit_write_failed))
                return@execute
            }

            if (!runCatching { RecordRepository.update(this, updated) }.getOrDefault(false)) {
                finishWith(getString(R.string.edit_record_write_failed))
                return@execute
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(getString(R.string.edit_saved))
                finish()
            }
        }
    }

    private fun finishWith(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            saving = false
            setFormEnabled(true)
            binding.btnSave.isEnabled = true
            binding.btnSave.text = getString(R.string.edit_save)
            toast(message)
        }
    }

    private fun writeExif(file: File, meta: CaptureMeta) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(meta.timeMillis))
            )
            if (meta.lat != null && meta.lon != null) {
                exif.setLatLong(meta.lat, meta.lon)
            }
            exif.saveAttributes()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setFormEnabled(enabled: Boolean) {
        fun apply(view: View) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (i in 0 until view.childCount) apply(view.getChildAt(i))
        }
        apply(binding.formScroll)
        binding.btnCancel.isEnabled = enabled
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
