package com.vladimir.messenger.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Несколько фотографий поста - в одну картинку-сетку для репоста.
 *
 * Чужие мессенджеры (Telegram, WhatsApp) принимают подпись только к одному
 * файлу; альбом из нескольких уходит без слов (владелец, 2026-09-09: фото
 * дошли, текст и ссылка - нет). Поэтому наружу отправляется одна картинка, в
 * которой все фото поста стоят рядами, как альбом в ленте, а текст со ссылкой
 * идёт подписью к ней. Полный пост с отдельными фото получатель откроет по
 * ссылке.
 *
 * Раскладка ([rows], [layout], [crop]) - чистые функции без Android, чтобы их
 * можно было проверить обычным тестом; рисование - в [compose].
 */
object PhotoCollage {

    /** Ширина итоговой картинки в пикселях. */
    const val WIDTH = 1080

    /** Зазор между фото. */
    const val GAP = 6

    /** Клетка сетки (или область исходника) в пикселях. */
    data class Cell(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    /**
     * Сколько фото в каждом ряду: до четырёх - по два в ряд, дальше - по три.
     * Ряды почти равные, более полный идёт первым: 3 → 2+1, 5 → 3+2, 6 → 3+3.
     */
    fun rows(count: Int): List<Int> {
        require(count > 0) { "empty collage" }
        if (count == 1) return listOf(1)
        val perRow = if (count <= 4) 2 else 3
        val rowCount = ceil(count / perRow.toFloat()).toInt()
        val base = count / rowCount
        val extra = count % rowCount
        return List(rowCount) { i -> base + if (i < extra) 1 else 0 }
    }

    /**
     * Клетки для [count] фото при ширине [width].
     *
     * Клетки одного ряда одинаковы по ширине; высота ряда - 3:4 от ширины
     * клетки (или 4:3, если [portrait]: фото в основном вертикальные, так их
     * меньше обрезает), но не выше [width] × 0.75 (для вертикальных - [width]),
     * чтобы одиночная клетка во всю ширину не вытягивала картинку столбом.
     */
    fun layout(
        count: Int,
        width: Int = WIDTH,
        gap: Int = GAP,
        portrait: Boolean = false,
    ): List<Cell> {
        val cells = ArrayList<Cell>(count)
        val ratio = if (portrait) 4f / 3f else 3f / 4f
        val maxRowHeight = (width * if (portrait) 1f else 0.75f).roundToInt()
        var top = 0
        rows(count).forEach { inRow ->
            val cellWidth = (width - gap * (inRow - 1)) / inRow
            val height = (cellWidth * ratio).roundToInt().coerceIn(1, maxRowHeight)
            var left = 0
            repeat(inRow) { i ->
                // Последняя клетка ряда добирает остаток целочисленного деления.
                val w = if (i == inRow - 1) width - left else cellWidth
                cells.add(Cell(left, top, w, height))
                left += cellWidth + gap
            }
            top += height + gap
        }
        return cells
    }

    /** Область исходника [srcW]×[srcH] с пропорциями клетки [dstW]×[dstH], по центру. */
    fun crop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Cell {
        val srcRatio = srcW.toFloat() / srcH
        val dstRatio = dstW.toFloat() / dstH
        return if (srcRatio > dstRatio) {
            // Исходник шире клетки - режем по бокам.
            val w = (srcH * dstRatio).roundToInt().coerceIn(1, srcW)
            Cell((srcW - w) / 2, 0, w, srcH)
        } else {
            // Исходник выше клетки - режем сверху и снизу.
            val h = (srcW / dstRatio).roundToInt().coerceIn(1, srcH)
            Cell(0, (srcH - h) / 2, srcW, h)
        }
    }

    /** Собрать сетку из [photos] (в порядке поста). Вызывать вне главного потока. */
    fun compose(photos: List<Bitmap>, width: Int = WIDTH): Bitmap {
        require(photos.isNotEmpty()) { "empty collage" }
        val portrait = photos.count { it.height > it.width } * 2 > photos.size
        val cells = layout(photos.size, width, GAP, portrait)
        val height = cells.last().bottom
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        photos.forEachIndexed { i, bmp ->
            val cell = cells[i]
            val src = crop(bmp.width, bmp.height, cell.width, cell.height)
            canvas.drawBitmap(
                bmp,
                Rect(src.left, src.top, src.right, src.bottom),
                Rect(cell.left, cell.top, cell.right, cell.bottom),
                paint,
            )
        }
        return out
    }
}
