package com.vladimir.messenger.ui.theme

// =============================================================================
// COLOR.KT — Цветовая палитра приложения
// =============================================================================
// Используем Material Design 3 цветовую систему.
// Два варианта: Light и Dark тема.
//
// Принцип именования Material3:
//   Primary       — основной акцентный цвет (кнопки, FAB)
//   Secondary     — вторичный акцент (чипы, второстепенные элементы)
//   Tertiary      — третичный акцент (редко используется)
//   Surface       — фон карточек, диалогов
//   Background    — фон экранов
//   On[Color]     — цвет текста/иконок НА данном фоне
//   [Color]Container — контейнер (менее насыщенный вариант)
// =============================================================================

import androidx.compose.ui.graphics.Color

// =============================================================================
// БАЗОВЫЕ ЦВЕТА (не зависят от темы)
// =============================================================================

// Основной синий — P2P ощущение, технологичность
val Blue10  = Color(0xFF001F3F)
val Blue20  = Color(0xFF003366)
val Blue30  = Color(0xFF00509E)
val Blue40  = Color(0xFF0070CC)  // Primary Light
val Blue80  = Color(0xFF90C8FF)  // Primary Dark
val Blue90  = Color(0xFFD0E8FF)
val Blue95  = Color(0xFFEBF4FF)

// Акцентный зелёный — онлайн статус, успешные операции
val Green10 = Color(0xFF002108)
val Green20 = Color(0xFF003910)
val Green30 = Color(0xFF005318)
val Green40 = Color(0xFF006E22)  // Secondary Light
val Green80 = Color(0xFF7DDC74)  // Secondary Dark
val Green90 = Color(0xFF9AEEA1)

// Фиолетовый — каналы, групповые чаты (Фаза 2)
val Purple10 = Color(0xFF1C0040)
val Purple40 = Color(0xFF6B36FF)
val Purple80 = Color(0xFFCCB7FF)

// Нейтральные
val Neutral10  = Color(0xFF1A1C1E)
val Neutral20  = Color(0xFF2F3033)
val Neutral30  = Color(0xFF46474A)
val Neutral40  = Color(0xFF5E5E62)
val Neutral80  = Color(0xFFC7C6CA)
val Neutral90  = Color(0xFFE3E2E6)
val Neutral95  = Color(0xFFF1F0F4)
val Neutral99  = Color(0xFFFDFBFF)
val Neutral100 = Color(0xFFFFFFFF)

// Нейтрально-вариантные (с лёгким оттенком)
val NeutralVariant10 = Color(0xFF191C22)
val NeutralVariant20 = Color(0xFF2D3038)
val NeutralVariant30 = Color(0xFF44474F)
val NeutralVariant40 = Color(0xFF5C5E67)
val NeutralVariant80 = Color(0xFFC4C6D0)
val NeutralVariant90 = Color(0xFFE1E2EC)

// Ошибка
val Error10 = Color(0xFF410002)
val Error40 = Color(0xFFBA1A1A)
val Error80 = Color(0xFFFFB4AB)
val Error90 = Color(0xFFFFDAD6)

// =============================================================================
// СПЕЦИФИЧНЫЕ ЦВЕТА МЕССЕНДЖЕРА
// =============================================================================

// Пузыри сообщений
val MessageBubbleOwn      = Color(0xFF0070CC)   // Мои сообщения (синий)
val MessageBubbleOther    = Color(0xFFF0F0F0)   // Чужие сообщения (серый)
val MessageBubbleOwnDark  = Color(0xFF004E8C)   // Мои сообщения (тёмная тема)
val MessageBubbleOtherDark= Color(0xFF2D2D2D)   // Чужие сообщения (тёмная тема)

// Статус соединения
val StatusOnline      = Color(0xFF4CAF50)  // Онлайн
val StatusOffline     = Color(0xFF9E9E9E)  // Оффлайн
val StatusConnecting  = Color(0xFFFFC107)  // Подключение
val StatusDegraded    = Color(0xFFFF9800)  // Деградированное соединение (через ретранслятор)