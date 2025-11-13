# AxiSight App - Fix Instructions

This document provides a step-by-step guide to fix the build issues and properly implement USB camera support in the AxiSight app.

## 1. The Problem

The application has been failing to build due to issues with the USB camera libraries that were previously tried. The project has been reverted to a stable state, and this guide will walk you through the correct implementation using a reliable and well-supported library.

## 2. The Fix

Follow these steps to get your project working.

### Step 1: Clean Up Unused Files

The previous attempts to add USB camera support left behind some now-unused files. Please delete the following:

*   `app/src/main/res/layout/fragment_usb_camera.xml`
*   `app/src/main/res/xml/device_filter.xml`

### Step 2: Update `app/build.gradle`

I see you've already added the correct dependency. Please ensure your `app/build.gradle` file contains the following `dependencies` block:


'''groovy
dependencies {
    implementation "org.jetbrains.kotlin:kotlin-stdlib:2.2.0"
    implementation "androidx.core:core-ktx:1.17.0"
    implementation "androidx.appcompat:appcompat:1.7.1"
    implementation "com.google.android.material:material:1.13.0"

    def camerax_version = "1.5.1"
    implementation "androidx.camera:camera-core:$camerax_version"
    implementation "androidx.camera:camera-camera2:$camerax_version"
    implementation "androidx.camera:camera-lifecycle:$camerax_version"
    implementation "androidx.camera:camera-view:$camerax_version"


    // USB Camera
    implementation 'com.serenegiant:libuvc:3.4.2'


    // ExoPlayer for WiFi camera streaming
    implementation 'androidx.media3:media3-exoplayer:1.8.0'
    implementation 'androidx.media3:media3-exoplayer-rtsp:1.8.0'


}
'''



### Step 3: Sync Your Project

After confirming the dependency is correct, sync your project with the Gradle files. This will download the `libuvc` library and make it available to your project.

### Step 4: Update `MainActivity.kt`

Replace the entire contents of your `MainActivity.kt` file with the following code. This new version correctly implements the `libuvc` library for USB camera support.


'''kotlin
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
'''


### Step 5: Update your `activity_main.xml`

Ensure your `activity_main.xml` is up-to-date with the following content. This version removes the unnecessary `FragmentContainerView` and makes sure the `TextureView` is available for both the WiFi and USB cameras.


'''xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <androidx.camera.view.PreviewView
        android:id="@+id/previewView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:implementationMode="performance" />

    <TextureView
        android:id="@+id/textureView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />

    <com.etrsystems.axisight.OverlayView
        android:id="@+id/overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Top controls -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="8dp"
        android:background="#66000000">

        <RadioGroup
            android:id="@+id/rgCameraSource"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <RadioButton
                android:id="@+id/rbInternal"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/internal"
                android:textColor="@android:color/white"
                android:checked="true"/>

            <RadioButton
                android:id="@+id/rbUsb"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/usb"
                android:textColor="@android:color/white"/>

            <RadioButton
                android:id="@+id/rbWifi"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/wifi"
                android:textColor="@android:color/white"/>
        </RadioGroup>

        <LinearLayout
            android:id="@+id/wifiGroup"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:visibility="gone">

            <EditText
                android:id="@+id/edWifiUrl"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:hint="@string/wifi_url_hint"
                android:inputType="textUri"/>

            <Button
                android:id="@+id/btnWifiConnect"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/connect"/>
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/switchSim"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/sim"
                android:textColor="@android:color/white"
                android:layout_marginEnd="8dp"/>

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/switchAuto"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/auto"
                android:textColor="@android:color/white"
                android:checked="true"
                android:layout_marginEnd="8dp"/>

            <Button
                android:id="@+id/btnCal"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/calibrate"
                android:layout_marginEnd="8dp"/>

            <Button
                android:id="@+id/btnExport"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/export_csv" />
        </LinearLayout>
    </LinearLayout>

    <!-- Bottom tuning panel -->
    <LinearLayout
        android:id="@+id/tuningPanel"
        android:layout_gravity="bottom"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:background="#66000000"
        android:padding="8dp">

        <TextView
            android:id="@+id/txtParams"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@android:color/white"
            android:text="minA 200  maxA 8000  circ≥0.5  kStd 1.0  mm/px unset" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <TextView android:text="minA" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="@android:color/white"/>
            <SeekBar
                android:id="@+id/seekMinArea"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:max="20000"
                android:progress="200" />

            <TextView android:text="maxA" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="@android:color/white"/>
            <SeekBar
                android:id="@+id/seekMaxArea"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:max="50000"
                android:progress="8000" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">
            <TextView android:text="circ" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="@android:color/white"/>
            <SeekBar
                android:id="@+id/seekCirc"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:max="100"
                android:progress="50" />
            <TextView android:text="kStd" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="@android:color/white"/>
            <SeekBar
                android:id="@+id/seekKstd"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:max="300"
                android:progress="100" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">
            <TextView android:text="mm/px" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="@android:color/white"/>
            <EditText
                android:id="@+id/edMmPerPx"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:hint="e.g. 0.02" 
                android:inputType="numberDecimal" />
            <TextView android:text="known mm" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="@android:color/white"/>
            <EditText
                android:id="@+id/edKnownMm"
                android:layout_width="80dp"
                android:layout_height="wrap_content"
                android:hint="10"
                android:inputType="numberDecimal" />
        </LinearLayout>
    </LinearLayout>
</FrameLayout>
'''


### Step 6: Create `device_filter.xml`

Create a new file at `app/src/main/res/xml/device_filter.xml` and add the following content. This file is required by the USB library to identify which devices to connect to.


'''xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Allow any USB device to be attached -->
    <usb-device />
</resources>
'''


### Step 7: Build and Run

Your project should now build successfully. You can now run the app on your device and test the internal, USB, and WiFi camera sources.

## `GEMINI.md` Update

Finally, here's the updated content for your `GEMINI.md` file. It now accurately reflects the project's state.


'''markdown
# Gemini Project Analysis: AxiSight

## Project Overview

This repository contains the AxiSight Android App. Based on the `AndroidManifest.xml` and `app/build.gradle` files, this app's primary function involves using a camera for alignment and measurement tasks.

It supports three camera sources:
1.  **Internal Camera:** Uses the device's built-in camera via Android CameraX.
2.  **USB Camera:** Directly connects to a UVC-compatible USB camera using the `com.serenegiant:libuvc` library.
3.  **Wi-Fi Camera:** Connects to a network video stream (e.g., RTSP) using ExoPlayer.

The application is written in Kotlin.

## Building and Running

This is a standard Android Gradle project.

**Build:**

To build the application from the command line, use the Gradle wrapper:

'''bash
./gradlew build
'''

**Assemble a Debug APK:**

'''bash
./gradlew assembleDebug
'''

The output APK can be found in `app/build/outputs/apk/debug/`.

**Run:**

The application can be run directly from Android Studio or installed on a device/emulator using `adb`:

'''bash
adb install app/build/outputs/apk/debug/app-debug.apk
'''

## Development Conventions

*   **Language:** Kotlin
*   **Java Version:** 17
*   **Build System:** Gradle
*   **Min SDK:** 26
*   **Target SDK:** 36
'''
