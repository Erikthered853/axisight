package com.etrsystems.axisight

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val pts = ArrayDeque<Pair<Float, Float>>()
    var maxPoints = 240
    var mmPerPx: Double? = null

    var showSimDot = false
    var simX = 0f
    var simY = 0f

    private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val paintPts = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.FILL
    }
    private val paintFit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f
    }
    private val paintSim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.FILL
    }

    fun clearPoints() { pts.clear(); invalidate() }

    fun addPoint(px: Float, py: Float) {
        if (pts.size >= maxPoints) pts.removeFirst()
        pts.addLast(px to py)
        invalidate()
    }

    fun getPoints(): List<Pair<Float, Float>> {
        return pts.toList()
    }

    fun setSimDot(x: Float, y: Float) {
        simX = x; simY = y; showSimDot = true; invalidate()
    }

    fun hideSimDot() { showSimDot = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width; val h = height
        val cx = w / 2f; val cy = h / 2f

        canvas.drawLine(cx - 40, cy, cx + 40, cy, paintCross)
        canvas.drawLine(cx, cy - 40, cx, cy + 40, paintCross)

        if (showSimDot) {
            canvas.drawCircle(simX, simY, 8f, paintSim)
        }

        for ((x, y) in pts) {
            canvas.drawCircle(x, y, 4f, paintPts)
        }

        if (pts.size >= 3) {
            val dpts = pts.map { it.first.toDouble() to it.second.toDouble() }
            val res = CircleFit.fit(dpts)
            if (res != null) {
                val ccx = res.cx.toFloat(); val ccy = res.cy.toFloat()
                val rr = res.r.toFloat()
                canvas.drawCircle(ccx, ccy, rr, paintFit)
                canvas.drawCircle(ccx, ccy, 5f, paintFit)
                val mm = mmPerPx
                val text = if (mm != null)
                    "r=%.1fpx(%.3fmm)  rms=%.2fpx(%.3fmm)".format(rr, rr*mm, res.rms, res.rms*mm)
                else
                    "r=%.1fpx  rms=%.2fpx".format(rr, res.rms)
                canvas.drawText(text, 20f, h - 24f, paintText)
            }
        }
    }
}
