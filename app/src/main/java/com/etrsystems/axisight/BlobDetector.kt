package com.etrsystems.axisight

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.*
import java.nio.ByteBuffer

object BlobDetector {
    fun detectDarkDotCenter(image: ImageProxy, cfg: DetectorConfig): Pair<Float, Float>? {
        val yPlane = image.planes[0]
        val w = image.width
        val h = image.height
        val rs = yPlane.rowStride
        val ps = yPlane.pixelStride
        val bb: ByteBuffer = yPlane.buffer
        val ds = cfg.downscale
        val dw = w / ds
        val dh = h / ds
        if (dw <= 0 || dh <= 0) return null

        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (j in 0 until dh) {
            val sy = j * ds
            for (i in 0 until dw) {
                val sx = i * ds
                val idx = sy * rs + sx * ps
                val v = bb.get(idx).toInt() and 0xFF
                sum += v
                sum2 += v.toDouble() * v
                n++
            }
        }
        if (n == 0) return null
        val mean = sum / n
        val variance = max(0.0, (sum2 / n) - mean * mean)
        val std = sqrt(variance)
        val thr = (mean - cfg.kStd * std).coerceIn(0.0, 255.0)

        var count = 0
        var sxAcc = 0.0; var syAcc = 0.0
        for (j in 0 until dh) {
            val sy0 = j * ds
            for (i in 0 until dw) {
                val sx0 = i * ds
                val idx = sy0 * rs + sx0 * ps
                val v = bb.get(idx).toInt() and 0xFF
                if (v <= thr) {
                    count++
                    sxAcc += i.toDouble()
                    syAcc += j.toDouble()
                }
            }
        }
        if (count == 0) return null

        val fullArea = count * ds * ds
        if (fullArea < cfg.minAreaPx || fullArea > cfg.maxAreaPx) return null

        val cxD = sxAcc / count
        val cyD = syAcc / count

        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (j in 0 until dh) {
            val sy0 = j * ds
            for (i in 0 until dw) {
                val sx0 = i * ds
                val idx = sy0 * rs + sx0 * ps
                val v = bb.get(idx).toInt() and 0xFF
                if (v <= thr) {
                    val dx = i - cxD
                    val dy = j - cyD
                    sxx += dx * dx
                    syy += dy * dy
                    sxy += dx * dy
                }
            }
        }
        sxx /= count; syy /= count; sxy /= count
        val trace = sxx + syy
        val det = sxx * syy - sxy * sxy
        val root = max(0.0, trace * trace / 4.0 - det)
        val l1 = trace / 2.0 + sqrt(root)
        val l2 = trace / 2.0 - sqrt(root)
        val axisRatio = if (l1 > 1e-9) max(0.0, min(1.0, l2 / l1)) else 0.0
        val circularityEstimate = sqrt(axisRatio)

        if (circularityEstimate < cfg.minCircularity) return null

        val cx = (cxD * ds).toFloat()
        val cy = (cyD * ds).toFloat()
        return Pair(cx, cy)
    }

    fun detectDarkDotCenter(bitmap: Bitmap, cfg: DetectorConfig): Pair<Float, Float>? {
        val w = bitmap.width
        val h = bitmap.height
        val ds = cfg.downscale
        val dw = w / ds
        val dh = h / ds
        if (dw <= 0 || dh <= 0) return null

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (j in 0 until dh) {
            for (i in 0 until dw) {
                val pixel = pixels[j * ds * w + i * ds]
                val v = (pixel shr 16 and 0xFF) * 0.299 + (pixel shr 8 and 0xFF) * 0.587 + (pixel and 0xFF) * 0.114
                sum += v
                sum2 += v * v
                n++
            }
        }
        if (n == 0) return null
        val mean = sum / n
        val variance = max(0.0, (sum2 / n) - mean * mean)
        val std = sqrt(variance)
        val thr = (mean - cfg.kStd * std).coerceIn(0.0, 255.0)

        var count = 0
        var sxAcc = 0.0; var syAcc = 0.0
        for (j in 0 until dh) {
            for (i in 0 until dw) {
                val pixel = pixels[j * ds * w + i * ds]
                val v = (pixel shr 16 and 0xFF) * 0.299 + (pixel shr 8 and 0xFF) * 0.587 + (pixel and 0xFF) * 0.114
                if (v <= thr) {
                    count++
                    sxAcc += i.toDouble()
                    syAcc += j.toDouble()
                }
            }
        }
        if (count == 0) return null

        val fullArea = count * ds * ds
        if (fullArea < cfg.minAreaPx || fullArea > cfg.maxAreaPx) return null

        val cxD = sxAcc / count
        val cyD = syAcc / count

        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (j in 0 until dh) {
            for (i in 0 until dw) {
                val pixel = pixels[j * ds * w + i * ds]
                val v = (pixel shr 16 and 0xFF) * 0.299 + (pixel shr 8 and 0xFF) * 0.587 + (pixel and 0xFF) * 0.114
                if (v <= thr) {
                    val dx = i - cxD
                    val dy = j - cyD
                    sxx += dx * dx
                    syy += dy * dy
                    sxy += dx * dy
                }
            }
        }
        sxx /= count; syy /= count; sxy /= count
        val trace = sxx + syy
        val det = sxx * syy - sxy * sxy
        val root = max(0.0, trace * trace / 4.0 - det)
        val l1 = trace / 2.0 + sqrt(root)
        val l2 = trace / 2.0 - sqrt(root)
        val axisRatio = if (l1 > 1e-9) max(0.0, min(1.0, l2 / l1)) else 0.0
        val circularityEstimate = sqrt(axisRatio)

        if (circularityEstimate < cfg.minCircularity) return null

        val cx = (cxD * ds).toFloat()
        val cy = (cyD * ds).toFloat()
        return Pair(cx, cy)
    }
}
