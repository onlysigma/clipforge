package com.clipforge.app

import androidx.media3.effect.RgbMatrix

/**
 * CapCut-style "premium" grade applied on the GPU via Media3's [RgbMatrix].
 *
 * v2 grade: gentle saturation boost + warm color balance (like the old
 * FFmpeg `colorbalance` + `hue=s=1.28` chain) + subtle contrast lift.
 * The warm balance prevents the "pink" tint from pure saturation.
 *
 * The matrix is column-major, following [android.opengl.Matrix] conventions:
 * element (row, col) lives at index `col * 4 + row`.
 */
class PremiumColorMatrix(
    private val saturation: Float = 1.15f,
    private val contrast: Float = 1.05f,
    // Warm balance: slight red/yellow lift (CapCut-style)
    private val warmRed: Float = 0.03f,
    private val warmGreen: Float = 0.015f,
    private val warmBlue: Float = -0.02f
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

        // Fold contrast around the 0.5 pivot + warm color balance.
        val bias = 0.5f * (1f - contrast)
        for (row in 0..2) {
            for (col in 0..2) {
                m[col * 4 + row] *= contrast
            }
            // Translation: contrast bias + warm tint per channel
            val warm = when (row) {
                0 -> warmRed
                1 -> warmGreen
                else -> warmBlue
            }
            m[12 + row] = m[12 + row] * contrast + bias + warm
        }
        return m
    }
}
