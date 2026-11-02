package com.measurecam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.measurecam.data.CaptureRecord
import com.measurecam.data.RecordRepository
import com.measurecam.data.RecordFilter
import com.measurecam.data.TemplateRepository
import com.measurecam.databinding.ActivityRecordListBinding
import com.measurecam.databinding.ItemRecordBinding
import com.measurecam.storage.CsvExporter
import com.measurecam.storage.OriginalStore
import com.measurecam.storage.PhotoImporter
import com.measurecam.storage.PhotoSaver
import com.measurecam.render.WatermarkRenderer
import java.util.concurrent.Executors

/**
 * 记录页：看拍过什么、改数值、删记录、导 CSV。
 *
 * 单击进编辑页（有原图才可改），长按出菜单。老照片没有原图，
 * 点一下直接开相册看大图——那种改不了，只能删了重拍。
 */
class RecordListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordListBinding
    private val items = mutableListOf<CaptureRecord>()
    private var allRecords = emptyList<CaptureRecord>()
    private var editableIds = emptySet<String>()
    private var filter = RecordFilter()
    private var working = false
    private val adapter = RecordAdapter()
    private val io = Executors.newSingleThreadExecutor()

    /** 常驻字段的值存在和取景页同一个 SharedPreferences 里 */
    private val prefs by lazy { getSharedPreferences("measurecam", Context.MODE_PRIVATE) }

    /**
     * 系统照片选择器（Photo Picker）。
     *
     * 走这个的好处是完全不用申请存储权限——Android 11+ 是系统选择器，低版本由
     * Play 服务或系统回落实现，用户只把选中的那几张授权给我们，比"读取整个相册"
     * 干净得多，上架审核也省事。
     */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT)
    ) { uris ->
        if (!uris.isNullOrEmpty()) startImport(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        filter = RecordFilter(
            category = savedInstanceState?.getString("category"),
            days = savedInstanceState?.getInt("days") ?: 0
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnExport.setOnClickListener { exportCsv() }
        binding.btnClear.setOnClickListener { confirmClearAll() }
        binding.btnImport.setOnClickListener { pickImages() }
        binding.searchInput.doAfterTextChanged { applyFilters() }
        binding.btnCategory.setOnClickListener {
            val categories = allRecords.map { it.categoryName }.distinct().sorted()
            val labels = arrayOf(getString(R.string.filter_all_categories)) + categories.toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle(R.string.filter_category)
                .setSingleChoiceItems(labels, categories.indexOf(filter.category) + 1) { dialog, which ->
                    filter = filter.copy(category = categories.getOrNull(which - 1))
                    dialog.dismiss()
                    applyFilters()
                }.show()
        }
        binding.btnPeriod.setOnClickListener {
            val days = listOf(0, 1, 7, 30)
            MaterialAlertDialogBuilder(this).setTitle(R.string.filter_period)
                .setSingleChoiceItems(R.array.record_periods, days.indexOf(filter.days)) { dialog, which ->
                    filter = filter.copy(days = days[which])
                    dialog.dismiss()
                    applyFilters()
                }.show()
        }
        binding.btnReset.setOnClickListener {
            filter = RecordFilter()
            binding.searchInput.text?.clear()
            applyFilters()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("category", filter.category)
        outState.putInt("days", filter.days)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun reload() {
        if (isFinishing || isDestroyed) return
        binding.loading.visibility = View.VISIBLE
        io.execute {
            val result = runCatching { RecordRepository.load(this) }
            val all = result.getOrDefault(emptyList())
            val editable = all.filter { OriginalStore.exists(this, it.originalName) }.map { it.id }.toSet()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.loading.visibility = View.GONE
                if (result.isFailure) {
                    toast(getString(R.string.record_load_failed))
                    return@runOnUiThread
                }
                allRecords = all.reversed()
                editableIds = editable
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        filter = filter.copy(query = binding.searchInput.text?.toString().orEmpty())
        items.clear()
        items.addAll(filter.apply(allRecords))
        adapter.submitList(items.map { RecordRow(it, it.id in editableIds) })
        binding.tvCount.text = getString(R.string.record_filtered_count, items.size, allRecords.size)
        binding.tvEmpty.setText(if (allRecords.isEmpty()) R.string.record_empty else R.string.record_no_match)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.btnCategory.text = filter.category ?: getString(R.string.filter_all_categories)
        binding.btnPeriod.text = resources.getStringArray(R.array.record_periods)[listOf(0, 1, 7, 30).indexOf(filter.days).coerceAtLeast(0)]
        binding.btnReset.visibility = if (filter == RecordFilter()) View.GONE else View.VISIBLE
        binding.btnExport.text = getString(if (filter == RecordFilter()) R.string.record_export else R.string.record_export_filtered)
        binding.btnExport.isEnabled = !working && items.isNotEmpty()
        binding.btnClear.isEnabled = !working && items.isNotEmpty()
    }

    private fun setWorking(value: Boolean) {
        working = value
        binding.btnImport.isEnabled = !value
        binding.loading.visibility = if (value) View.VISIBLE else View.GONE
        applyFilters()
    }

    // ---------------- 图片导入 ----------------

    private fun pickImages() {
        runCatching {
            importLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure { toast(getString(R.string.import_all_failed)) }
    }

    /**
     * 导入已有照片。
     *
     * 走的是和拍摄完全一样的一条流水线：留无水印基底 → 渲染 → 进相册 → 落记录。
     * 区别只有数值留空——导入时不知道这组数是哪个测点的，留给用户进编辑页补。
     * 模板/分类用当前在取景页选中的那套，常驻字段也照抄，导进来的照片和现场拍的
     * 水印格式一致。
     */
    private fun startImport(uris: List<Uri>) {
        if (working) return
        val template = TemplateRepository.selectedTemplate(this)
        val category = template.category(TemplateRepository.selectedCategory(this, template.id))
            ?: template.activeCategories().firstOrNull()
        if (category == null) {
            toast(getString(R.string.import_no_template))
            return
        }

        val stickyValues = template.activeSticky().associate { f ->
            f.id to (prefs.getString(stickyKey(template.id, f.id), f.value) ?: f.value)
        }

        val selected = uris.take(MAX_IMPORT)
        val total = selected.size
        setWorking(true)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_progress_title)
            .setMessage(getString(R.string.import_progress, 0, total))
            .setCancelable(false)
            .create()
        dialog.show()

        io.execute {
            val result = PhotoImporter(this)
                .import(selected, template, category, stickyValues) { done, all ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            dialog.setMessage(getString(R.string.import_progress, done, all))
                        }
                    }
                }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                setWorking(false)
                when {
                    result.imported == 0 -> toast(getString(R.string.import_all_failed))
                    result.failed > 0 -> toast(
                        getString(R.string.import_done_partial, result.imported, result.failed)
                    )
                    else -> toast(getString(R.string.import_done, result.imported))
                }
                reload()
                // 只导了一张就顺手打开编辑页——导入的图本来没数值，多半就是要补的
                if (result.imported == 1 && result.failed == 0) {
                    openEditor(result.records.first())
                }
            }
        }
    }

    private fun stickyKey(templateId: String, fieldId: String) = "sticky:$templateId:$fieldId"

    // ---------------- 删除 ----------------

    private fun showActions(r: CaptureRecord) {
        val actions = mutableListOf<String>()
        if (r.id in editableIds) actions.add(getString(R.string.action_edit))
        actions.add(getString(R.string.action_view_photo))
        actions.add(getString(R.string.action_delete))

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (r.chainage.isNotBlank()) r.chainage
                else WatermarkRenderer.formatTime(r.timeMillis)
            )
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.action_edit) -> openEditor(r)
                    getString(R.string.action_view_photo) -> openPhoto(r)
                    else -> confirmDelete(r)
                }
            }
            .show()
    }

    /** 删之前先说清照片留不留：有人只要表、有人连废片一起清 */
    private fun confirmDelete(r: CaptureRecord) {
        val options = arrayOf(
            getString(R.string.delete_record_only),
            getString(R.string.delete_with_photo)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_record_title)
            .setItems(options) { _, which -> performDelete(listOf(r), which == 1) }
            .show()
    }

    /**
     * 清空。
     *
     * 注意不能同时 setMessage 和 setItems：AlertDialog 的正文区只放得下一样，
     * 两者互顶，实测选项会整个消失只剩"取消"。所以说明写进标题和选项文案里。
     */
    private fun confirmClearAll() {
        if (items.isEmpty()) return
        val n = items.size
        val options = arrayOf(
            getString(R.string.clear_keep_photo),
            getString(R.string.clear_with_photo)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_filtered_title, n))
            .setItems(options) { _, which ->
                performDelete(ArrayList(items), withPhoto = which == 1)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 真删。原图一律删掉（它只是给编辑用的中间件，记录没了就没有意义）；
     * 相册里的成片按用户的选择决定。
     */
    private fun performDelete(targets: List<CaptureRecord>, withPhoto: Boolean) {
        if (targets.isEmpty() || working) return
        setWorking(true)
        val ids = targets.map { it.id }
        io.execute {
            val removed = runCatching { RecordRepository.deleteAll(this, ids) }.getOrDefault(0)
            var photoFailures = 0
            if (removed > 0) targets.forEach { r ->
                if (withPhoto && !PhotoSaver.deleteByName(this, r.photoName)) photoFailures++
                OriginalStore.delete(this, r.originalName)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setWorking(false)
                toast(when {
                    removed == 0 -> getString(R.string.record_write_failed)
                    photoFailures > 0 -> getString(R.string.delete_partial, removed, photoFailures)
                    else -> getString(R.string.deleted_count, removed)
                })
                reload()
            }
        }
    }

    // ---------------- 跳转 ----------------

    private fun openEditor(r: CaptureRecord) {
        startActivity(
            Intent(this, RecordEditActivity::class.java)
                .putExtra(RecordEditActivity.EXTRA_RECORD_ID, r.id)
        )
    }

    /** 按文件名去相册里找那张照片 */
    private fun openPhoto(record: CaptureRecord) {
        val uri = PhotoSaver.findUri(this, record.photoName)
        if (uri == null) {
            toast(getString(R.string.photo_missing))
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { toast(getString(R.string.photo_missing)) }
    }

    // ---------------- 导出 ----------------

    private fun exportCsv() {
        if (working) return
        if (items.isEmpty()) {
            toast(getString(R.string.record_no_data))
            return
        }
        val all = items.reversed()
        MaterialAlertDialogBuilder(this)
            .setTitle("导出 ${all.size} 条记录")
            .setItems(arrayOf("照片 + CSV（ZIP）", "仅 CSV")) { _, index -> exportRecords(all, index == 0) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun exportRecords(all: List<CaptureRecord>, withPhotos: Boolean) {
        setWorking(true)
        io.execute {
            val result = runCatching { CsvExporter.export(this, all, withPhotos) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setWorking(false)
                if (result == null || !result.ok) {
                    toast(getString(R.string.export_fail))
                    return@runOnUiThread
                }
                val where = result.publicPath ?: result.fileName
                val detail = buildString {
                    append(getString(R.string.export_ok, result.count, where))
                    if (withPhotos) append("\n已打包 ${result.photoCount} 张成片")
                    if (result.missing > 0) append("\n缺少 ${result.missing} 个照片文件，详见包内清单。")
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.record_export)
                    .setMessage(detail)
                    .setPositiveButton(R.string.export_share) { _, _ -> share(result) }
                    .setNegativeButton(R.string.ok, null)
                    .show()
            }
        }
    }

    private fun share(result: CsvExporter.Result) {
        val uri: Uri = result.shareUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = result.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, result.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.export_chooser)))
        }.onFailure { toast(getString(R.string.export_fail)) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 一次最多导入这么多张，再多就分几批——解码+重编码是同步的，堆太多会卡很久 */
        private const val MAX_IMPORT = 20
    }

    private data class RecordRow(val record: CaptureRecord, val editable: Boolean)

    private inner class RecordAdapter : ListAdapter<RecordRow, RecordAdapter.VH>(object : DiffUtil.ItemCallback<RecordRow>() {
        override fun areItemsTheSame(old: RecordRow, new: RecordRow) = old.record.id == new.record.id
        override fun areContentsTheSame(old: RecordRow, new: RecordRow) = old == new
    }) {

        inner class VH(val item: ItemRecordBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val item = ItemRecordBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(item)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = getItem(position)
            val r = row.record
            holder.item.tvTime.text = WatermarkRenderer.formatTime(r.timeMillis)
            holder.item.tvCategory.text = r.categoryName.ifBlank { "-" }

            val values = CsvExporter.summaryLine(r)
            holder.item.tvChainage.text = r.chainage.ifBlank { getString(R.string.record_no_chainage) }
            holder.item.tvValues.text = values.ifBlank { getString(R.string.record_fill_values) }
            holder.item.btnMore.setOnClickListener { if (!working) showActions(r) }

            val metaParts = ArrayList<String>()
            // 导入的单独标一下：这批图的拍摄时间可能是几天前，跟现场拍的混在一起容易看错
            if (r.imported()) metaParts.add(getString(R.string.import_tag))
            r.stickyList().forEach { f ->
                val v = f.valueList().firstOrNull().orEmpty()
                if (v.isNotBlank()) metaParts.add(v)
            }
            val move = r.moveMeters
            if (move != null) metaParts.add("位移 ${String.format(java.util.Locale.US, "%.1f", move)}m")
            if (!row.editable) metaParts.add(getString(R.string.record_no_original))
            holder.item.tvMeta.text = metaParts.joinToString(" · ")
            holder.item.tvMeta.visibility = if (metaParts.isEmpty()) View.GONE else View.VISIBLE

            holder.item.root.setOnClickListener {
                if (!working) { if (row.editable) openEditor(r) else openPhoto(r) }
            }
            holder.item.root.setOnLongClickListener {
                if (!working) showActions(r)
                true
            }
        }
    }
}
