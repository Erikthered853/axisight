package com.etrsystems.axisight

import android.content.Context
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CsvLogger(private val ctx: Context) {
    private val df = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun exportOverlay(overlay: OverlayView, mmPerPx: Double?) {
        val now = df.format(Date())
        val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val file = File(dir, "axisight_$now.csv")
        val sb = StringBuilder()
        sb.appendLine("index,x_px,y_px")
        sb.appendLine("# mm_per_px=${mmPerPx ?: Double.NaN}")
        overlay.getPoints().forEachIndexed { index, p -> 
            sb.appendLine("$index,${p.first},${p.second}") 
        }
        file.writeText(sb.toString())
        Toast.makeText(ctx, "Exported ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}
