package com.yolo.detector.tracking

/**
 * Kalman filter for tracking a bounding box using a constant-velocity motion model.
 *
 * State vector (8-dimensional):
 *   [cx, cy, w, h, vCx, vCy, vW, vH]
 *   where cx/cy is box centre, w/h is box size, v* are velocities.
 *
 * Observation vector (4-dimensional):
 *   [cx, cy, w, h]
 *
 * All matrix operations use simple array math to avoid native library dependencies.
 */
class KalmanFilter {

    // State dimension and observation dimension
    private val stateDim = 8
    private val obsDim = 4

    // State estimate: x̂ = [cx, cy, w, h, vCx, vCy, vW, vH]
    private val x = FloatArray(stateDim)

    // Error covariance: P (8×8)
    private val P = Array(stateDim) { i -> FloatArray(stateDim) { j -> if (i == j) 1f else 0f } }

    // State transition matrix F (constant velocity model)
    // x_{k+1} = F * x_k
    private val F = Array(stateDim) { i ->
        FloatArray(stateDim) { j ->
            when {
                i == j -> 1f                          // identity
                i < obsDim && j == i + obsDim -> 1f   // position += velocity
                else -> 0f
            }
        }
    }

    // Observation matrix H (maps state to measurement)
    // z = H * x  =>  H extracts [cx, cy, w, h]
    private val H = Array(obsDim) { i ->
        FloatArray(stateDim) { j -> if (i == j) 1f else 0f }
    }

    // Process noise covariance Q (diagonal, tuned for normalized coordinates in [0, 1])
    private val Q = Array(stateDim) { i ->
        FloatArray(stateDim) { j ->
            if (i != j) 0f
            else if (i < obsDim) 1e-4f else 1e-3f
        }
    }

    // Measurement noise covariance R (diagonal, tuned for normalized coordinates in [0, 1])
    private val R = Array(obsDim) { i ->
        FloatArray(obsDim) { j -> if (i == j) 1e-3f else 0f }
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    /** Initialises the filter with the first observed bounding box measurement. */
    fun init(cx: Float, cy: Float, w: Float, h: Float) {
        x[0] = cx; x[1] = cy; x[2] = w; x[3] = h
        x[4] = 0f; x[5] = 0f; x[6] = 0f; x[7] = 0f
        // Realistic initial uncertainty for normalized [0, 1] coordinates
        for (i in 0 until stateDim) {
            for (j in 0 until stateDim) {
                P[i][j] = if (i == j) (if (i < obsDim) 1e-2f else 1e-1f) else 0f
            }
        }
    }

    // ── Predict ───────────────────────────────────────────────────────────────

    /** Advances the state by one time step without a measurement. */
    fun predict() {
        // x̂ = F * x
        val newX = mvMul(F, x)
        newX.copyInto(x)

        // P = F * P * Fᵀ + Q
        val FP = mmMul(F, P)
        val FPFt = mmMul(FP, transpose(F))
        for (i in 0 until stateDim) {
            for (j in 0 until stateDim) {
                val pVal = FPFt[i][j] + Q[i][j]
                P[i][j] = if (i == j) pVal.coerceAtLeast(1e-6f) else pVal
            }
        }
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /** Incorporates a new measurement [cx], [cy], [w], [h] into the state. */
    fun update(cx: Float, cy: Float, w: Float, h: Float) {
        val z = floatArrayOf(cx, cy, w, h)

        // Innovation: y = z - H * x̂
        val Hx = mvMul(H, x)
        val y = FloatArray(obsDim) { i -> z[i] - Hx[i] }

        // Innovation covariance: S = H * P * Hᵀ + R
        val HP = mmMul(H, P)
        val HPHt = mmMul(HP, transpose(H))
        val S = Array(obsDim) { i -> FloatArray(obsDim) { j -> HPHt[i][j] + R[i][j] } }

        // Kalman gain: K = P * Hᵀ * S⁻¹
        val PHt = mmMul(P, transpose(H))
        val Sinv = invert4x4(S) ?: return   // skip update if S is singular
        val K = mmMul(PHt, Sinv)

        // State update: x = x + K * y
        val Ky = mvMul(K, y)
        for (i in 0 until stateDim) x[i] += Ky[i]

        // Covariance update: P = (I - K * H) * P (symmetrized)
        val KH = mmMul(K, H)
        val IKH = Array(stateDim) { i -> FloatArray(stateDim) { j -> (if (i == j) 1f else 0f) - KH[i][j] } }
        val newP = mmMul(IKH, P)
        for (i in 0 until stateDim) {
            for (j in 0 until stateDim) {
                val sym = (newP[i][j] + newP[j][i]) / 2f
                P[i][j] = if (i == j) sym.coerceAtLeast(1e-6f) else sym
            }
        }
    }

    // ── State access ──────────────────────────────────────────────────────────

    /** Returns the current predicted bounding box as [cx, cy, w, h]. */
    fun stateToBbox(): FloatArray = floatArrayOf(x[0], x[1], x[2], x[3])

    // ── Matrix helpers ────────────────────────────────────────────────────────

    private fun transpose(m: Array<FloatArray>): Array<FloatArray> {
        val rows = m.size; val cols = m[0].size
        return Array(cols) { i -> FloatArray(rows) { j -> m[j][i] } }
    }

    private fun mvMul(m: Array<FloatArray>, v: FloatArray): FloatArray =
        FloatArray(m.size) { i -> m[i].zip(v.toList()).sumOf { (a, b) -> (a * b).toDouble() }.toFloat() }

    private fun mmMul(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val rows = a.size; val cols = b[0].size; val inner = b.size
        return Array(rows) { i ->
            FloatArray(cols) { j ->
                var sum = 0f
                for (k in 0 until inner) sum += a[i][k] * b[k][j]
                sum
            }
        }
    }

    /**
     * Inverts a 4×4 matrix using cofactor expansion.
     * Returns null if the matrix is singular (determinant ≈ 0).
     */
    private fun invert4x4(m: Array<FloatArray>): Array<FloatArray>? {
        // Use Gaussian elimination with partial pivoting
        val n = 4
        val aug = Array(n) { i -> FloatArray(2 * n) { j -> if (j < n) m[i][j] else if (j - n == i) 1f else 0f } }

        for (col in 0 until n) {
            // Pivot
            var maxRow = col
            for (row in col + 1 until n) {
                if (kotlin.math.abs(aug[row][col]) > kotlin.math.abs(aug[maxRow][col])) maxRow = row
            }
            val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp

            val pivot = aug[col][col]
            if (kotlin.math.abs(pivot) < 1e-10f) return null

            for (j in 0 until 2 * n) aug[col][j] /= pivot
            for (row in 0 until n) {
                if (row == col) continue
                val factor = aug[row][col]
                for (j in 0 until 2 * n) aug[row][j] -= factor * aug[col][j]
            }
        }
        return Array(n) { i -> FloatArray(n) { j -> aug[i][j + n] } }
    }
}
