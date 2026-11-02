package com.measurecam.capture

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.measurecam.util.ReadingChecks
import com.measurecam.util.Chainage
import com.measurecam.widget.ReadingDialogs
import com.measurecam.R
import com.measurecam.data.CaptureTemplate
import com.measurecam.data.FieldDef
import com.measurecam.data.FieldType
import com.measurecam.data.MeasureCategory
import com.measurecam.databinding.DialogCaptureFormBinding
import com.measurecam.databinding.ItemMeasureFieldBinding

/**
 * 拍完立刻弹出的速录面板。
 *
 * 速度是这里唯一的设计目标：
 *  1. 弹出即聚焦第一格，键盘自动起，不用再点一下
 *  2. 每格 setSelectAllOnFocus，直接输新值就把旧值盖掉
 *  3. 回车跳下一格，末格回车直接保存
 *  4. 快速填入上次数值，连续测点只需改变化的那一格
 *  5. 面板内可直接换分类，已填的值不会丢
 *  6. 桩号按 GPS 位移自动预填，通常直接回车
 */
class CaptureFormDialog(
    context: Context,
    private val template: CaptureTemplate,
    initialCategoryId: String?,
    private val lastValues: Map<String, String>,
    private val chainagePrefill: String,
    private val chainageNote: String,
    private val lastChainage: Double?,
    private val onConfirmed: (categoryId: String, values: Map<String, String>) -> Unit,
    private val onCancelled: () -> Unit = {}
) : Dialog(context) {

    private val binding = DialogCaptureFormBinding.inflate(LayoutInflater.from(context))
    private var confirmed = false

    private val categories: List<MeasureCategory> = template.activeCategories()
    private var category: MeasureCategory =
        categories.firstOrNull { it.id == initialCategoryId } ?: categories.firstOrNull()
        ?: MeasureCategory(name = "默认分类")

    /** 当前分类里的输入框，按屏幕顺序展开（多值字段会有多格） */
    private val editors = ArrayList<EditText>()

    /** 每个字段填过的值，key=字段 id，值已按 "/" 合成。换分类靠它保住已填内容 */
    private val typed = LinkedHashMap<String, String>()

    /** 上一次回车跳格的时间，用于吃掉重复触发的第二次动作 */
    private var lastAdvanceAt = 0L

    init {
        setContentView(binding.root)
        setCancelable(true)
        setCanceledOnTouchOutside(false)
        setOnDismissListener { if (!confirmed) onCancelled() }
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        binding.scrollFields.maxHeight =
            (context.resources.displayMetrics.heightPixels * 0.50f).toInt()

        binding.btnSave.setOnClickListener { confirm() }
        binding.btnCancel.setOnClickListener {
            confirmed = false
            dismiss()
        }
        binding.btnReuse.setOnClickListener { applyLastValues() }

        buildCategoryChips()
        rebuildFields()
    }

    // ---------------- 分类切换 ----------------

    private fun buildCategoryChips() {
        if (categories.size <= 1) {
            binding.scrollCategory.visibility = ViewGroup.GONE
            return
        }
        binding.scrollCategory.visibility = ViewGroup.VISIBLE
        binding.categoryRow.removeAllViews()

        categories.forEach { c ->
            val chip = TextView(context).apply {
                text = c.name
                setTextColor(ContextCompat.getColor(context, R.color.white))
                textSize = 13f
                val h = dp(12)
                val v = dp(6)
                setPadding(h, v, h, v)
                background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
                isSelected = c.id == category.id
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            chip.setOnClickListener {
                if (c.id == category.id) return@setOnClickListener
                collectTyped()
                category = c
                buildCategoryChips()
                rebuildFields()
            }
            binding.categoryRow.addView(chip)
        }

        // 当前分类可能排在后面（排水沟/截流沟），横条要把它滑进可视区
        val active = binding.categoryRow.getChildAt(
            categories.indexOfFirst { it.id == category.id }.coerceAtLeast(0)
        )
        active?.post {
            binding.scrollCategory.smoothScrollTo((active.left - dp(12)).coerceAtLeast(0), 0)
        }
    }

    // ---------------- 字段渲染 ----------------

    /** 先采集当前屏上的值，再按新分类重建 */
    private fun rebuildFields() {
        binding.fieldsContainer.removeAllViews()
        editors.clear()

        binding.tvTitle.text = context.getString(R.string.fill_title_cat, category.name)
        // 分类没有桩号字段时（比如钢丝网格），桩号推算提示就没意义了
        binding.tvHint.text = if (category.chainageField() == null) {
            context.getString(R.string.fill_hint)
        } else {
            chainageNote.ifBlank { context.getString(R.string.fill_hint) }
        }

        val fields = category.activeFields()
        val inflater = LayoutInflater.from(context)
        val flat = ArrayList<Pair<FieldDef, EditText>>()

        fields.forEach { field ->
            val row = ItemMeasureFieldBinding.inflate(inflater, binding.fieldsContainer, false)
            row.tvLabel.text = field.shortLabel()
            row.tvUnit.text = field.unit

            val raw = typed[field.id]
                ?: (if (field.isChainage() && chainagePrefill.isNotBlank()) {
                    chainagePrefill
                } else {
                    field.defaultValue
                })
            val values = field.splitValue(raw)

            val slots = field.slots()
            for (i in 0 until slots) {
                val et = makeEditor(field, i, slots)
                et.doAfterTextChanged { et.error = warning(field, i, it.toString()) }
                et.setText(values.getOrElse(i) { "" })
                if (field.isChainage()) et.setSelection(0, et.text.length)
                row.valueRow.addView(et)
                flat.add(field to et)
            }
            binding.fieldsContainer.addView(row.root)
        }

        // 焦点链要在所有输入框都建好之后才能串起来
        flat.forEachIndexed { index, (_, et) ->
            et.imeOptions = if (index == flat.lastIndex) {
                EditorInfo.IME_ACTION_DONE
            } else {
                EditorInfo.IME_ACTION_NEXT
            }
            et.setOnEditorActionListener { _, actionId, _ ->
                when (actionId) {
                    EditorInfo.IME_ACTION_DONE -> { advanceOnce { confirm() }; true }
                    EditorInfo.IME_ACTION_NEXT -> { advanceOnce { focusNext(index) }; true }
                    else -> false
                }
            }
            // 物理键盘回车必须从 key 事件这一层吃掉。
            // 只靠 setOnEditorActionListener 不行：TextView 会先自己做一次"焦点前进"，
            // 再抛一次 editor action，结果一次回车跳两格。
            et.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        advanceOnce {
                            if (index == flat.lastIndex) confirm() else focusNext(index)
                        }
                    }
                    true
                } else {
                    false
                }
            }
            editors.add(et)
        }

        // 面板内切换分类时，字段是重建的，焦点必须重新给到第一格。
        // 否则键盘还在但按键没有落点，会出现"打字没反应"。
        if (isShowing) {
            val first = editors.firstOrNull()
            first?.post {
                first.requestFocus()
                first.setSelection(0, first.text.length)
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(first, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun makeEditor(field: FieldDef, index: Int, slots: Int): EditText =
        EditText(context).apply {
            inputType = when (if (field.isChainage()) FieldType.TEXT else field.type) {
                FieldType.DECIMAL ->
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                FieldType.INTEGER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                FieldType.TEXT -> InputType.TYPE_CLASS_TEXT
            }
            isSingleLine = true
            maxLines = 1
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setSelectAllOnFocus(true)
            // setSelectAllOnFocus 只在"面板刚打开"和"焦点导航"时生效。手指点回来时，
            // ACTION_DOWN 触发的全选会紧接着被 ACTION_UP 重定位光标取消，新输的内容
            // 就接在旧值后面——实测把 K0+021 改一下变成了 K0+021K3+800。
            //
            // 所以点完再补一次：只要落成"光标态"（没选中任何字符）就全选。
            // 已经全选着的不动，免得连点两下反而把选区弄丢。
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    v.post {
                        if (selectionStart == selectionEnd && text.isNotEmpty()) {
                            setSelection(0, text.length)
                        }
                    }
                }
                false
            }
            setPadding(dp(2), dp(8), dp(2), dp(8))
            if (slots > 1) {
                hint = if (index == 0) "①" else if (index == 1) "②" else "③"
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (index > 0) marginStart = dp(6) }
                background = ContextCompat.getDrawable(context, R.drawable.bg_value_box)
            } else {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).toInt()

    private fun collectTyped() {
        val fields = category.activeFields()
        var cursor = 0
        fields.forEach { field ->
            val slots = field.slots()
            val parts = ArrayList<String>()
            for (i in 0 until slots) {
                val et = editors.getOrNull(cursor + i)
                parts.add(et?.text?.toString()?.trim().orEmpty())
            }
            cursor += slots
            typed[field.id] = field.joinValues(parts)
        }
    }

    private fun focusNext(from: Int) {
        val next = editors.getOrNull(from + 1)
        if (next != null) next.requestFocus() else confirm()
    }

    /**
     * 回车防重复触发。物理键盘回车会先走 TextView 默认的焦点前进再抛 editor action，
     * 软键盘的"下一项/完成"只抛 action。这里把 220ms 内的第二次动作吃掉。
     */
    private fun advanceOnce(action: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAdvanceAt < 220L) return
        lastAdvanceAt = now
        action()
    }

    private fun applyLastValues() {
        if (lastValues.isEmpty()) {
            Toast.makeText(context, R.string.no_last_values, Toast.LENGTH_SHORT).show()
            return
        }
        var filled = 0
        var cursor = 0
        category.activeFields().forEach { field ->
            val slots = field.slots()
            val stored = lastValues[field.id]
            if (!stored.isNullOrBlank()) {
                val parts = field.splitValue(stored)
                for (i in 0 until slots) {
                    val et = editors.getOrNull(cursor + i)
                    if (et != null && et.text.isNullOrBlank() && parts.getOrElse(i) { "" }.isNotBlank()) {
                        et.setText(parts[i])
                        filled++
                    }
                }
            }
            cursor += slots
        }
        val msg = if (filled == 0) {
            context.getString(R.string.reuse_nothing_empty)
        } else {
            context.getString(R.string.reused_count, filled)
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun warning(field: FieldDef, slot: Int, value: String): String? {
        if (field.type == FieldType.TEXT || field.isChainage()) return null
        val previous = field.splitValue(lastValues[field.id].orEmpty()).getOrNull(slot)
        return ReadingChecks.warning(value, previous, field.warningRange)
    }

    private fun confirm() {
        if (confirmed) return
        collectTyped()
        val warnings = mutableListOf<String>()
        val values = LinkedHashMap<String, String>()
        var cursor = 0
        for (field in category.activeFields()) {
            val slots = field.slots()
            val v = typed[field.id].orEmpty().trim()
            if (!field.isChainage() && v.isNotEmpty() && !accepts(field.type, v)) {
                val target = editors.getOrNull(cursor)
                target?.error = context.getString(R.string.err_number)
                target?.requestFocus()
                Toast.makeText(context, R.string.err_number, Toast.LENGTH_SHORT).show()
                return
            }
            if (field.isChainage() && template.autoChainage && v.isNotBlank()) {
                val metres = Chainage.parse(v)
                val limit = template.advanceLimit()
                val error = when {
                    metres == null -> "请输入有效桩号，例如 K0+350"
                    lastChainage != null && Chainage.exceedsLimit(lastChainage, metres, limit) ->
                        "上次桩号 " + Chainage.format(lastChainage) + "，单次推进不能超过 $limit 米"
                    else -> null
                }
                if (error != null) {
                    editors[cursor].error = error
                    editors[cursor].requestFocus()
                    return
                }
            }
            field.splitValue(v).forEachIndexed { i, raw ->
                warning(field, i, raw)?.let { reason ->
                    val label = if (slots == 1) field.label else field.label + "（" + (i + 1) + "）"
                    warnings.add("$label $raw " + field.unit + " · $reason")
                }
            }
            cursor += slots
            values[field.id] = v
        }
        ReadingDialogs.confirm(context, warnings) {
            if (!confirmed) {
                confirmed = true
                dismiss()
                onConfirmed(category.id, values)
            }
        }
    }

    private fun accepts(type: FieldType, raw: String): Boolean = when (type) {
        // 多值字段存的是 "3.0/3.2"，逐段校验
        FieldType.DECIMAL -> raw.split("/").all { it.isBlank() || it.trim().toDoubleOrNull()?.isFinite() == true }
        FieldType.INTEGER -> raw.split("/").all { it.isBlank() || it.trim().toLongOrNull() != null }
        FieldType.TEXT -> true
    }

    fun showForm() {
        show()
        val dm = context.resources.displayMetrics
        window?.apply {
            setLayout(
                (dm.widthPixels * 0.94f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setDimAmount(0.55f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        val first = editors.firstOrNull() ?: return
        first.post {
            first.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(first, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
