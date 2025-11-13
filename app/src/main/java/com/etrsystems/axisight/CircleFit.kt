package com.etrsystems.axisight

import kotlin.math.sqrt

object CircleFit {
    data class Result(val cx: Double, val cy: Double, val r: Double, val rms: Double)

    fun fit(points: List<Pair<Double, Double>>): Result? {
        if (points.size < 3) return null
        val n = points.size

        var a11 = 0.0; var a12 = 0.0; var a13 = 0.0
        var a22 = 0.0; var a23 = 0.0
        val a33 = n.toDouble()
        var b1 = 0.0; var b2 = 0.0; var b3 = 0.0

        for ((x, y) in points) {
            val ax = 2.0 * x
            val ay = 2.0 * y
            val rhs = x * x + y * y
            a11 += ax * ax
            a12 += ax * ay
            a13 += ax
            a22 += ay * ay
            a23 += ay
            b1 += ax * rhs
            b2 += ay * rhs
            b3 += rhs
        }

        val A = arrayOf(
            doubleArrayOf(a11, a12, a13),
            doubleArrayOf(a12, a22, a23),
            doubleArrayOf(a13, a23, a33)
        )
        val b = doubleArrayOf(b1, b2, b3)
        val sol = solve3x3(A, b) ?: return null
        val cx = sol[0]; val cy = sol[1]; val c = sol[2]
        val r = sqrt(c + cx*cx + cy*cy)

        var se = 0.0
        for ((x, y) in points) {
            val d = sqrt((x - cx)*(x - cx) + (y - cy)*(y - cy))
            se += (d - r) * (d - r)
        }
        val rms = sqrt(se / n)
        return Result(cx, cy, r, rms)
    }

    private fun solve3x3(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val M = Array(3){ DoubleArray(4) }
        for (i in 0..2) { for (j in 0..2) M[i][j] = A[i][j]; M[i][3] = b[i] }
        for (col in 0..2) {
            var pivot = col
            var maxAbs = kotlin.math.abs(M[col][col])
            for (row in col+1..2) {
                val v = kotlin.math.abs(M[row][col])
                if (v > maxAbs) { maxAbs = v; pivot = row }
            }
            if (maxAbs < 1e-12) return null
            if (pivot != col) { val tmp = M[pivot]; M[pivot] = M[col]; M[col] = tmp }
            val p = M[col][col]
            for (j in col..3) M[col][j] /= p
            for (row in 0..2) if (row != col) {
                val f = M[row][col]
                for (j in col..3) M[row][j] -= f * M[col][j]
            }
        }
        return doubleArrayOf(M[0][3], M[1][3], M[2][3])
    }
}
