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
