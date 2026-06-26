package com.vayunmathur.photos.data

import android.graphics.Bitmap

/**
 * Reads every pixel of the bitmap once, applies [transform] (an ARGB int -> ARGB int mapping),
 * and writes the results back in place. Replaces the repeated getPixels/loop/setPixels boilerplate.
 */
inline fun Bitmap.mapPixels(transform: (Int) -> Int) {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) pixels[i] = transform(pixels[i])
    setPixels(pixels, 0, width, 0, 0, width, height)
}
