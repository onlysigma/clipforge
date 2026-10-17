package com.clipforge.app

import androidx.media3.effect.RgbMatrix

/**
 * "Premium" filter for ClipForge Native.
 *
 * v4: NEUTRAL edition — user feedback: "colors should stay as they are,
 * only resolution should upscale." The old warm balance was causing a
 * pink/magenta cast. This version does a very subtle polish with NO
 * color shifting: tiny saturation lift + micro contrast, zero tint.
 *
 * The matrix is column-major, following [android.opengl.Matrix] conventions:
 * element (row, col) lives at index `col * 4 + row`.
 */
class PremiumColorMatrix(
    private val saturation: Float = 1.08f,  // Very subtle (was 1.15)
    private val contrast: Float = 1.02f     // Very subtle (was 1.05)
    // NO warm balance — removed entirely to prevent pink tint
) : RgbMatrix {

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val inv = 1f - saturation
        val r = 0.2126f * inv // BT.709 luma coefficients
        val g = 0.7152f * inv
        val b = 0.0722f * inv

        // Column-major saturation matrix (neutral, no color shift).
        val m = floatArrayOf(
            r + saturation, g, b, 0f, // column 0
            r, g + saturation, b, 0f, // column 1
            r, g, b + saturation, 0f, // column 2
            0f, 0f, 0f, 1f            // column 3 (translation)
        )

        // Contrast around 0.5 pivot only — no tint added.
        val bias = 0.5f * (1f - contrast)
        for (row in 0..2) {
            for (col in 0..2) {
                m[col * 4 + row] *= contrast
            }
            m[12 + row] = m[12 + row] * contrast + bias
        }
        return m
    }
}
