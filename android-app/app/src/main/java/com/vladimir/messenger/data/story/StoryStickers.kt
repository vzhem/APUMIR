package com.vladimir.messenger.data.story

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vladimir.messenger.R

/**
 * Картинки AR-фильтров (вырезанные PNG с прозрачным фоном; румянец рисуется кодом). Один кэш на экран и на запись,
 * чтобы каждое лицо в каждом кадре не декодировало файл заново.
 */
internal object StoryStickers {
    private val resources: Map<String, Int> = mapOf(
        "ear_fox" to R.drawable.ar_ear_fox,
        "ear_cat" to R.drawable.ar_ear_cat,
        "ear_dog" to R.drawable.ar_ear_dog,
        "ear_bunny" to R.drawable.ar_ear_bunny,
        "ear_bear" to R.drawable.ar_ear_bear,
        "nose_cat" to R.drawable.ar_nose_cat,
        "nose_dog" to R.drawable.ar_nose_dog,
        "nose_pig" to R.drawable.ar_nose_pig,
        "nose_bear" to R.drawable.ar_nose_bear,
        "nose_rabbit" to R.drawable.ar_nose_rabbit,
        "eye_cat" to R.drawable.ar_eye_cat,
        "eye_owl" to R.drawable.ar_eye_owl,
        "eye_panda" to R.drawable.ar_eye_panda,
        "eye_frog" to R.drawable.ar_eye_frog,
        "lips_red" to R.drawable.ar_lips_red,
        "lips_pink" to R.drawable.ar_lips_pink,
        "brow" to R.drawable.ar_brow,
        "whiskers" to R.drawable.ar_whiskers,
        "brow_thin" to R.drawable.ar_brow_thin,
        "cheek" to R.drawable.ar_cheek,
        "cheek_freckles" to R.drawable.ar_cheek_freckles,
    )

    private val cache = HashMap<String, Bitmap>()

    /** Картинка по имени или null, если такой нет или файл не декодируется (тогда рисуем фигурой). */
    @Synchronized
    fun bitmap(context: Context, name: String): Bitmap? {
        cache[name]?.let { return it }
        val id = resources[name] ?: return null
        val bitmap = BitmapFactory.decodeResource(context.resources, id) ?: return null
        cache[name] = bitmap
        return bitmap
    }
}
