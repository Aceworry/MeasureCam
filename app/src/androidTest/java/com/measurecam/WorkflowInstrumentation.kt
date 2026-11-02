package com.measurecam

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import com.measurecam.util.ImageLoader
import com.measurecam.widget.ZoomDialView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.measurecam.render.WatermarkRenderer
import com.measurecam.widget.StyleDialogs
import com.measurecam.capture.CaptureFormDialog
import java.util.zip.ZipFile
import com.measurecam.widget.DragOrder
import com.measurecam.util.WeatherService
import androidx.recyclerview.widget.RecyclerView
import com.measurecam.data.*
import com.measurecam.storage.OriginalStore
import com.measurecam.storage.PhotoSaver
import com.measurecam.storage.PhotoImporter
import android.net.Uri
import java.io.File

/** 在真机运行，不依赖下载测试库；只创建和清理自己的验收数据。 */
class WorkflowInstrumentation : Instrumentation() {
    private var activity: Activity? = null
    @Volatile private var resumed: Activity? = null
    @Volatile private var cameraActivity: Activity? = null
    private val resultLines = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private val marker = "MC_REVIEW_${System.currentTimeMillis()}"
    private val sample = CaptureRecord(
        id = marker, photoName = "$marker.jpg", originalName = "$marker.jpg",
        categoryName = "验收分类", chainage = "K1+050", chainageMeters = 1050.0,
        sticky = listOf(RecordField("项目名称", values = listOf("界面验收"))),
        fields = listOf(RecordField("测点桩号", values = listOf("K1+050"), role = "CHAINAGE", type = "TEXT"),
            RecordField("深度", "mm", listOf("850"), type = "DECIMAL"),
            RecordField("平整度（水平）", "mm", listOf("3.1", "3.2"), type = "DECIMAL"))
    )

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        resumed = activity
        if (activity is MainActivity) cameraActivity = activity
    }

    override fun onStart() {
        try {
            test("atomic rollback and failed write preserve existing records") { storageChecks() }
            check(RecordRepository.append(targetContext, sample))
            val image = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.rgb(87, 111, 96))
            val original = OriginalStore.file(targetContext, sample.originalName!!)
            original.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            image.recycle()
            // 部分设备限制后台启动页面，先通过 shell 打开已导出的相机入口。
            uiAutomation.executeShellCommand("am start -n com.measurecam/.MainActivity").close()
            await { cameraActivity != null }
            test("list search, reset, empty state and displayed record count") { listChecks() }
            closeActivity()
            test("editor text keyboard, sampled preview and unsaved confirmation") { editorChecks() }
            closeActivity()
            test("editor save updates record and gallery photo") { saveChecks() }
            closeActivity()
            test("filtered ZIP contains matching gallery photo and CSV") { zipExportChecks() }
            closeActivity()
            test("photo drag persists through save and reopening") { recordDragChecks() }
            closeActivity()
            test("capture entry warns and enforces chainage limit") { entryValidationChecks() }
            test("camera drag updates template and imported photo") { cameraDragChecks() }
            closeActivity()
            test("photo import preserves type and creates editable record") { importChecks() }
            test("camera controls stay fixed and JPEG follows capture orientation") { rotationChecks() }
            test("zoom badge fully visible inside control") { zoomBoundsChecks() }
            test("template library and fixed save action") { templateScreenChecks() }
            closeActivity()
            test("per-field spans and background opacity render") { styleRenderChecks() }
            test("watermark settings and field format dialogs") { styleDialogChecks() }
            test("native drag reorder and template persistence") { templateOrderChecks() }
            closeActivity()
            test("live weather request and cached forecast") { weatherChecks() }
        } catch (e: Throwable) {
            failures.add("setup: ${e.stackTraceToString()}")
        } finally {
            closeActivity()
            cameraActivity?.let { a -> runOnMainSync { a.finish() } }
            RecordRepository.delete(targetContext, sample.id)
            OriginalStore.delete(targetContext, sample.originalName)
            PhotoSaver.deleteByName(targetContext, sample.photoName)
        }
        val output = (resultLines + failures).joinToString("\n")
        File(targetContext.getExternalFilesDir(null), "review-results.txt").writeText(output)
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "\n$output\n"); putInt("failures", failures.size) })
    }

    private fun test(name: String, body: () -> Unit) {
        sendStatus(1, Bundle().apply { putString("stream", "START: $name\n") })
        try { body(); resultLines.add("PASS: $name") }
        catch (e: Throwable) { failures.add("FAIL: $name\n${e.stackTraceToString()}") }
        sendStatus(0, Bundle().apply { putString("stream", "END: $name\n") })
    }

    private fun zipExportChecks() {
        val list = open(RecordListActivity::class.java)
        runOnMainSync { list.findViewById<EditText>(R.id.search_input).setText(marker) }
        await { list.findViewById<RecyclerView>(R.id.recycler).adapter!!.itemCount == 1 }
        val directory = File(targetContext.getExternalFilesDir(null), "export")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        runOnMainSync { list.findViewById<View>(R.id.btn_export).performClick() }
        clickText("照片 + CSV（ZIP）")
        fun exported() = directory.listFiles().orEmpty().firstOrNull { it.extension == "zip" && it.name !in before }
        await { exported() != null && list.findViewById<View>(R.id.btn_export).isEnabled }
        val file = exported()!!
        ZipFile(file).use { zip ->
            val csv = zip.getInputStream(zip.getEntry("记录.csv")).bufferedReader().use { it.readText() }
            check(csv.contains("照片/" + sample.photoName) && csv.trimEnd().lines().size == 2)
            val archived = zip.getInputStream(zip.getEntry("照片/" + sample.photoName)).use { it.readBytes() }
            val uri = PhotoSaver.findUri(targetContext, sample.photoName)!!
            check(targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(archived))
        }
        clickText(targetContext.getString(R.string.ok))
        file.delete()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            targetContext.contentResolver.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "_display_name = ? AND relative_path = ?", arrayOf(file.name, "Download/图数相机/"))
        }
    }

    private fun swipe(view: View, x: Float, y: Float, toX: Float, toY: Float) {
        val time = SystemClock.uptimeMillis()
        for ((action, point) in listOf(MotionEvent.ACTION_DOWN to (x to y),
            MotionEvent.ACTION_MOVE to (toX to toY), MotionEvent.ACTION_UP to (toX to toY))) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
            try { check(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun recordDragChecks() {
        val a = open(RecordEditActivity::class.java, sample.id)
        val view = a.findViewById<ImageView>(R.id.iv_preview)
        await { view.drawable is BitmapDrawable }
        runOnMainSync {
            val b = (view.drawable as BitmapDrawable).bitmap
            val scale = minOf(view.width.toFloat() / b.width, view.height.toFloat() / b.height)
            val x = (view.width - b.width * scale) / 2 + b.width * scale * .07f
            val y = (view.height - b.height * scale) / 2 + b.height * scale * .95f
            swipe(view, x, y, x + b.width * scale * .35f, y - b.height * scale * .30f)
            check(a.findViewById<TextView>(R.id.tv_anchor_value).text.startsWith("自定义"))
            a.findViewById<View>(R.id.btn_save).performClick()
        }
        await { a.isFinishing }
        val saved = RecordRepository.find(targetContext, sample.id)!!.watermarkPosition!!
        check(saved.x in .1f.. .9f && saved.y in .1f.. .9f)
        closeActivity()
        val again = open(RecordEditActivity::class.java, sample.id)
        await { again.findViewById<TextView>(R.id.tv_anchor_value).text.startsWith("自定义") }
    }

    private fun cameraDragChecks() {
        val previous = TemplateRepository.selectedTemplate(targetContext).id
        val cat = MeasureCategory(name = "验收分类", fields = mutableListOf(FieldDef(label = "深度")))
        val temp = CaptureTemplate(id = marker + "_drag", title = "拖动位置验收", categories = mutableListOf(cat))
        check(TemplateRepository.save(targetContext, (TemplateRepository.load(targetContext) + temp).toMutableList()))
        try {
            TemplateRepository.select(targetContext, temp.id)
            open(TemplateListActivity::class.java)
            closeActivity()
            val mark = cameraActivity!!.findViewById<TextView>(R.id.watermark_preview)
            await { mark.text.contains(temp.title) && mark.width > 0 }
            runOnMainSync {
                val x = mark.width / 2f; val y = mark.height / 2f
                swipe(mark, x, y, x + 120, y - 200)
            }
            val chosen = TemplateRepository.selectedTemplate(targetContext)
            val point = chosen.watermarkPosition ?: error("Camera position was not saved")
            check(chosen.id == temp.id && point.x > 0 && point.y < 1)
            val input = Uri.fromFile(OriginalStore.file(targetContext, sample.originalName!!))
            val result = PhotoImporter(targetContext).import(listOf(input), chosen, cat, emptyMap())
            try {
                check(result.records.single().watermarkPosition == point)
            } finally {
                result.records.forEach {
                    RecordRepository.delete(targetContext, it.id)
                    OriginalStore.delete(targetContext, it.originalName)
                    PhotoSaver.deleteByName(targetContext, it.photoName)
                }
            }
        } finally {
            if (TemplateRepository.selectedTemplate(targetContext).id == temp.id)
                TemplateRepository.select(targetContext, previous)
            TemplateRepository.save(targetContext, TemplateRepository.load(targetContext).filter { it.id != temp.id }.toMutableList())
            open(TemplateListActivity::class.java)
            closeActivity()
        }
    }

    private fun entryValidationChecks() {
        val chain = FieldDef(label = "桩号", role = FieldRole.CHAINAGE, type = FieldType.TEXT)
        val depth = FieldDef(label = "深度", unit = "mm", valueCount = 2, warningRange = ReadingRange(max = 20.0))
        val category = MeasureCategory(name = "测量", fields = mutableListOf(chain, depth))
        val template = CaptureTemplate(categories = mutableListOf(category), maxChainageAdvance = 50)
        var result: Map<String, String>? = null
        lateinit var dialog: CaptureFormDialog
        runOnMainSync {
            dialog = CaptureFormDialog(cameraActivity!!, template, category.id, mapOf(depth.id to "1/2"),
                "K1+050", "", 1000.0, { _, v -> result = v }).apply { showForm() }
        }
        try {
            val fields = edits(dialog.findViewById(R.id.fields_container))
            val save = dialog.findViewById<View>(R.id.btn_save)
            runOnMainSync {
                fields[0].setText("K1+051")
                fields[1].setText("10")
                fields[2].setText("25")
                save.performClick()
                check(fields[0].error?.contains("50") == true && result == null)
                fields[0].setText("K1+050")
                check(fields[1].error?.contains("10 倍") == true && fields[2].error?.contains("上限") == true)
                save.performClick()
            }
            waitForIdleSync()
            clickText("返回修改")
            check(dialog.isShowing && result == null)
            runOnMainSync { save.performClick() }
            waitForIdleSync()
            clickText("核实并保存")
            check(result?.get(depth.id) == "10/25" && result?.get(chain.id) == "K1+050")
        } finally { runOnMainSync { if (dialog.isShowing) dialog.dismiss() } }
    }

    private fun storageChecks() {
        val directory = File(targetContext.cacheDir, marker).apply { mkdirs() }
        val isolated = object : ContextWrapper(targetContext) { override fun getFilesDir() = directory }
        try {
            check(RecordRepository.append(isolated, sample))
            val atomic = AtomicFile(File(directory, "records.jsonl"))
            val output = atomic.startWrite()
            output.write("partial".toByteArray())
            atomic.failWrite(output)
            check(RecordRepository.load(isolated).single().id == sample.id)
            val blocked = File(directory, "records.jsonl.new").apply { mkdir() }
            File(blocked, "blocker").writeText("prevent replacement")
            check(!RecordRepository.append(isolated, sample.copy(id = "second")))
            check(RecordRepository.deleteAll(isolated, listOf(sample.id)) == 0)
            check(RecordRepository.load(isolated).single().id == sample.id)
            blocked.deleteRecursively()
            File(directory, "records.jsonl").appendText("\n{invalid json\n{}\n")
            check(RecordRepository.load(isolated).single().id == sample.id)
            check(RecordRepository.delete(isolated, sample.id))
            check(RecordRepository.load(isolated).isEmpty())
        } finally { directory.deleteRecursively() }
    }

    private fun listChecks() {
        val a = open(RecordListActivity::class.java)
        val search = a.findViewById<EditText>(R.id.search_input)
        await { a.findViewById<TextView>(R.id.tv_count).text.isNotBlank() }
        runOnMainSync { search.setText(marker) }
        await { a.findViewById<RecyclerView>(R.id.recycler).adapter!!.itemCount == 1 }
        check(a.findViewById<TextView>(R.id.btn_export).text.toString() == "导出筛选结果")
        screenshot("v1.8-record-list.png")
        runOnMainSync { search.setText("NEVER_MATCH_$marker") }
        await { a.findViewById<RecyclerView>(R.id.recycler).adapter!!.itemCount == 0 }
        check(a.findViewById<View>(R.id.tv_empty).visibility == View.VISIBLE)
        check(!a.findViewById<View>(R.id.btn_export).isEnabled)
        runOnMainSync { a.findViewById<View>(R.id.btn_reset).performClick() }
        await { a.findViewById<RecyclerView>(R.id.recycler).adapter!!.itemCount > 0 }
    }

    private fun editorChecks() {
        val a = open(RecordEditActivity::class.java, sample.id)
        await { a.findViewById<View>(R.id.btn_save).isEnabled }
        await { a.findViewById<ImageView>(R.id.iv_preview).drawable is BitmapDrawable }
        val bitmap = (a.findViewById<ImageView>(R.id.iv_preview).drawable as BitmapDrawable).bitmap
        check(maxOf(bitmap.width, bitmap.height) <= 720)
        val sticky = edits(a.findViewById(R.id.sticky_container)).first()
        check(sticky.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT)
        val fields = edits(a.findViewById(R.id.field_container))
        check(fields[1].inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0)
        runOnMainSync { sticky.setText("项目修改测试") }
        check(RecordRepository.find(targetContext, sample.id)!!.stickyList().first().valueList().first() == "界面验收")
        screenshot("v1.8-record-edit.png")
        runOnMainSync { a.findViewById<View>(R.id.btn_cancel).performClick() }
        waitForIdleSync()
        check(!a.isFinishing)
        screenshot("v1.8-unsaved-confirmation.png")
    }

    private fun rotationChecks() {
        val a = cameraActivity as MainActivity
        val field = MainActivity::class.java.getDeclaredField("imageCapture").apply { isAccessible = true }
        await { field.get(a) != null }
        val capture = field.get(a) as ImageCapture
        val shutter = a.findViewById<View>(R.id.btn_shutter)
        val dial = a.findViewById<View>(R.id.zoom_dial)
        fun bounds(v: View) = Rect().also { v.getGlobalVisibleRect(it) }
        val shutterBounds = bounds(shutter); val zoomBounds = bounds(dial)
        check(a.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        for (degrees in listOf(0, 90, 270)) {
            val file = File(targetContext.cacheDir, "${marker}_rotation_$degrees.jpg")
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            try {
                runOnMainSync {
                    a.updateCaptureOrientation(degrees)
                    check(a.findViewById<View>(R.id.btn_shutter) === shutter)
                    check(bounds(shutter) == shutterBounds && bounds(dial) == zoomBounds)
                    check(capture.targetRotation == when (degrees) { 90 -> 3; 270 -> 1; else -> 0 })
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(a),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) { done.countDown() }
                            override fun onError(exception: ImageCaptureException) { failure = exception; done.countDown() }
                        })
                }
                check(done.await(15, TimeUnit.SECONDS)) { "Capture timed out" }
                failure?.let { throw it }
                val bitmap = ImageLoader.load(file, 720) ?: error("Missing captured JPEG")
                try {
                    check(if (degrees == 0) bitmap.height > bitmap.width else bitmap.width > bitmap.height) {
                        "Incorrect JPEG orientation for $degrees: ${bitmap.width}x${bitmap.height}"
                    }
                } finally { bitmap.recycle() }
            } finally { file.delete() }
        }
        runOnMainSync { a.updateCaptureOrientation(0) }
        screenshot("v1.8-camera-fixed.png")
    }

    private fun zoomBoundsChecks() {
        val dial = cameraActivity!!.findViewById<ZoomDialView>(R.id.zoom_dial)
        val rect = Rect()
        runOnMainSync { check(dial.getGlobalVisibleRect(rect)); check(rect.height() == dial.height) }
        val bmp = Bitmap.createBitmap(dial.width, dial.height, Bitmap.Config.ARGB_8888)
        runOnMainSync { dial.draw(android.graphics.Canvas(bmp)) }
        try {
            // 顶部需留白，旧气泡越过顶部边界时第一行会出现被裁切的像素。
            check((0 until bmp.width).all { Color.alpha(bmp.getPixel(it, 0)) == 0 })
            val pixels = IntArray(bmp.width * bmp.height); bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            check(pixels.any { Color.alpha(it) > 0 })
        } finally { bmp.recycle() }
    }

    private fun templateScreenChecks() {
        val list = open(TemplateListActivity::class.java)
        check(list.findViewById<TextView>(R.id.tv_count).text.isNotBlank())
        screenshot("v1.8-template-list.png")
        closeActivity()
        val a = open(TemplateEditActivity::class.java, TemplateRepository.load(targetContext).first().id)
        val save = a.findViewById<View>(R.id.btn_save)
        val before = Rect().also { check(save.getGlobalVisibleRect(it)) }
        val sticky = a.findViewById<View>(R.id.container_sticky)
        runOnMainSync { sticky.requestRectangleOnScreen(Rect(0, 0, sticky.width, sticky.height), true) }
        waitForIdleSync()
        check(Rect().also { save.getGlobalVisibleRect(it) } == before)
        screenshot("v1.8-template-edit.png")
    }

    private fun styleRenderChecks() {
        val format = FieldAppearance(TextFormat(bold = false, underline = true, color = "#00FF00"), TextFormat(background = "#FFFF00"))
        val meta = CaptureMeta(measureFields = listOf(RecordField("深度", values = listOf("850"), appearance = format)),
            showTime = false, style = WatermarkStyle(background = "#FF0000", opacity = 1f))
        check(!WatermarkRenderer.previewText(meta.copy(weather = "天气：晴 24℃（预报·MET Norway）")).contains("MET Norway"))
        val preview = WatermarkRenderer.previewText(meta) as android.text.Spanned
        check(preview.getSpans(0, preview.length, android.text.style.UnderlineSpan::class.java).isNotEmpty())
        check(preview.getSpans(0, preview.length, android.text.style.BackgroundColorSpan::class.java).isNotEmpty())
        val paint = android.text.TextPaint().apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        WatermarkRenderer.ExactWeightSpan(false).updateDrawState(paint)
        check(!paint.typeface.isBold)
        fun redPixels(opacity: Float): Int {
            val bmp = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            WatermarkRenderer.render(bmp, meta.copy(style = meta.style.copy(opacity = opacity)))
            val pixels = IntArray(480 * 320); bmp.getPixels(pixels, 0, 480, 0, 0, 480, 320); bmp.recycle()
            return pixels.count { it == Color.RED }
        }
        check(redPixels(1f) > 100); check(redPixels(0f) == 0)
    }

    private fun clickText(text: String) {
        fun find() = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text }
        val deadline = SystemClock.uptimeMillis() + 4000
        var found = find()
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            waitForIdleSync()
            Thread.sleep(100)
            found = find()
        }
        val node = found ?: error("Missing control: $text")
        var target = node
        while (!target.isClickable && target.parent != null) target = target.parent
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitForIdleSync()
    }

    private fun styleDialogChecks() {
        runOnMainSync { cameraActivity!!.findViewById<View>(R.id.btn_settings).performClick() }
        waitForIdleSync(); screenshot("v1.8-watermark-settings.png")
        clickText("主题颜色")
        screenshot("v1.8-color-picker.png")
        clickText("蓝色")
        clickText("取消")
        runOnMainSync { StyleDialogs.field(cameraActivity!!, "深度", FieldAppearance(value = TextFormat(bold = true, underline = true, color = "#FFEB3B"))) { } }
        waitForIdleSync(); screenshot("v1.8-field-format.png")
        clickText("取消")
    }

    private fun templateOrderChecks() {
        val id = marker + "_template"
        val t = CaptureTemplate(id = id, name = "排序验收", watermarkStyle = WatermarkStyle(theme = "#1565C0", opacity = 0.4f), showWeather = true,
            sticky = mutableListOf(StickyField(id = "s1", label = "项目名称", value = "保留值"), StickyField(id = "s2", label = "标段")),
            categories = mutableListOf(MeasureCategory(id = "c1", name = "基平台", fields = mutableListOf(
                FieldDef(id = "f1", label = "深度", defaultValue = "850", appearance = FieldAppearance(value = TextFormat(underline = true))),
                FieldDef(id = "f2", label = "宽度"))), MeasureCategory(id = "c2", name = "坡面", fields = mutableListOf(FieldDef(label = "厚度")))))
        check(TemplateRepository.save(targetContext, (TemplateRepository.load(targetContext) + t).toMutableList()))
        try {
            val a = open(TemplateEditActivity::class.java, id)
            val sticky = a.findViewById<LinearLayout>(R.id.container_sticky)
            runOnMainSync { sticky.requestRectangleOnScreen(Rect(0, 0, sticky.width, sticky.height), true) }
            waitForIdleSync()
            val start = IntArray(2); val end = IntArray(2)
            val first = sticky.getChildAt(0) as LinearLayout
            val handle = first.getChildAt(0)
            runOnMainSync { handle.getLocationOnScreen(start); sticky.getChildAt(1).getLocationOnScreen(end) }
            val x = start[0] + handle.width / 2f; val y = start[1] + handle.height / 2f
            val destination = end[1] + sticky.getChildAt(1).height - 5f
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, yy: Float) {
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, yy, 0)
                e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(uiAutomation.injectInputEvent(e, true)); e.recycle()
            }
            touch(MotionEvent.ACTION_DOWN, y); Thread.sleep(800)
            for (i in 1..20) { touch(MotionEvent.ACTION_MOVE, y + (destination - y) * i / 20); Thread.sleep(30) }
            touch(MotionEvent.ACTION_UP, destination); waitForIdleSync()
            check(sticky.getChildAt(0).tag == "s2") { "Native drag did not reorder sticky fields" }
            val cats = a.findViewById<LinearLayout>(R.id.container_category)
            val fieldBox = cats.getChildAt(0).findViewById<LinearLayout>(R.id.container_fields)
            runOnMainSync {
                DragOrder.move(fieldBox, fieldBox.getChildAt(0), 1)
                DragOrder.move(cats, cats.getChildAt(0), 1)
                cats.requestRectangleOnScreen(Rect(0, 0, cats.width, minOf(cats.height, 500)), true)
            }
            screenshot("v1.8-template-order.png")
            runOnMainSync { a.findViewById<View>(R.id.btn_save).performClick() }
            await { a.isFinishing }
            val saved = TemplateRepository.load(targetContext).single { it.id == id }
            check(saved.stickyList().map { it.id } == listOf("s2", "s1"))
            check(saved.stickyList()[1].value == "保留值")
            check(saved.categoryList().map { it.id } == listOf("c2", "c1"))
            check(saved.categoryList()[1].fieldList().map { it.id } == listOf("f2", "f1"))
            check(saved.categoryList()[1].fieldList()[1].appearance?.value?.underline == true)
            check(saved.watermarkStyle == t.watermarkStyle && saved.showWeather)
        } finally {
            closeActivity()
            TemplateRepository.save(targetContext, TemplateRepository.load(targetContext).filter { it.id != id }.toMutableList())
        }
    }

    private fun weatherChecks() {
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(marker + "_weather", mode)
        }
        try {
            WeatherService.refresh(isolated, 51.5, -0.12)
            val live = WeatherService.cached(isolated, 51.5, -0.12)
            check(live != null && live.contains("℃") && !live.contains("MET Norway")) { "Weather endpoint unavailable or returned no current forecast" }
            check(WeatherService.cached(isolated, 30.0, 110.0) == null)
            check(WeatherService.cached(isolated, 51.5, -0.12, System.currentTimeMillis() + 2 * 60 * 60_000) == null)
        } finally { isolated.getSharedPreferences("weather_cache", 0).edit().clear().commit() }
    }

    private fun open(clazz: Class<out Activity>, id: String? = null): Activity {
        val intent = Intent(targetContext, clazz)
        if (id != null) intent.putExtra(if (clazz == TemplateEditActivity::class.java) TemplateEditActivity.EXTRA_ID else RecordEditActivity.EXTRA_RECORD_ID, id)
        resumed = null
        runOnMainSync { cameraActivity!!.startActivity(intent) }
        await { resumed?.javaClass == clazz }
        return resumed!!.also { activity = it }
    }

    private fun saveChecks() {
        val a = open(RecordEditActivity::class.java, sample.id)
        await { a.findViewById<View>(R.id.btn_save).isEnabled }
        runOnMainSync {
            edits(a.findViewById(R.id.field_container))[1].setText("900")
            edits(a.findViewById(R.id.sticky_container)).first().setText("保存验收")
            a.findViewById<View>(R.id.btn_save).performClick()
        }
        await { a.isFinishing }
        val saved = RecordRepository.find(targetContext, sample.id)!!
        check(saved.fieldList()[1].valueList().single() == "900")
        check(saved.fieldList()[1].type == "DECIMAL")
        check(saved.stickyList().first().valueList().single() == "保存验收")
        check(PhotoSaver.findUri(targetContext, sample.photoName) != null)
    }

    private fun importChecks() {
        val template = CaptureTemplate(name = "验收模板")
        val category = MeasureCategory(name = "验收分类", fields = mutableListOf(FieldDef(label = "读数", type = FieldType.INTEGER)))
        val beforeState = TemplateRepository.chainageState(targetContext, template.id)
        val uri = Uri.fromFile(OriginalStore.file(targetContext, sample.originalName!!))
        val result = PhotoImporter(targetContext).import(listOf(uri), template, category, emptyMap())
        try {
            check(result.imported == 1 && result.failed == 0)
            val imported = result.records.single()
            check(imported.imported() && OriginalStore.exists(targetContext, imported.originalName))
            check(imported.fieldList().single().type == "INTEGER")
            check(TemplateRepository.chainageState(targetContext, template.id) == beforeState)
        } finally {
            result.records.forEach {
                RecordRepository.delete(targetContext, it.id)
                OriginalStore.delete(targetContext, it.originalName)
                PhotoSaver.deleteByName(targetContext, it.photoName)
            }
        }
    }

    private fun closeActivity() {
        activity?.let { a -> runOnMainSync { a.finish() }; waitForIdleSync() }
        activity = null
    }

    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < end) {
            var ready = false
            runOnMainSync { ready = condition() }
            if (ready) { waitForIdleSync(); return }
            Thread.sleep(100)
        }
        error("Timed out waiting for UI")
    }

    private fun edits(view: View): List<EditText> = when (view) {
        is EditText -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { edits(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun screenshot(name: String) {
        waitForIdleSync()
        Thread.sleep(400)
        val bitmap = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
