package gpes.core.estimator

/** Minimal dense row-major matrix for small filters. Not a general linear-algebra library. */
class Mat(val rows: Int, val cols: Int, val a: DoubleArray = DoubleArray(rows * cols)) {
    operator fun get(r: Int, c: Int) = a[r * cols + c]
    operator fun set(r: Int, c: Int, v: Double) { a[r * cols + c] = v }

    fun copy() = Mat(rows, cols, a.copyOf())

    operator fun times(o: Mat): Mat {
        require(cols == o.rows)
        val out = Mat(rows, o.cols)
        for (i in 0 until rows) for (k in 0 until cols) {
            val v = this[i, k]
            if (v == 0.0) continue
            for (j in 0 until o.cols) out.a[i * o.cols + j] += v * o[k, j]
        }
        return out
    }

    operator fun plus(o: Mat) = Mat(rows, cols, DoubleArray(a.size) { a[it] + o.a[it] })
    operator fun minus(o: Mat) = Mat(rows, cols, DoubleArray(a.size) { a[it] - o.a[it] })

    fun t(): Mat {
        val out = Mat(cols, rows)
        for (i in 0 until rows) for (j in 0 until cols) out[j, i] = this[i, j]
        return out
    }

    /** Symmetrize in place, to fight numerical drift in covariance matrices. */
    fun symmetrize(): Mat {
        for (i in 0 until rows) for (j in i + 1 until cols) {
            val v = 0.5 * (this[i, j] + this[j, i]); this[i, j] = v; this[j, i] = v
        }
        return this
    }

    /** Inverse via Gauss-Jordan with partial pivoting (small matrices only). */
    fun inv(): Mat {
        require(rows == cols)
        val n = rows
        val m = copy()
        val inv = identity(n)
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (kotlin.math.abs(m[r, c]) > kotlin.math.abs(m[p, c])) p = r
            require(kotlin.math.abs(m[p, c]) > 1e-15) { "singular matrix" }
            if (p != c) { m.swapRows(p, c); inv.swapRows(p, c) }
            val d = m[c, c]
            for (j in 0 until n) { m[c, j] /= d; inv[c, j] /= d }
            for (r in 0 until n) if (r != c) {
                val f = m[r, c]
                if (f != 0.0) for (j in 0 until n) { m[r, j] -= f * m[c, j]; inv[r, j] -= f * inv[c, j] }
            }
        }
        return inv
    }

    private fun swapRows(i: Int, j: Int) {
        for (c in 0 until cols) { val t = this[i, c]; this[i, c] = this[j, c]; this[j, c] = t }
    }

    companion object {
        fun identity(n: Int) = Mat(n, n).also { for (i in 0 until n) it[i, i] = 1.0 }
        fun diag(vararg d: Double) = Mat(d.size, d.size).also { for (i in d.indices) it[i, i] = d[i] }
    }
}
