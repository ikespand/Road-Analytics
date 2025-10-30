package com.ikespand.roadanalytics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Location
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ikespand.roadanalytics.Constants.LABELS_PATH
import com.ikespand.roadanalytics.Constants.MODEL_PATH
import com.ikespand.roadanalytics.databinding.ActivityMainBinding
import com.ikespand.roadanalytics.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var csvLogger: CsvLogger
    private lateinit var locationHelper: LocationHelper

    @Volatile private var lastFrame: Bitmap? = null
    private var isRunning = false

    private val ACCURACY_OK_METERS = 30f
    private val AGE_OK_MILLIS = 15_000L

    private var lastSaveTs = 0L
    private val MIN_SAVE_INTERVAL_MS = 2000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        csvLogger = CsvLogger(this)
        locationHelper = LocationHelper(this)
        locationHelper.ensurePermission()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        }

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        bindUi()
        startGpsUpdates()
        showStartOverlay(true)
        setRunningUi(isRunning = false) // initial: Stop hidden, Share disabled
    }

    private fun bindUi() = with(binding) {
        isGpu?.setOnCheckedChangeListener { buttonView, isChecked ->
            cameraExecutor.submit { detector?.restart(isGpu = isChecked) }
            buttonView.setBackgroundColor(
                ContextCompat.getColor(baseContext, if (isChecked) R.color.orange else R.color.gray)
            )
        }

        btnShare?.setOnClickListener { shareDetections() }

        btnStart.setOnClickListener { startSession() }
        btnStop.setOnClickListener { stopSession() }
    }

    // ----- GPS -----

    private fun startGpsUpdates() {
        locationHelper.startUpdates { loc ->
            updateGpsStatusUi(loc)
            val ready = isGoodFix(loc)
            binding.btnStart.isEnabled = ready && !isRunning
        }
    }

    private fun stopGpsUpdates() {
        locationHelper.stopUpdates()
    }

    private fun isGoodFix(loc: Location?): Boolean {
        loc ?: return false
        val accOk = loc.hasAccuracy() && loc.accuracy <= ACCURACY_OK_METERS
        val ageOk = (System.currentTimeMillis() - loc.time) <= AGE_OK_MILLIS
        return accOk && ageOk
    }

    private fun updateGpsStatusUi(loc: Location?) = with(binding) {
        val status = if (isGoodFix(loc)) "GPS ready" else "Waiting for GPS fix…"
        val acc = loc?.let { if (it.hasAccuracy()) "${it.accuracy.toInt()}m" else "—" } ?: "—"
        val age = loc?.let { DateUtils.getRelativeTimeSpanString(it.time, System.currentTimeMillis(), 1000L) } ?: "—"
        txtGpsStatus.text = status
        txtGpsDetail.text = "Accuracy: $acc   Age: $age"
    }

    // ----- Session controls -----

    private fun startSession() {
        val fix = locationHelper.getLastFix()
        if (!isGoodFix(fix)) {
            Toast.makeText(this, "GPS not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        isRunning = true
        showStartOverlay(false)
        setRunningUi(isRunning = true)
        Toast.makeText(this, "Logging started", Toast.LENGTH_SHORT).show()
    }

    private fun stopSession() {
        isRunning = false
        setRunningUi(isRunning = false) // enables Share, hides Stop
        showStartOverlay(false)         // ⟵ keep overlay hidden so Share is clickable
        Toast.makeText(this, "Logging stopped", Toast.LENGTH_SHORT).show()
    }


    private fun setRunningUi(isRunning: Boolean) = with(binding) {
        // Stop button lives in bottom bar; Share should be disabled while running
        btnStop.visibility = if (isRunning) View.VISIBLE else View.GONE
        btnStop.isEnabled = isRunning

        btnShare.isEnabled = !isRunning
    }

    private fun showStartOverlay(show: Boolean) = with(binding) {
        startOverlay.visibility = if (show) View.VISIBLE else View.GONE
        // START enabled only with good GPS; we re-evaluate on each GPS update
        btnStart.isEnabled = isGoodFix(locationHelper.getLastFix()) && show
    }

    // ----- Camera -----

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview =  Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
            )

            lastFrame = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true)
            imageProxy.close()

            if (isRunning) detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // ----- Permissions / lifecycle -----

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (it[Manifest.permission.CAMERA] == true) startCamera() }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
        stopGpsUpdates()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) startCamera()
        else requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    // ----- Detector callbacks -----

    override fun onEmptyDetect() {
        runOnUiThread { binding.overlay.clear() }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.setResults(boundingBoxes)
            binding.overlay.invalidate()
        }

        if (!isRunning) return

        val now = System.currentTimeMillis()
        if (now - lastSaveTs < MIN_SAVE_INTERVAL_MS || boundingBoxes.isEmpty()) return
        lastSaveTs = now

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                delay(10)

                val previewBmp = lastFrame ?: return@launch

                val scaleX = if (binding.overlay.width > 0) previewBmp.width.toFloat() / binding.overlay.width else 1f
                val scaleY = if (binding.overlay.height > 0) previewBmp.height.toFloat() / binding.overlay.height else 1f

                val boxes: List<Box> = boundingBoxes.mapNotNull { b ->
                    val bb = extractBox(b) ?: return@mapNotNull null
                    val left   = (bb.left * scaleX)
                    val top    = (bb.top  * scaleY)
                    val right  = (bb.right * scaleX)
                    val bottom = (bb.bottom* scaleY)

                    val l = max(0f, min(previewBmp.width  - 1f, left)).toInt()
                    val t = max(0f, min(previewBmp.height - 1f, top)).toInt()
                    val r = max(0f, min(previewBmp.width  - 1f, right)).toInt()
                    val bt= max(0f, min(previewBmp.height - 1f, bottom)).toInt()

                    Box(
                        x = l,
                        y = t,
                        w = (r - l).coerceAtLeast(1),
                        h = (bt - t).coerceAtLeast(1),
                        label = reflectLabel(b) ?: "pothole",
                        conf = reflectConfidence(b) ?: 0f
                    )
                }

                val (fileName, _) = ImageSaver.saveToGallery(
                    context = this@MainActivity,
                    src = previewBmp,
                    boxes = boxes,
                    albumName = "PotholeDetections",
                    prefix = "pothole"
                )

                val loc = locationHelper.getLastFix()
                val top = boundingBoxes.first()

                csvLogger.append(
                    DetectionRecord(
                        timestampMs = now,
                        label = reflectLabel(top) ?: "pothole",
                        confidence = reflectConfidence(top) ?: 0f,
                        x = -1, y = -1, w = -1, h = -1,
                        latitude = loc?.latitude, longitude = loc?.longitude,
                        imageName = fileName
                    )
                )

            } catch (t: Throwable) {
                Log.e(TAG, "Failed to save detection", t)
            }
        }
    }

    // ----- Reflection helpers -----

    private fun reflectLabel(any: Any): String? = runCatching {
        val k = any::class
        listOf("clsName","label","className","name","title")
            .firstNotNullOfOrNull { p -> k.members.firstOrNull { it.name == p }?.call(any) as? String }
    }.getOrNull()

    private fun reflectConfidence(any: Any): Float? = runCatching {
        val k = any::class
        listOf("prob","confidence","score")
            .firstNotNullOfOrNull { p ->
                k.members.firstOrNull { it.name == p }?.call(any)?.let {
                    when (it) {
                        is Number -> it.toFloat()
                        is String -> it.toFloatOrNull()
                        else -> null
                    }
                }
            }
    }.getOrNull()

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun extractBox(any: Any): Bounds? = runCatching {
        val k = any::class

        k.members.firstOrNull { it.name == "rect" }?.call(any)?.let { r ->
            return@runCatching when (r) {
                is android.graphics.RectF -> Bounds(r.left, r.top, r.right, r.bottom)
                is android.graphics.Rect  -> Bounds(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                else -> null
            }
        }

        fun num(name: String): Float? =
            k.members.firstOrNull { it.name == name }?.call(any)?.let { v ->
                when (v) {
                    is Number -> v.toFloat()
                    is String -> v.toFloatOrNull()
                    else -> null
                }
            }
        val left  = num("x1") ?: num("left")
        val top   = num("y1") ?: num("top")
        val right = num("x2") ?: num("right")
        val bottom= num("y2") ?: num("bottom")
        if (left != null && top != null && right != null && bottom != null) {
            return@runCatching Bounds(left, top, right, bottom)
        }

        val cx = num("cx") ?: num("centerX")
        val cy = num("cy") ?: num("centerY")
        val w  = num("w") ?: num("width")
        val h  = num("h") ?: num("height")
        if (cx != null && cy != null && w != null && h != null) {
            val l = cx - w/2f
            val t = cy - h/2f
            return@runCatching Bounds(l, t, l + w, t + h)
        }

        null
    }.getOrNull()

    // ----- Share CSV -----

    private fun shareDetections() {
        val csv: File = csvLogger.csvFile
        if (!csv.exists()) {
            Toast.makeText(this, "No detections yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", csv)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_SUBJECT, "Pothole detections")
            }
            startActivity(Intent.createChooser(intent, "Share detections"))
        } catch (e: Exception) {
            Log.e("Camera", "Share failed", e)
            Toast.makeText(this, "Unable to share file", Toast.LENGTH_SHORT).show()
        }
    }
}
