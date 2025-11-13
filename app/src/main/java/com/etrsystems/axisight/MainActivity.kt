package com.etrsystems.axisight

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.etrsystems.axisight.databinding.ActivityMainBinding
import com.serenegiant.usb.DeviceFilter
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@UnstableApi
class MainActivity : ComponentActivity(), TextureView.SurfaceTextureListener {

    private lateinit var b: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var exoPlayer: ExoPlayer? = null
    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null

    private enum class CameraSource { INTERNAL, USB, WIFI }

    private var cameraSource = CameraSource.INTERNAL

    private var simulate = false
    private var autoDetect = true
    private var mmPerPx: Double? = null
    private var knownMm: Double = 10.0

    private val cfg = DetectorConfig()

    private var calMode = false
    private var calP1: Pair<Float, Float>? = null

    private var simAngle = 0.0
    private var simRadiusPx = 200f

    private var csvLogger: CsvLogger? = null

    private val camPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        csvLogger = CsvLogger(this)

        b.rgCameraSource.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbInternal -> {
                    cameraSource = CameraSource.INTERNAL
                    b.wifiGroup.visibility = View.GONE
                    stopWifiCamera()
                    stopUsbCamera()
                    startCamera()
                }
                R.id.rbUsb -> {
                    cameraSource = CameraSource.USB
                    b.wifiGroup.visibility = View.GONE
                    stopCamera()
                    stopWifiCamera()
                    startUsbCamera()
                }
                R.id.rbWifi -> {
                    cameraSource = CameraSource.WIFI
                    b.wifiGroup.visibility = View.VISIBLE
                    stopCamera()
                    stopUsbCamera()
                }
            }
        }

        b.btnWifiConnect.setOnClickListener {
            val url = b.edWifiUrl.text.toString()
            if (url.isNotEmpty()) {
                startWifiCamera(url)
            }
        }

        b.switchSim.setOnCheckedChangeListener { _, checked ->
            simulate = checked
            if (simulate) {
                stopCamera()
                stopWifiCamera()
                stopUsbCamera()
                b.overlay.clearPoints()
                b.overlay.hideSimDot()
                b.previewView.visibility = View.INVISIBLE
                b.textureView.visibility = View.GONE
                b.previewView.post(simTick)
            } else {
                b.previewView.visibility = View.VISIBLE
                b.overlay.hideSimDot()
                b.overlay.clearPoints()
                when (cameraSource) {
                    CameraSource.INTERNAL -> startCamera()
                    CameraSource.USB -> startUsbCamera()
                    CameraSource.WIFI -> {
                        val url = b.edWifiUrl.text.toString()
                        if (url.isNotEmpty()) {
                            startWifiCamera(url)
                        }
                    }
                }
            }
        }
        b.switchAuto.setOnCheckedChangeListener { _, checked -> autoDetect = checked }

        b.btnCal.setOnClickListener { calMode = true; calP1 = null }
        b.btnExport.setOnClickListener { csvLogger?.exportOverlay(b.overlay, mmPerPx) }

        fun updateParamsText() {
            val mmText = mmPerPx?.let { String.format(Locale.US, "%.4f", it) } ?: getString(R.string.unset)
            b.txtParams.text = getString(
                R.string.params_text_format,
                cfg.minAreaPx,
                cfg.maxAreaPx,
                cfg.minCircularity,
                cfg.kStd,
                mmText
            )
        }
        b.seekMinArea.setOnSeekBarChangeListener(SimpleSeek { p ->
            cfg.minAreaPx = max(1, p); updateParamsText()
        })
        b.seekMaxArea.setOnSeekBarChangeListener(SimpleSeek { p ->
            cfg.maxAreaPx = max(cfg.minAreaPx + 1, p); updateParamsText()
        })
        b.seekCirc.setOnSeekBarChangeListener(SimpleSeek { p ->
            cfg.minCircularity = (p / 100.0).coerceIn(0.0, 1.0); updateParamsText()
        })
        b.seekKstd.setOnSeekBarChangeListener(SimpleSeek { p -> cfg.kStd = p / 100.0; updateParamsText() })
        updateParamsText()

        b.edMmPerPx.setOnEditorActionListener { v, _, _ ->
            mmPerPx = v.text?.toString()?.trim()?.toDoubleOrNull()
            b.overlay.mmPerPx = mmPerPx
            updateParamsText()
            true
        }
        b.edKnownMm.setText(getString(R.string.default_known_mm))
        b.edKnownMm.setOnEditorActionListener { v, _, _ ->
            knownMm = v.text?.toString()?.toDoubleOrNull() ?: 10.0
            true
        }

        b.overlay.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                v.performClick()
                val x = ev.x
                val y = ev.y
                if (calMode) {
                    if (calP1 == null) calP1 = x to y
                    else {
                        val p1 = calP1!!
                        val dp = hypot((x - p1.first).toDouble(), (y - p1.second).toDouble())
                        if (dp > 0.0) {
                            mmPerPx = knownMm / dp
                            b.overlay.mmPerPx = mmPerPx
                            updateParamsText()
                        }
                        calP1 = null; calMode = false
                    }
                } else if (!autoDetect || simulate) {
                    b.overlay.addPoint(x, y)
                }
            }
            true
        }

        if (!simulate) ensureCameraPermission { startCamera() }

        usbMonitor = USBMonitor(this, onDeviceConnectListener)
    }

    override fun onResume() {
        super.onResume()
        if (cameraSource == CameraSource.USB) {
            usbMonitor?.register()
        }
    }

    override fun onPause() {
        super.onPause()
        usbMonitor?.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        stopWifiCamera()
        stopUsbCamera()
        usbMonitor?.destroy()
    }

    private val onDeviceConnectListener: USBMonitor.OnDeviceConnectListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            if (cameraSource == CameraSource.USB) {
                val filters = DeviceFilter.getDeviceFilters(this@MainActivity, R.xml.device_filter)
                val filtered = filters.firstOrNull { it.matches(device) }
                if (filtered != null) {
                    usbMonitor?.requestPermission(device)
                }
            }
        }

        override fun onDettach(device: UsbDevice?) {
            if (cameraSource == CameraSource.USB && uvcCamera?.device == device) {
                uvcCamera?.destroy()
                uvcCamera = null
            }
        }

        override fun onConnect(
            device: UsbDevice?,
            ctrlBlock: USBMonitor.UsbControlBlock?,
            createNew: Boolean
        ) {
            if (cameraSource == CameraSource.USB) {
                uvcCamera = UVCCamera()
                uvcCamera?.open(ctrlBlock)
                uvcCamera?.setPreviewSize(640, 480, UVCCamera.DEFAULT_PREVIEW_MODE)
                val surfaceTexture = b.textureView.surfaceTexture
                if (surfaceTexture != null) {
                    uvcCamera?.setPreviewDisplay(Surface(surfaceTexture))
                    uvcCamera?.startPreview()
                }
            }
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            if (cameraSource == CameraSource.USB && uvcCamera?.device == device) {
                uvcCamera?.close()
                uvcCamera = null
            }
        }

        override fun onCancel(device: UsbDevice?) {}
    }

    private fun ensureCameraPermission(onGranted: () -> Unit) {
        val ok = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (ok) onGranted() else camPerm.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        b.previewView.visibility = View.VISIBLE
        b.textureView.visibility = View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalyzer = null
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().apply {
            surfaceProvider = b.previewView.surfaceProvider
        }

        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                    try {
                        if (autoDetect && !simulate && cameraSource == CameraSource.INTERNAL) {
                            val center = BlobDetector.detectDarkDotCenter(image, cfg)
                            if (center != null) b.overlay.addPoint(center.first, center.second)
                        }
                    } finally {
                        image.close()
                    }
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        provider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
    }

    private fun startWifiCamera(url: String) {
        b.previewView.visibility = View.GONE
        b.textureView.visibility = View.VISIBLE
        b.textureView.surfaceTextureListener = this

        exoPlayer = ExoPlayer.Builder(this).build()
        b.textureView.surfaceTexture?.let {
            exoPlayer?.setVideoSurface(Surface(it))
        }

        val mediaSource = RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(url))
        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.prepare()
        exoPlayer?.play()
    }

    private fun stopWifiCamera() {
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun startUsbCamera() {
        b.previewView.visibility = View.GONE
        b.textureView.visibility = View.VISIBLE
        b.textureView.surfaceTextureListener = this
        usbMonitor?.register()
    }

    private fun stopUsbCamera() {
        usbMonitor?.unregister()
        uvcCamera?.close()
        uvcCamera = null
    }

    private val simTick = object : Runnable {
        override fun run() {
            if (!simulate) return
            val w = b.overlay.width.coerceAtLeast(1)
            val h = b.overlay.height.coerceAtLeast(1)
            val cx = w / 2f
            val cy = h / 2f
            simRadiusPx = min(w, h) * 0.3f
            simAngle += 0.07
            val x = cx + simRadiusPx * cos(simAngle).toFloat()
            val y = cy + simRadiusPx * sin(simAngle).toFloat()
            b.overlay.setSimDot(x, y)
            if (autoDetect) b.overlay.addPoint(x, y)
            b.previewView.postDelayed(this, 16L)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        when (cameraSource) {
            CameraSource.WIFI -> exoPlayer?.setVideoSurface(Surface(surface))
            CameraSource.USB -> {
                uvcCamera?.setPreviewDisplay(Surface(surface))
                uvcCamera?.startPreview()
            }
            else -> {}
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        when (cameraSource) {
            CameraSource.WIFI -> exoPlayer?.clearVideoSurface()
            CameraSource.USB -> uvcCamera?.stopPreview()
            else -> {}
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if ((cameraSource == CameraSource.WIFI || cameraSource == CameraSource.USB) && autoDetect) {
            val bmp = b.textureView.bitmap ?: return
            val center = BlobDetector.detectDarkDotCenter(bmp, cfg)
            if (center != null) b.overlay.addPoint(center.first, center.second)
        }
    }
}

private class SimpleSeek(val on: (Int) -> Unit) : android.widget.SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
        on(progress)
    }

    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
}
