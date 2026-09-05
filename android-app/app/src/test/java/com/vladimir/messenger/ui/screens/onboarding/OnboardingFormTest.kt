package com.vladimir.messenger.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Готовность формы регистрации.
 *
 * Раньше кнопка просто оставалась тёмной, и человек не понимал, чего от него
 * хотят. Условие вынесено в состояние, поэтому его можно проверить здесь, а
 * экран показывает недостающее подписью под полями.
 */
class OnboardingFormTest {

    private val filled = OnboardingUiState(
        displayName = "Анна Ковалёва",
        nickname = "anna_k",
        password = "verygoodpass",
        passwordRepeat = "verygoodpass",
    )

    @Test
    fun `complete registration form is ready`() {
        assertTrue(filled.canSubmit)
    }

    @Test
    fun `registration needs every field`() {
        assertFalse(filled.copy(displayName = "").canSubmit)
        assertFalse(filled.copy(displayName = "А").canSubmit)
        assertFalse(filled.copy(nickname = "").canSubmit)
    }

    @Test
    fun `short password blocks registration`() {
        val short = "a".repeat(MIN_PASSWORD_LENGTH - 1)
        assertFalse(filled.copy(password = short, passwordRepeat = short).canSubmit)
    }

    @Test
    fun `password must be repeated exactly`() {
        // Опечатка обнаружилась бы только при восстановлении, когда исправить
        // уже нечем, поэтому повтор обязателен.
        assertFalse(filled.copy(passwordRepeat = "verygoodpasz").canSubmit)
        assertFalse(filled.copy(passwordRepeat = "").canSubmit)
    }

    @Test
    fun `restore mode needs only nickname and password`() {
        val restoring = OnboardingUiState(
            restoreMode = true,
            nickname = "anna_k",
            password = "verygoodpass",
        )
        // Ни имени, ни повтора пароля здесь не требуется.
        assertTrue(restoring.canSubmit)
        assertFalse(restoring.copy(nickname = "").canSubmit)
        assertFalse(restoring.copy(password = "").canSubmit)
    }

    @Test
    fun `restore accepts any existing password length`() {
        // Старый пароль мог быть задан по прежним правилам - не отвергаем его.
        val restoring = OnboardingUiState(
            restoreMode = true,
            nickname = "anna_k",
            password = "old",
        )
        assertTrue(restoring.canSubmit)
    }

    @Test
    fun `nothing is submitted while busy`() {
        assertFalse(filled.copy(isLoading = true).canSubmit)
    }

    @Test
    fun `password threshold is stated once`() {
        assertEquals(8, MIN_PASSWORD_LENGTH)
    }
}
