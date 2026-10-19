package com.vladimir.messenger.data.file

/**
 * Файл — установочный пакет Android (APK).
 *
 * По требованию владельца APK вне «пересылочной» экономики: не считается в
 * статистику пересылки (место под пересылку) и не упирается в её лимиты —
 * ни у отправителя, ни у получателя, ни у посредника, который держит файл
 * для другого. Узнаём по расширению имени и по типу содержимого манифеста.
 */
object ApkFiles {
    const val MIME = "application/vnd.android.package-archive"

    fun isApk(displayName: String?, mediaType: String?): Boolean {
        val type = mediaType?.trim()?.lowercase().orEmpty()
        if (type == MIME) return true
        val name = displayName?.substringBefore('?')?.substringBefore('#')
            ?.trim()?.lowercase().orEmpty()
        return name.endsWith(".apk")
    }
}
