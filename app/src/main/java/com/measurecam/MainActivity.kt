package com.measurecam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.measurecam.widget.StyleDialogs
import com.measurecam.data.WatermarkStyle
import com.measurecam.util.WeatherService
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import com.measurecam.util.CaptureOrientation
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.measurecam.capture.CaptureFormDialog
import com.measurecam.data.CaptureMeta
import com.measurecam.data.CaptureRecord
import com.measurecam.data.CaptureTemplate
import com.measurecam.data.ChainageState
import com.measurecam.data.MeasureCategory
import com.measurecam.data.RecordField
import com.measurecam.data.RecordRepository
import com.measurecam.data.StickyField
import com.measurecam.data.TemplateRepository
import com.measurecam.data.WatermarkArea
import com.measurecam.data.WatermarkPosition
import com.measurecam.data.WatermarkAnchor
import com.measurecam.databinding.ActivityMainBinding
import com.measurecam.render.WatermarkRenderer
import com.measurecam.storage.OriginalStore
import com.measurecam.storage.PhotoSaver
import com.measurecam.util.Chainage
import com.measurecam.util.ImageLoader
import com.measurecam.util.LocationHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var imageCapture: ImageCapture? = null
    private var previewUseCase: Preview? = null
    private var captureRotation = Surface.ROTATION_0
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) { updateCaptureOrientation(orientation) }
        }
    }
    internal fun updateCaptureOrientation(degrees: Int) {
        captureRotation = CaptureOrientation.rotationFor(degrees, captureRotation)
        if (!busy) imageCapture?.targetRotation = captureRotation
    }
    private var camera: Camera? = null
    private var template: CaptureTemplate? = null
    private var category: MeasureCategory? = null

    /** 用户填表的同时，后台已经在解码照片了，保存时直接取结果 */
    private var pendingDecode: Future<Bitmap?>? = null

    /** 快门那一刻定下的桩号推算方案，保存时用来记「位移」和更新状态 */
    private var pendingMove: Double? = null

    // 变焦
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var zoomRatio = 1f

    private var capturedAt: Long = 0L
    private var capturedLat: Double? = null
    private var capturedLon: Double? = null
    private var capturedAddress: String? = null
    private var capturedWeather: String? = null
    private var weatherLine: String? = null
    private var weatherLoading = false
    private var lastWeatherCheck = 0L
    private val weatherExecutor = Executors.newSingleThreadExecutor()
    private val weatherHandler = Handler(Looper.getMainLooper())
    private val weatherTick = object : Runnable {
        override fun run() { requestWeather(); weatherHandler.postDelayed(this, 60_000) }
    }
    private var startingCamera = false
    private var lat: Double? = null
    private var lon: Double? = null
    private var address: String? = null
    private var busy = false

    /** 上次反查地址时的坐标，避免每次都打网络 */
    private var geocodedLat = Double.NaN
    private var geocodedLon = Double.NaN

    private val prefs by lazy {
        getSharedPreferences("measurecam", Context.MODE_PRIVATE)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val cameraOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraOk) {
            startCamera()
            if (LocationHelper.hasPermission(this)) requestLocation()
        } else {
            // 不直接 finish：工地手机上误点"禁止"后应用一闪就消失，用户会当成闪退。
            // 给一个明确的引导，授权后回到前台自动重新初始化。
            showCameraPermissionDialog()
        }
    }

    private fun showCameraPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_camera_title)
            .setMessage(R.string.perm_camera_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.action_go_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.action_exit) { _, _ -> finish() }
            .show()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }.onFailure { toast(R.string.perm_camera_msg) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        template = TemplateRepository.selectedTemplate(this)
        zoomRatio = prefs.getFloat(KEY_ZOOM, 1f)
        bindUi()
        setupZoomGesture()
        bindWatermarkDrag()
        binding.watermarkPreview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            template?.let { applyPreviewAnchor(it.anchor()) }
        }
        binding.bottomBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            template?.let { applyPreviewAnchor(it.anchor()) }
        }
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        weatherHandler.removeCallbacks(weatherTick)
        weatherHandler.post(weatherTick)
        // 从设置页授权回来时，相机还没起来就补一次初始化
        if (imageCapture == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
            if (LocationHelper.hasPermission(this)) requestLocation()
        }
        // 从模板管理返回后重新读一遍，保证字段改动立即生效
        if (!busy) {
            template = TemplateRepository.selectedTemplate(this)
            category = template?.category(category?.id)
            renderTemplateChips()
            renderStickyChips()
            renderCategoryChips()
            refreshWatermarkPreview()
        }
    }

    private fun requestWeather() {
        if (template?.showWeather != true || isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val la = lat ?: return; val lo = lon ?: return
        weatherLine = WeatherService.cached(this, la, lo)
        refreshWatermarkPreview()
        val now = System.currentTimeMillis()
        if (weatherLoading || now - lastWeatherCheck < 30_000) return
        lastWeatherCheck = now; weatherLoading = true
        weatherExecutor.execute {
            runCatching { WeatherService.refresh(applicationContext, la, lo) }
            runOnUiThread {
                weatherLoading = false
                if (!isFinishing && !isDestroyed) {
                    weatherLine = WeatherService.cached(this, lat, lon)
                    refreshWatermarkPreview()
                }
            }
        }
    }

    override fun onPause() {
        weatherHandler.removeCallbacks(weatherTick)
        orientationListener.disable()
        super.onPause()
        prefs.edit().putFloat(KEY_ZOOM, zoomRatio).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationListener.disable()
        weatherHandler.removeCallbacks(weatherTick)
        weatherExecutor.shutdown()
        LocationHelper.stopTracking()
        cameraExecutor.shutdown()
        ioExecutor.shutdown()
    }

    /** 窗口尺寸变化只更新预览，拍摄控件不重建、不换位。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        previewUseCase?.targetRotation = currentDisplayRotation()
        template?.let { applyPreviewAnchor(it.anchor()) }
    }

    private fun currentDisplayRotation(): Int =
        binding.previewView.display?.rotation ?: Surface.ROTATION_0

    // ---------------- 权限与相机 ----------------

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (!LocationHelper.hasPermission(this)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (needed.isEmpty()) {
            startCamera()
            requestLocation()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCamera() {
        if (startingCamera || isFinishing || isDestroyed) return
        startingCamera = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            startingCamera = false
            if (isFinishing || isDestroyed) return@addListener
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: run {
                toast(R.string.camera_start_failed)
                return@addListener
            }

            // PreviewView 按固定界面方向显示；成片独立跟随手机物理方向。
            val rotation = currentDisplayRotation()

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val capture = ImageCapture.Builder()
                // 宁可少一点多帧合成，换取按下快门到可继续操作的延迟
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(captureRotation)
                .build()

            runCatching {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                )
                camera = cam
                imageCapture = capture
                previewUseCase = preview
                val restoredZoom = zoomRatio
                val zoomState = cam.cameraInfo.zoomState.value
                if (zoomState != null) {
                    cam.cameraControl.setZoomRatio(restoredZoom.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio))
                }

                // 变焦范围由设备决定（有的机器只到 4×，有的能到 10×）
                cam.cameraInfo.zoomState.observe(this) { zs ->
                    zoomMin = zs.minZoomRatio
                    zoomMax = zs.maxZoomRatio
                    zoomRatio = zs.zoomRatio
                    binding.zoomDial.setRange(zoomMin, zoomMax, zoomRatio)
                }
            }.onFailure { toast(R.string.camera_start_failed) }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- 变焦 ----------------

    private fun setupZoomGesture() {
        val detector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (zoomMax <= zoomMin) return false
                    val target = (zoomRatio * detector.scaleFactor).coerceIn(zoomMin, zoomMax)
                    camera?.cameraControl?.setZoomRatio(target)
                    // 捏合和推拉条共用同一个倍率，捏的时候条上的手柄要跟着走
                    binding.zoomDial.setRatio(target)
                    return true
                }
            }
        )

        // 双指捏合直接连续变焦，不用去摸推拉条
        binding.previewView.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        // 推拉条：拖到哪就是哪，点档位直接吸过去
        binding.zoomDial.onRatioChanged = { r ->
            camera?.cameraControl?.setZoomRatio(r)
        }
        binding.zoomDial.onRatioSettled = { r ->
            zoomRatio = r
            camera?.cameraControl?.setZoomRatio(r)
        }
    }

    // ---------------- 定位 ----------------

    private fun requestLocation() {
        if (!LocationHelper.hasPermission(this)) return
        // 持续跟踪：水印上的坐标要是快门那一刻的，桩号递增也依赖它
        LocationHelper.startTracking(this) { la, lo ->
            lat = la
            lon = lo
            requestWeather()
            refreshWatermarkPreview()

            // 反查地址要联网，别每两秒查一次：没地址或挪了 200m 以上才重新查
            val moved = if (geocodedLat.isNaN() || geocodedLon.isNaN()) Double.MAX_VALUE
            else Chainage.distanceMeters(geocodedLat, geocodedLon, la, lo)
            if (address.isNullOrBlank() || moved > 200.0) {
                geocodedLat = la
                geocodedLon = lo
                LocationHelper.reverseGeocode(this, la, lo) { addr ->
                    if (!addr.isNullOrBlank()) {
                        address = addr
                        refreshWatermarkPreview()
                    }
                }
            }
        }
    }

    // ---------------- 界面 ----------------

    private fun bindUi() {
        // 相机起来之前先给个占位范围，免得推拉条画不出来
        binding.zoomDial.setRange(1f, 8f, zoomRatio)
        binding.btnShutter.setOnClickListener { takePicture() }
        binding.btnSettings.setOnClickListener {
            val t = template ?: return@setOnClickListener
            StyleDialogs.watermark(this, t.watermarkStyle, t.scale(), t.showWeather) { style, scale, weather ->
                val changed = t.copy(watermarkStyle = style, watermarkScale = scale, showWeather = weather)
                val list = TemplateRepository.load(this).map { if (it.id == t.id) changed else it }.toMutableList()
                if (TemplateRepository.save(this, list)) {
                    template = changed; refreshWatermarkPreview(); requestWeather()
                    toast("设置已保存 · ${t.name}")
                } else toast(R.string.record_write_failed)
            }
        }
        binding.btnTemplates.setOnClickListener {
            startActivity(Intent(this, TemplateListActivity::class.java))
        }
        binding.btnRecords.setOnClickListener {
            startActivity(Intent(this, RecordListActivity::class.java))
        }
        binding.btnGallery.setOnClickListener { openGallery() }
    }

    private fun renderTemplateChips() {
        val row = binding.templateRow
        row.removeAllViews()
        val list = TemplateRepository.load(this)
        val currentId = template?.id

        list.forEach { t ->
            val chip = makeChip(t.name, t.id == currentId)
            chip.setOnClickListener {
                TemplateRepository.select(this, t.id)
                template = t
                category = null
                renderTemplateChips()
                renderStickyChips()
                renderCategoryChips()
                refreshWatermarkPreview()
            }
            row.addView(chip)
        }
    }

    private fun renderStickyChips() {
        val t = template ?: return
        val row = binding.stickyRow
        row.removeAllViews()

        t.activeSticky().forEach { f ->
            val v = stickyValue(t, f)
            val text = if (v.isBlank()) "${f.label} ＋" else "${f.label}: $v"
            val chip = makeChip(text, false)
            chip.setOnClickListener { editSticky(f) }
            row.addView(chip)
        }
    }

    private fun renderCategoryChips() {
        val t = template ?: return
        val row = binding.categoryRow
        row.removeAllViews()

        val cats = t.activeCategories()
        if (cats.isEmpty()) {
            category = null
            return
        }
        val remembered = TemplateRepository.selectedCategory(this, t.id)
        if (category == null || cats.none { it.id == category?.id }) {
            category = cats.firstOrNull { it.id == remembered } ?: cats.first()
        }

        cats.forEach { c ->
            val chip = makeChip(c.name, c.id == category?.id)
            chip.setOnClickListener {
                category = c
                TemplateRepository.selectCategory(this, t.id, c.id)
                renderCategoryChips()
                refreshWatermarkPreview()
            }
            row.addView(chip)
        }

        // 选中的可能排在最后（排水沟/截流沟），把它滑进可视区
        val index = cats.indexOfFirst { it.id == category?.id }
        val activeChip = if (index >= 0) row.getChildAt(index) else null
        activeChip?.post {
            binding.scrollCategory.smoothScrollTo(
                (activeChip.left - dp(12)).coerceAtLeast(0), 0
            )
        }
    }

    private fun makeChip(text: String, selected: Boolean): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.white))
        textSize = 13f
        val h = dp(12)
        val v = dp(6)
        setPadding(h, v, h, v)
        background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
        isSelected = selected
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
    }

    private fun editSticky(field: StickyField) {
        val t = template ?: return

        val input = EditText(this).apply {
            setText(stickyValue(t, field))
            isSingleLine = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(field.label)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.edit()
                    .putString(stickyKey(t, field), input.text.toString().trim())
                    .apply()
                renderStickyChips()
                refreshWatermarkPreview()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        // 打开即全选 + 弹键盘：改项目名/标段时直接输新值覆盖，不用先手动清空
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
            input.requestFocus()
            input.setSelection(0, input.text.length)
            input.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }
        dialog.show()
    }

    private fun refreshWatermarkPreview() {
        val t = template ?: return
        val meta = buildMeta(t, category, emptyMap(), System.currentTimeMillis())
        binding.watermarkPreview.text = WatermarkRenderer.previewText(meta)
        binding.watermarkPreview.textSize = 11f * t.scale()
        binding.watermarkPreview.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(WatermarkRenderer.parseColor(meta.style.background, android.graphics.Color.BLACK))
            alpha = meta.style.alpha(); cornerRadius = dp(6).toFloat()
        }
        applyPreviewAnchor(t.anchor())
    }

    /**
     * 取景页的预览块跟着模板的水印落点走。
     *
     * 位置只是近似：预览是铺满全屏的，成片是 4:3，两者比例并不一致。
     * 但"水印在上还是在下"这种粗粒度判断是一致的，够用。
     * 横屏时上下都给顶栏/底栏让位（那两条在横屏布局里矮得多）。
     */
    private var draggingPosition: WatermarkPosition? = null

    private fun previewArea(): WatermarkArea {
        val edge = dp(12).toFloat()
        val top = binding.topBar.bottom + edge
        val bottom = binding.bottomBar.top - edge
        return WatermarkArea(edge, top,
            (binding.root.width - binding.watermarkPreview.width - 2 * edge).coerceAtLeast(0f),
            (bottom - top - binding.watermarkPreview.height).coerceAtLeast(0f))
    }

    private fun applyPreviewAnchor(anchor: WatermarkAnchor) {
        val view = binding.watermarkPreview
        val point = (draggingPosition ?: template?.watermarkPosition ?: WatermarkPosition.fromAnchor(anchor)).bounded()
        val area = previewArea()
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val left = area.x(point).toInt()
        val top = area.y(point).toInt()
        val gravity = Gravity.TOP or Gravity.LEFT
        if (lp.gravity != gravity || lp.marginStart != 0 || lp.topMargin != 0 || lp.bottomMargin != 0) {
            lp.gravity = gravity
            lp.setMargins(0, 0, 0, 0)
            lp.marginStart = 0
            lp.marginEnd = 0
            view.layoutParams = lp
        }
        view.translationX = left.toFloat()
        view.translationY = top.toFloat()
    }

    private fun bindWatermarkDrag() {
        val view = binding.watermarkPreview
        var startX = 0f
        var startY = 0f
        var originX = 0f
        var originY = 0f
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        view.contentDescription = "水印预览，可拖动调整模板位置"
        view.setOnTouchListener { _, event ->
            val t = template ?: return@setOnTouchListener false
            if (busy) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = view.x; originY = view.y
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (draggingPosition != null || kotlin.math.hypot(dx, dy) >= slop) {
                        draggingPosition = previewArea().positionAt(originX + dx, originY + dy)
                        applyPreviewAnchor(t.anchor())
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        draggingPosition?.let { point ->
                            val updated = t.copy(watermarkPosition = point)
                            val templates = TemplateRepository.load(this).map { if (it.id == t.id) updated else it }.toMutableList()
                            if (TemplateRepository.save(this, templates)) template = updated
                            else toast(R.string.record_write_failed)
                        } ?: view.performClick()
                        draggingPosition = null
                        applyPreviewAnchor(t.anchor())
                    }
                }
                MotionEvent.ACTION_CANCEL -> { draggingPosition = null; applyPreviewAnchor(t.anchor()) }
            }
            true
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        runCatching { startActivity(intent) }
            .onFailure { toast("没有可用的相册应用") }
    }

    // ---------------- 桩号推算 ----------------

    private data class ChainagePlan(
        val prefill: String,
        val note: String,
        /** 本次推断的位移；null 表示没有可用位移 */
        val moveMeters: Double?
    )

    /**
     * 桩号怎么给：
     *  - 第一次：空着让用户手填
     *  - 位移 < 1.5m：还在同一个测点（只是换了个部位拍），沿用上次桩号
     *  - 1.5m ~ 300m：按 GPS 位移反算推进
     *  - 位移过大或没有定位：按模板里配的步长推进
     */
    private fun buildChainagePlan(): ChainagePlan {
        val t = template ?: return ChainagePlan("", "", null)
        if (!t.autoChainage) return ChainagePlan("", "", null)

        val st = TemplateRepository.chainageState(this, t.id)
            ?: return ChainagePlan("", getString(R.string.chainage_first), null)

        val curLat = capturedLat
        val curLon = capturedLon
        val move = if (curLat != null && curLon != null &&
            st.lat != null && st.lon != null
        ) {
            Chainage.distanceMeters(st.lat, st.lon, curLat, curLon)
        } else {
            null
        }

        val step = if (t.chainageStep > 0.5) t.chainageStep else 20.0

        fun advance(delta: Double, note: String, measured: Double?): ChainagePlan {
            val limit = t.advanceLimit()
            val next = Chainage.limitedAdvance(st.meters, delta, limit)
            val message = if (Chainage.advance(st.meters, delta) > next)
                "$note。本次推进已限制在 $limit 米以内。" else note
            return ChainagePlan(Chainage.format(next), message, measured)
        }
        return when {
            move == null || move > 300.0 -> {
                advance(step, getString(R.string.chainage_note_step, step), null)
            }
            move < 1.5 -> {
                ChainagePlan(
                    Chainage.format(st.meters),
                    getString(R.string.chainage_note_same, move),
                    0.0
                )
            }
            else -> {
                advance(move, getString(R.string.chainage_note_gps, move), move)
            }
        }
    }

    // ---------------- 拍摄主流程 ----------------

    private fun takePicture() {
        if (busy) return
        val capture = imageCapture ?: return
        val t = template ?: return
        val cat = category ?: t.activeCategories().firstOrNull()

        if (cat == null || cat.activeFields().isEmpty()) {
            toast(R.string.no_field)
            return
        }

        capture.targetRotation = captureRotation
        busy = true
        setShutterEnabled(false)

        // 按快门这一刻就把时间冻结，后面填多久都不影响水印时间
        capturedAt = System.currentTimeMillis()
        capturedLat = lat
        capturedLon = lon
        capturedAddress = address
        capturedWeather = if (t.showWeather) WeatherService.cached(this, lat, lon) ?: "天气：未获取" else null
        val file = File(cacheDir, "cap_$capturedAt.jpg")

        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) { file.delete(); return@runOnUiThread }
                    pendingDecode = ioExecutor.submit(Callable<Bitmap?> { ImageLoader.load(file) })
                    showForm(file, cat)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    busy = false
                    setShutterEnabled(true)
                    toast("拍摄失败：${exception.message}")
                }
            }
        })
    }

    private fun showForm(file: File, cat: MeasureCategory) = showFormNow(file, cat)

    private fun showFormNow(file: File, cat: MeasureCategory) {
        val t = template ?: return
        val plan = buildChainagePlan()
        pendingMove = plan.moveMeters

        val dialog = CaptureFormDialog(
            context = this,
            template = t,
            initialCategoryId = cat.id,
            lastValues = TemplateRepository.lastMeasureValues(this, t.id),
            chainagePrefill = plan.prefill,
            chainageNote = plan.note,
            lastChainage = if (t.autoChainage) TemplateRepository.chainageState(this, t.id)?.meters else null,
            onConfirmed = { categoryId, values ->
                val chosen = t.category(categoryId) ?: cat
                category = chosen
                TemplateRepository.selectCategory(this, t.id, chosen.id)
                renderCategoryChips()

                savePhoto(file, t, chosen, values)
            },
            onCancelled = {
                val abandoned = pendingDecode
                pendingDecode = null
                ioExecutor.execute {
                    runCatching { abandoned?.get()?.recycle() }
                    file.delete()
                }
                busy = false
                setShutterEnabled(true)
                toast("已放弃这张")
            }
        )
        dialog.showForm()
    }

    private fun savePhoto(
        source: File,
        t: CaptureTemplate,
        cat: MeasureCategory,
        values: Map<String, String>
    ) {
        val metaTime = capturedAt
        val move = pendingMove
        val meta = buildMeta(t, cat, values, metaTime).copy(
            lat = capturedLat, lon = capturedLon, address = capturedAddress, weather = capturedWeather
        )
        binding.savingPanel.visibility = View.VISIBLE

        ioExecutor.execute {
            val cached = runCatching {
                pendingDecode?.get(3, TimeUnit.SECONDS)
            }.getOrNull()
            pendingDecode = null

            val src = cached ?: ImageLoader.load(source)

            if (src == null) {
                source.delete()
                runOnUiThread {
                    binding.savingPanel.visibility = View.GONE
                    busy = false
                    setShutterEnabled(true)
                    toast("读取照片失败")
                }
                return@execute
            }

            val anchor = t.anchor()
            val wmScale = t.scale()
            val rendered = WatermarkRenderer.render(src, meta, anchor, wmScale, t.watermarkPosition)

            val outFile = File(cacheDir, "out_${System.currentTimeMillis()}.jpg")
            val compressed = runCatching {
                outFile.outputStream().use { out ->
                    rendered.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }.getOrDefault(false)

            if (compressed) writeExif(outFile, meta)

            val name = "MC_" + SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS", Locale.US
            ).format(Date(metaTime)) + ".jpg"

            val saved = compressed && PhotoSaver.save(this, outFile, name)

            var recorded = false
            if (saved) {
                // 留一份无水印原图：改了数值要能重画，烧进像素的水印抠不下来。
                // 存失败不挡保存——照片已经进相册了，只是这张以后不能改数。
                val keptOriginal = OriginalStore.save(this, source, name)
                val record = buildRecord(
                    t, cat, values, metaTime, name,
                    move, if (keptOriginal) name else null, anchor, wmScale
                )
                recorded = RecordRepository.append(this, record)
                if (recorded) {
                    updateChainageState(t, record)
                    val previous = TemplateRepository.lastMeasureValues(this, t.id).toMutableMap()
                    previous.putAll(values)
                    TemplateRepository.rememberMeasureValues(this, t.id, previous)
                }
                // 原图占地方，超上限就从最老的开始清
                runCatching { OriginalStore.trim(this, setOf(name)) }
            }

            runCatching { source.delete() }
            runCatching { outFile.delete() }
            if (rendered !== src) runCatching { rendered.recycle() }
            runCatching { src.recycle() }

            val chainText = chainageOf(cat, values)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.savingPanel.visibility = View.GONE
                busy = false
                setShutterEnabled(true)
                when {
                    !saved -> toast("保存失败")
                    !recorded -> toast(R.string.capture_record_write_failed)
                    chainText.isNotBlank() -> toast("已保存 · $chainText")
                    else -> toast(R.string.saved_toast)
                }
            }
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

    // ---------------- 数据组装 ----------------

    private fun buildMeta(
        t: CaptureTemplate,
        cat: MeasureCategory?,
        values: Map<String, String>,
        time: Long
    ): CaptureMeta {
        val stickyLines = t.activeSticky().mapNotNull { f ->
            val v = stickyValue(t, f).trim()
            if (v.isEmpty()) null else "${f.label}: $v"
        }

        val measureLines = cat?.activeFields()?.mapNotNull { f ->
            f.format(values[f.id].orEmpty()).trim().ifEmpty { null }
        } ?: emptyList()

        return CaptureMeta(
            titleLine = t.title,
            stickyLines = stickyLines,
            categoryName = cat?.name.orEmpty(),
            measureLines = measureLines,
            timeMillis = time,
            lat = lat,
            lon = lon,
            address = address,
            showTime = t.showTime,
            showAddress = t.showAddress,
            style = t.watermarkStyle ?: WatermarkStyle(),
            stickyFields = t.activeSticky().map { RecordField(it.label, values = listOf(stickyValue(t, it)), appearance = it.appearance) },
            measureFields = cat?.activeFields()?.map { RecordField(it.label, it.unit, it.splitValue(values[it.id].orEmpty()), appearance = it.appearance) },
            weather = if (t.showWeather) weatherLine ?: "天气：未获取" else null
        )
    }

    private fun chainageOf(cat: MeasureCategory, values: Map<String, String>): String {
        val f = cat.chainageField() ?: return ""
        return values[f.id].orEmpty().trim()
    }

    private fun buildRecord(
        t: CaptureTemplate,
        cat: MeasureCategory,
        values: Map<String, String>,
        time: Long,
        photoName: String,
        move: Double?,
        originalName: String?,
        anchor: WatermarkAnchor,
        wmScale: Float
    ): CaptureRecord {
        val stickyFields = t.activeSticky().map { f ->
            RecordField(label = f.label, unit = "", values = listOf(stickyValue(t, f).trim()), appearance = f.appearance)
        }
        val measureFields = cat.activeFields().map { f ->
            RecordField(
                label = f.label,
                unit = f.unit,
                values = f.splitValue(values[f.id].orEmpty()).map { it.trim() },
                // 记录自己记住哪行是桩号，之后编辑页不用回头查模板
                role = f.role.name,
                type = f.type.name,
                warningRange = f.warningRange,
                appearance = f.appearance
            )
        }
        val chainText = chainageOf(cat, values)
        return CaptureRecord(
            timeMillis = time,
            templateId = t.id,
            templateName = t.name,
            categoryName = cat.name,
            photoName = photoName,
            originalName = originalName,
            lat = capturedLat,
            lon = capturedLon,
            address = capturedAddress,
            chainage = chainText,
            chainageMeters = Chainage.parse(chainText),
            // 没有桩号的分类（比如钢丝网格）记位移没有意义
            moveMeters = if (cat.chainageField() != null) move else null,
            watermarkAnchor = anchor.key,
            watermarkPosition = t.watermarkPosition,
            watermarkScale = wmScale,
            titleLine = t.title,
            showTime = t.showTime,
            showAddress = t.showAddress,
            sticky = stickyFields,
            fields = measureFields,
            watermarkStyle = t.watermarkStyle,
            weather = capturedWeather
        )
    }

    /**
     * 保存成功后把桩号和当时的坐标记下来，作为下一次递增的基准。
     * 没拿到坐标也要记——否则工地上偶发丢星就再也续不上了，
     * 只是下一次会退回「按步长推算」。
     */
    private fun updateChainageState(t: CaptureTemplate, record: CaptureRecord) {
        val meters = record.chainageMeters ?: return
        TemplateRepository.rememberChainage(
            this, t.id,
            ChainageState(
                meters = meters,
                lat = record.lat,
                lon = record.lon,
                timeMillis = record.timeMillis
            )
        )
    }

    private fun stickyKey(t: CaptureTemplate, f: StickyField) = "sticky:${t.id}:${f.id}"

    private fun stickyValue(t: CaptureTemplate, f: StickyField): String =
        prefs.getString(stickyKey(t, f), f.value) ?: f.value

    // ---------------- 小工具 ----------------

    private fun setShutterEnabled(enabled: Boolean) {
        binding.btnShutter.isEnabled = enabled
        binding.btnShutter.alpha = if (enabled) 1f else 0.45f
        binding.btnSettings.isEnabled = enabled
        binding.btnTemplates.isEnabled = enabled
        binding.btnRecords.isEnabled = enabled
        binding.btnGallery.isEnabled = enabled
        listOf(binding.templateRow, binding.stickyRow, binding.categoryRow).forEach { row ->
            for (i in 0 until row.childCount) row.getChildAt(i).isEnabled = enabled
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_ZOOM = "zoom_ratio"
    }
}
