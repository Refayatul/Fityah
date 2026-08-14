package com.refayatul.fityah.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

object ColorUtils {

    fun getDominantColor(icon: Drawable): Int {
        try {
            val bitmap = if (icon is BitmapDrawable && icon.bitmap != null) {
                icon.bitmap
            } else {
                val bmp = Bitmap.createBitmap(
                    icon.intrinsicWidth.coerceAtLeast(1),
                    icon.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                icon.setBounds(0, 0, canvas.width, canvas.height)
                icon.draw(canvas)
                bmp
            }

            val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)

            val colorCounts = mutableMapOf<Int, Int>()
            val hsv = FloatArray(3)
            
            for (pixel in pixels) {
                val alpha = Color.alpha(pixel)
                if (alpha < 200) continue

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                Color.colorToHSV(pixel, hsv)
                val saturation = hsv[1]
                val value = hsv[2]

                // Skip pure white/gray/black to find the actual brand color
                if (saturation < 0.15f || value < 0.2f) {
                    continue
                }

                val bucket = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
                colorCounts[bucket] = colorCounts.getOrDefault(bucket, 0) + 1
            }

            if (colorCounts.isNotEmpty()) {
                val maxBucket = colorCounts.maxByOrNull { it.value }?.key ?: return Color.GRAY
                val rr = (maxBucket shr 8) and 0xF
                val gg = (maxBucket shr 4) and 0xF
                val bb = maxBucket and 0xF
                return Color.rgb((rr shl 4) or rr, (gg shl 4) or gg, (bb shl 4) or bb)
            } else {
                // Fallback to absolute most frequent color ignoring saturation (monochrome icon)
                val fallbackCounts = mutableMapOf<Int, Int>()
                for (pixel in pixels) {
                    if (Color.alpha(pixel) < 200) continue
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val bucket = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
                    fallbackCounts[bucket] = fallbackCounts.getOrDefault(bucket, 0) + 1
                }
                val maxBucket = fallbackCounts.maxByOrNull { it.value }?.key ?: return Color.GRAY
                val rr = (maxBucket shr 8) and 0xF
                val gg = (maxBucket shr 4) and 0xF
                val bb = maxBucket and 0xF
                return Color.rgb((rr shl 4) or rr, (gg shl 4) or gg, (bb shl 4) or bb)
            }
        } catch (e: Exception) {
            return Color.GRAY
        }
    }
}
