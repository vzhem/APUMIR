package com.vladimir.messenger.data.background

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.vladimir.messenger.R

/**
 * Фоны для видеозвонка и видеосторис: человек вырезается сегментацией,
 * позади него рисуется выбранная картинка.
 *
 * Картинки не лежат файлами в APK, а рисуются кодом под нужный размер кадра:
 * градиент неба и простые силуэты. Так набор легко менять и он ничего не весит.
 */
enum class VideoBackground(val labelRes: Int) {
    NONE(R.string.call_bg_none),
    BLUR(R.string.call_bg_blur),
    OFFICE(R.string.call_bg_office),
    BEACH(R.string.call_bg_beach),
    MOUNTAINS(R.string.call_bg_mountains),
    FOREST(R.string.call_bg_forest),
    CITY(R.string.call_bg_city),
    SUNSET(R.string.call_bg_sunset),
    SPACE(R.string.call_bg_space),
    CAFE(R.string.call_bg_cafe),
    LIBRARY(R.string.call_bg_library),
    AURORA(R.string.call_bg_aurora);

    val isActive: Boolean get() = this != NONE

    /** Нарисовать фон точно под размер кадра [width]x[height]. Для NONE и BLUR не вызывается. */
    fun render(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(2), height.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun sky(top: Int, bottom: Int, horizon: Float = 1f) {
            paint.shader = LinearGradient(0f, 0f, 0f, h * horizon, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h * horizon, paint)
            paint.shader = null
        }
        fun circle(x: Float, y: Float, r: Float, color: Int) {
            paint.color = color
            canvas.drawCircle(x, y, r, paint)
        }
        fun hill(color: Int, vararg points: Float) {
            paint.color = color
            val path = Path()
            path.moveTo(0f, h)
            var i = 0
            while (i + 1 < points.size) {
                path.lineTo(points[i] * w, points[i + 1] * h)
                i += 2
            }
            path.lineTo(w, h)
            path.close()
            canvas.drawPath(path, paint)
        }
        fun rect(x: Float, y: Float, right: Float, bottom: Float, color: Int) {
            paint.color = color
            canvas.drawRect(x * w, y * h, right * w, bottom * h, paint)
        }
        when (this) {
            OFFICE -> {
                sky(Color.rgb(214, 222, 232), Color.rgb(168, 180, 198), 0.72f)
                rect(0f, 0.72f, 1f, 1f, Color.rgb(112, 98, 88))
                rect(0.06f, 0.10f, 0.40f, 0.58f, Color.rgb(240, 244, 248))
                rect(0.55f, 0.08f, 0.94f, 0.50f, Color.rgb(226, 233, 240))
                rect(0.10f, 0.62f, 0.90f, 0.74f, Color.rgb(128, 106, 84))
            }
            BEACH -> {
                sky(Color.rgb(94, 178, 228), Color.rgb(178, 226, 246), 0.52f)
                circle(0.82f * w, 0.16f * h, 0.09f * w, Color.rgb(255, 224, 130))
                rect(0f, 0.52f, 1f, 0.74f, Color.rgb(38, 150, 190))
                rect(0f, 0.74f, 1f, 1f, Color.rgb(240, 214, 160))
                hill(Color.rgb(46, 140, 84), 0f, 0.70f, 0.16f, 0.58f, 0.26f, 0.74f)
            }
            MOUNTAINS -> {
                sky(Color.rgb(120, 168, 220), Color.rgb(208, 228, 246), 0.7f)
                circle(0.16f * w, 0.14f * h, 0.07f * w, Color.rgb(255, 238, 170))
                hill(Color.rgb(104, 122, 158), 0f, 0.66f, 0.28f, 0.30f, 0.52f, 0.66f)
                hill(Color.rgb(70, 88, 126), 0.30f, 0.68f, 0.62f, 0.26f, 1f, 0.68f)
                hill(Color.WHITE, 0.545f, 0.365f, 0.62f, 0.26f, 0.695f, 0.365f)
                hill(Color.rgb(56, 96, 70), 0f, 1f, 0f, 0.78f, 1f, 0.78f)
            }
            FOREST -> {
                sky(Color.rgb(148, 196, 140), Color.rgb(205, 232, 190), 0.6f)
                rect(0f, 0.6f, 1f, 1f, Color.rgb(52, 110, 56))
                for (i in 0..7) {
                    val x = 0.04f + i * 0.13f
                    rect(x, 0.34f, x + 0.02f, 0.78f, Color.rgb(74, 56, 40))
                    hill(Color.rgb(30, 96, 44), x - 0.05f, 0.52f, x + 0.01f, 0.26f, x + 0.07f, 0.52f)
                }
            }
            CITY -> {
                sky(Color.rgb(86, 108, 166), Color.rgb(198, 168, 150), 0.8f)
                circle(0.78f * w, 0.18f * h, 0.08f * w, Color.rgb(255, 214, 140))
                val heights = floatArrayOf(0.42f, 0.30f, 0.50f, 0.36f, 0.46f, 0.28f)
                for (i in heights.indices) {
                    val x = i * 0.17f
                    rect(x, heights[i], x + 0.15f, 0.86f, Color.rgb(44, 52, 84))
                    for (wy in 0..3) for (wx in 0..1) {
                        if ((i * 7 + wy * 3 + wx) % 3 != 0) {
                            rect(x + 0.03f + wx * 0.06f, heights[i] + 0.06f + wy * 0.09f,
                                x + 0.06f + wx * 0.06f, heights[i] + 0.10f + wy * 0.09f,
                                Color.rgb(255, 214, 130))
                        }
                    }
                }
                rect(0f, 0.86f, 1f, 1f, Color.rgb(30, 34, 52))
            }
            SUNSET -> {
                sky(Color.rgb(252, 140, 84), Color.rgb(146, 74, 140), 1f)
                circle(0.5f * w, 0.52f * h, 0.17f * w, Color.rgb(255, 206, 110))
                hill(Color.rgb(60, 34, 74), 0f, 0.86f, 0.35f, 0.72f, 0.7f, 0.86f, 1f, 0.76f)
            }
            SPACE -> {
                sky(Color.rgb(6, 8, 32), Color.rgb(24, 16, 64), 1f)
                for (i in 0..70) {
                    val x = ((i * 37) % 100) / 100f * w
                    val y = ((i * 53) % 80) / 100f * h
                    circle(x, y, (1 + i % 3).toFloat(), Color.WHITE)
                }
                paint.shader = RadialGradient(0.78f * w, 0.66f * h, 0.30f * w,
                    Color.rgb(88, 150, 220), Color.rgb(20, 40, 96), Shader.TileMode.CLAMP)
                canvas.drawCircle(0.78f * w, 0.66f * h, 0.30f * w, paint)
                paint.shader = null
                circle(0.70f * w, 0.58f * h, 0.05f * w, Color.rgb(140, 200, 240))
            }
            CAFE -> {
                sky(Color.rgb(214, 178, 142), Color.rgb(166, 124, 92), 0.75f)
                rect(0f, 0.75f, 1f, 1f, Color.rgb(96, 66, 48))
                rect(0.08f, 0.12f, 0.44f, 0.55f, Color.rgb(240, 226, 200))
                for (i in 0..2) circle((0.62f + i * 0.13f) * w, 0.20f * h, 0.035f * w, Color.rgb(255, 196, 110))
                rect(0.58f, 0.60f, 0.92f, 0.66f, Color.rgb(70, 46, 34))
            }
            LIBRARY -> {
                sky(Color.rgb(122, 84, 58), Color.rgb(84, 56, 40), 1f)
                for (shelf in 0..2) {
                    val top = 0.08f + shelf * 0.30f
                    rect(0.04f, top + 0.24f, 0.96f, top + 0.27f, Color.rgb(52, 34, 24))
                    for (i in 0..11) {
                        val x = 0.06f + i * 0.078f
                        val colors = intArrayOf(
                            Color.rgb(150, 60, 52), Color.rgb(52, 84, 130), Color.rgb(60, 120, 74),
                            Color.rgb(170, 130, 52), Color.rgb(110, 66, 120),
                        )
                        rect(x, top + 0.04f + (i % 3) * 0.012f, x + 0.055f, top + 0.24f, colors[i % colors.size])
                    }
                }
            }
            AURORA -> {
                sky(Color.rgb(8, 14, 44), Color.rgb(10, 30, 60), 0.78f)
                for (i in 0..50) {
                    val x = ((i * 41) % 100) / 100f * w
                    val y = ((i * 29) % 55) / 100f * h
                    circle(x, y, (1 + i % 2).toFloat(), Color.WHITE)
                }
                paint.shader = LinearGradient(0f, 0.06f * h, w, 0.5f * h,
                    intArrayOf(Color.argb(150, 60, 255, 170), Color.argb(90, 80, 190, 255), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP)
                val wave = Path()
                wave.moveTo(0f, 0.30f * h)
                wave.cubicTo(0.3f * w, 0.05f * h, 0.6f * w, 0.45f * h, w, 0.16f * h)
                wave.lineTo(w, 0.45f * h)
                wave.cubicTo(0.6f * w, 0.65f * h, 0.3f * w, 0.30f * h, 0f, 0.52f * h)
                wave.close()
                canvas.drawPath(wave, paint)
                paint.shader = null
                hill(Color.rgb(16, 26, 44), 0f, 0.84f, 0.4f, 0.74f, 1f, 0.86f)
            }
            else -> sky(Color.rgb(60, 60, 60), Color.rgb(30, 30, 30), 1f)
        }
        return bitmap
    }

    companion object {
        /** Все варианты для ленты выбора: без фона, размытие и десять картинок. */
        val pickerEntries: List<VideoBackground> = entries
    }
}
