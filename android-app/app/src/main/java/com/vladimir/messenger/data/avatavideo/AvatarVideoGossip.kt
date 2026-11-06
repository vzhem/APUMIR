package com.vladimir.messenger.data.avatavideo

import android.content.Context

/**
 * Мост от хранилища своего видеоаватара ([com.vladimir.messenger.data.profile.VideoAvatarStore])
 * к координатору обмена, которому нужны DI-детали (база контактов, подготовка
 * файловых передач). Хранилище — объект без зависимостей, координатор —
 * Hilt-класс: связка одна и явная, без второго открытия базы и без поиска
 * зависимостей из статики.
 */
object AvatarVideoGossip {
    @Volatile
    private var coordinator: AvatarVideoCoordinator? = null

    internal fun attach(target: AvatarVideoCoordinator) {
        coordinator = target
    }

    /** Своё видео сменили (или убрали) — пора объявить его контактам. */
    fun onMyVideoChanged(context: Context) {
        val target = coordinator
        if (target == null) {
            // Приложение ещё не собрало граф: следующий старт объявит само.
            return
        }
        target.onMyVideoChanged()
    }
}
