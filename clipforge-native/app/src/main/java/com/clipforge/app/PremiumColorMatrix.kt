package com.clipforge.app

import androidx.media3.effect.RgbMatrix

/**
 * CapCut-style "premium" grade applied on the GPU via Media3's [RgbMatrix].
 *
 * v1 grade: saturation boost (like the old `hue=s=1.28`) plus a gentle
 * contrast lift (like the old `eq=contrast=1.06`), folded into a single
 * 4x4 color matrix. Applied in the linear working color space.
 *
 * The matrix is column-major, following [android.opengl.Matrix] conventions:
 * element (row, col) lives at index `col * 4 + row`.
 */
class PremiumColorMatrix(
    private val saturation: Float = 1.28f,
    private val contrast: Float = 1.06f
) : RgbMatrix {

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val inv = 1f - saturation
        val r = 0.2126f * inv // BT.709 luma coefficients
        val g = 0.7152f * inv
        val b = 0.0722f * inv

        // Column-major saturation matrix.
        val m = floatArrayOf(
            r + saturation, g, b, 0f, // column 0
            r, g + saturation, b, 0f, // column 1
            r, g, b + saturation, 0f, // column 2
            0f, 0f, 0f, 1f            // column 3 (translation)
        )

        // Fold contrast around the 0.5 pivot into the matrix.
        val bias = 0.5f * (1f - contrast)
        for (row in 0..2) {
            for (col in 0..3) {
                m[col * 4 + row] *= contrast
            }
            m[12 + row] += bias // translation column, rows R/G/B
        }
        return m
    }
}
