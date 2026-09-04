package com.vladimir.messenger.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Насколько далеко надо увести палец вправо, чтобы это считалось «назад».
 *
 * В пикселях, а не в долях экрана: доля на планшете потребовала бы неудобно
 * длинного движения, а случайный сдвиг на десяток пикселей не должен закрывать
 * экран.
 */
const val SWIPE_BACK_THRESHOLD_PX = 140f

/**
 * Смахивание вправо = «Назад».
 *
 * Привычный жест: на экране без него человек тянется к кнопке в углу, хотя
 * рядом уже есть такой же экран, который умеет закрываться пальцем. Вешать на
 * всё содержимое безопасно — вертикальная прокрутка не страдает, Compose
 * отдаёт жест тому, кто первым распознал направление, а мы забираем только
 * явное движение вправо.
 *
 * @param enabled выключает жест, когда назад идти некуда.
 * @param onBack что делать по завершённому смахиванию.
 */
fun Modifier.swipeBack(
    enabled: Boolean = true,
    onBack: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    this.pointerInput(onBack) {
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragEnd = {
                if (travelled > SWIPE_BACK_THRESHOLD_PX) onBack()
            },
        ) { change, dragAmount ->
            travelled += dragAmount
            // Гасим только движение вправо, чтобы не отбирать жесты у
            // горизонтальных списков и листалок внутри экрана.
            if (dragAmount > 0f) change.consume()
        }
    }
}
