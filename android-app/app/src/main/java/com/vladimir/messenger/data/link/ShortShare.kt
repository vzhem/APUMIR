package com.vladimir.messenger.data.link

import android.content.Context
import android.util.Log
import com.vladimir.messenger.util.AppShare
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Доступ к сокращателю ссылок из экранов без своей ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LinkShortenerEntryPoint {
    fun linkShortener(): LinkShortener
}

/**
 * «Поделиться» с короткой ссылкой.
 *
 * Ссылки на свой профиль и на контакт (`apu://a/…`) в чужих мессенджерах не
 * кликабельны: те подсвечивают только http(s). Поэтому перед системным меню
 * спрашиваем у сервиса короткую `https://<хост>/s/<код>` - она открывается
 * одним нажатием и у тех, у кого APU нет, ведёт на страницу установки.
 * Сервис не ответил - делимся прежней ссылкой, как раньше.
 *
 * Меню открывается после ответа сервиса (обычно доли секунды, не дольше
 * таймаута в BotApi); после отказа LinkShortener пару минут не ходит в сеть,
 * и меню открывается сразу.
 */
object ShortShare {

    private const val TAG = "ShortShare"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Приглашение в APU от своего имени (текст AppShare.inviteText). */
    fun shareInvite(context: Context, displayName: String, link: String) {
        withShortLink(context, link) { shared -> AppShare.shareInvite(context, displayName, shared) }
    }

    /** Произвольный текст, в который ссылка подставляется уже короткой. */
    fun shareText(context: Context, link: String, title: String, text: (link: String) -> String) {
        withShortLink(context, link) { shared -> AppShare.shareText(context, text(shared), title) }
    }

    private fun withShortLink(context: Context, link: String, then: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val shared = withContext(Dispatchers.IO) {
                runCatching {
                    EntryPointAccessors.fromApplication(app, LinkShortenerEntryPoint::class.java)
                        .linkShortener()
                        .shorten(link)
                }.onFailure { Log.w(TAG, "short link failed: ${it.javaClass.simpleName}") }
                    .getOrNull()
            }
            runCatching { then(shared ?: link) }
                .onFailure { Log.w(TAG, "share failed: ${it.javaClass.simpleName}") }
        }
    }
}
