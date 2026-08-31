package com.vladimir.messenger.ui.components

// =============================================================================
// ANIMATEDTOPICICON.KT — 100 оригинальных живых значков тем
// =============================================================================
// Владелец попросил ~100 оригинальных форм (не формы x оттенки), красочных,
// с бликами и переливами; образцы - его скриншоты из мессенджера. Каждая
// форма нарисована кодом на Canvas: градиенты, белые блики, бегущий перелив
// и лёгкое движение (скачет / кружится / пульсирует / покачивается / плывёт).
// Код значка - имя формы ("train", "pizza"); он хранится в базе и ходит по
// проводу. Старые параметрические коды ("ball-3") и эмодзи дорисовываются
// совместимо: параметрические сводятся к ближайшей форме, эмодзи - текстом.

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

object TopicIconCatalog {
    /** 100 оригинальных форм, порядок - как в сетке выбора. */
    val kinds = listOf(
        "chat", "bolt", "mic", "top", "cool", "bang", "memo", "calendar", "folder", "search",
        "horn", "flame", "heart", "ask", "chartup", "chartdown", "gem", "moneybag", "wings", "coin",
        "exchange", "pad", "laptop", "phone", "car", "house", "heartarrow", "party", "bang2", "trophy",
        "checkflag", "clapper", "note", "sun", "books", "crown", "soccer", "basket", "tv", "eyes",
        "lips", "strawberry", "lipstick", "heel", "plane", "case", "island", "suncloud", "unicorn", "shops",
        "handbag", "cart", "train", "boat", "mountain", "tent", "robot", "disco", "ticket", "pirate",
        "ballot", "gradcap", "telescope", "microscope", "notes2", "moon", "dancer", "dancer2", "helmet", "briefcase",
        "tube", "family", "baby", "bank", "abacus", "printer", "police", "steth", "pill", "syringe",
        "soap", "idcard", "plate", "fish", "palette", "masks", "tophat", "crystal", "cocktail", "cake",
        "coffee", "sushi", "burger", "pizza", "virus", "star", "ball", "drop", "flower", "gear",
    )

    val names = mapOf(
        "chat" to "Пузырёк", "bolt" to "Молния", "mic" to "Микрофон", "top" to "Стрелка вверх",
        "cool" to "Крутые очки", "bang" to "Вослицание", "memo" to "Записка", "calendar" to "Календарь",
        "folder" to "Папка", "search" to "Лупа", "horn" to "Горн", "flame" to "Огонёк",
        "heart" to "Сердце", "ask" to "Вопрос", "chartup" to "Рост", "chartdown" to "Спад",
        "gem" to "Кристалл", "moneybag" to "Мешок денег", "wings" to "Купюры с крыльями", "coin" to "Монета",
        "exchange" to "Обмен", "pad" to "Геймпад", "laptop" to "Ноутбук", "phone" to "Телефон",
        "car" to "Машина", "house" to "Дом", "heartarrow" to "Сердце со стрелой", "party" to "Хлопушка",
        "bang2" to "Два восклицания", "trophy" to "Кубок", "checkflag" to "Финишный флаг", "clapper" to "Кино",
        "note" to "Нота", "sun" to "Солнце", "books" to "Книги", "crown" to "Корона",
        "soccer" to "Мяч", "basket" to "Баскетбол", "tv" to "Телевизор", "eyes" to "Глаза",
        "lips" to "Губы", "strawberry" to "Клубника", "lipstick" to "Помада", "heel" to "Туфелька",
        "plane" to "Самолёт", "case" to "Чемодан", "island" to "Остров", "suncloud" to "Солнце за тучей",
        "unicorn" to "Единорог", "shops" to "Пакеты", "handbag" to "Сумочка", "cart" to "Корзина",
        "train" to "Паровозик", "boat" to "Яхта", "mountain" to "Горы", "tent" to "Палатка",
        "robot" to "Робот", "disco" to "Диско-шар", "ticket" to "Билет", "pirate" to "Пиратский флаг",
        "ballot" to "Голосование", "gradcap" to "Фуражка выпуска", "telescope" to "Телескоп", "microscope" to "Микроскоп",
        "notes2" to "Ноты", "moon" to "Месяц", "dancer" to "Танцор", "dancer2" to "Танцовщица",
        "helmet" to "Каска", "briefcase" to "Портфель", "tube" to "Пробирка", "family" to "Семья",
        "baby" to "Малыш", "bank" to "Банк", "abacus" to "Счёты", "printer" to "Принтер",
        "police" to "Полицейский", "steth" to "Стетоскоп", "pill" to "Капсула", "syringe" to "Шприц",
        "soap" to "Мыло", "idcard" to "Карточка", "plate" to "Обед", "fish" to "Рыбка",
        "palette" to "Палитра", "masks" to "Маски", "tophat" to "Цилиндр", "crystal" to "Хрустальный шар",
        "cocktail" to "Коктейль", "cake" to "Торт", "coffee" to "Кофе", "sushi" to "Суши",
        "burger" to "Бургер", "pizza" to "Пицца", "virus" to "Вирус", "star" to "Звезда",
        "ball" to "Мячик", "drop" to "Капля", "flower" to "Цветок", "gear" to "Шестерёнка",
    )

    const val DEFAULT = "chat"

    /** Старые параметрические коды сводим к ближайшей форме. */
    private val legacyRep = mapOf(
        "ball" to "ball", "heart" to "heart", "star" to "star", "drop" to "drop",
        "flower" to "flower", "gem" to "gem", "moon" to "moon", "note" to "note",
        "balloon" to "party", "flame" to "flame", "gear" to "gear", "sun" to "sun",
        "chat" to "chat", "train" to "train", "fish" to "fish",
    )

    private val drawerSet = kinds.toSet()

    fun normalize(kind: String): String? {
        if (kind in drawerSet) return kind
        val dash = kind.lastIndexOf('-')
        if (dash > 0) {
            return legacyRep[kind.substring(0, dash)]
        }
        return legacyRep[kind]
    }

    fun isKnown(kind: String): Boolean = normalize(kind) != null

    fun describe(kind: String): String = names[normalize(kind)] ?: kind
}

/** Значок темы: код из каталога - анимацией, эмодзи старых тем - текстом. */
@Composable
fun TopicIconView(icon: String, size: Dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        val kind = TopicIconCatalog.normalize(icon)
        if (kind != null) {
            AnimatedTopicIcon(kind, Modifier.size(size))
        } else {
            Text(icon, fontSize = (size.value * 0.55f).sp)
        }
    }
}

@Composable
fun AnimatedTopicIcon(kind: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "topic-icon")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "topic-icon-t",
    )
    Canvas(modifier = modifier) {
        val drawer = DRAWERS[kind] ?: DRAWERS.getValue(TopicIconCatalog.DEFAULT)
        val idx = TopicIconCatalog.kinds.indexOf(kind)
        drawWithMotion(idx, t) { drawer(t) }
    }
}

// ── движение, общее для всех форм ────────────────────────────────────────────

private val PI2 = (2.0 * Math.PI).toFloat()
private fun sinF(x: Float): Float = sin(x.toDouble()).toFloat()
private fun cosF(x: Float): Float = cos(x.toDouble()).toFloat()

private fun DrawScope.drawWithMotion(
    idx: Int,
    t: Float,
    block: DrawScope.() -> Unit,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2
    when (idx % 5) {
        0 -> {
            val bounce = kotlin.math.abs(sinF(PI2 * t / 2f))
            drawCircle(
                DARK.copy(alpha = 0.10f + 0.12f * (1f - bounce)),
                w * (0.16f + 0.08f * (1f - bounce)),
                Offset(cx, h * 0.88f),
            )
            translate(0f, -bounce * h * 0.12f) { block() }
        }
        1 -> rotate(t * 360f, Offset(cx, cy)) { block() }
        2 -> scale(1f + 0.07f * sinF(PI2 * t), Offset(cx, cy)) { block() }
        3 -> rotate(sinF(PI2 * t) * 12f, Offset(cx, cy)) { block() }
        else -> translate(sinF(PI2 * t) * w * 0.06f, cosF(PI2 * t) * h * 0.04f) { block() }
    }
}

// ── палитра и примитивы ──────────────────────────────────────────────────────

private val DARK = Color(0xFF1E2430)
private val WHITE = Color.White
private val RED = Color(0xFFE04B3A)
private val RED_D = Color(0xFF8E2418)
private val ORANGE = Color(0xFFF08A24)
private val YELLOW = Color(0xFFF6C445)
private val GOLD = Color(0xFFE0A32E)
private val GREEN = Color(0xFF57A65A)
private val GREEN_D = Color(0xFF2E6B31)
private val TEAL = Color(0xFF35B5A9)
private val SKY = Color(0xFF7FB2E5)
private val BLUE = Color(0xFF3E77C9)
private val BLUE_D = Color(0xFF24476F)
private val VIOLET = Color(0xFF8E6BD9)
private val PINK = Color(0xFFE56A9A)
private val BROWN = Color(0xFF9C6B3F)
private val GRAY = Color(0xFF8B94A3)
private val GRAY_D = Color(0xFF525A68)
private val GRAY_L = Color(0xFFC7CEDA)
private val SKIN = Color(0xFFEBB58C)
private val HOLE = Color(0xFFE8EEF5)

private fun DrawScope.oval(color: Color, l: Float, t: Float, r: Float, b: Float) {
    drawOval(color, Offset(l, t), Size(r - l, b - t))
}

private fun DrawScope.oval(brush: Brush, l: Float, t: Float, r: Float, b: Float) {
    drawOval(brush, Offset(l, t), Size(r - l, b - t))
}

private fun DrawScope.rect(color: Color, l: Float, t: Float, r: Float, b: Float) {
    drawRect(color, Offset(l, t), Size(r - l, b - t))
}

private fun DrawScope.rect(brush: Brush, l: Float, t: Float, r: Float, b: Float) {
    drawRect(brush, Offset(l, t), Size(r - l, b - t))
}

private fun DrawScope.line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, sw: Float) {
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = sw)
}

private fun DrawScope.vb(c1: Color, c2: Color, c3: Color, cx: Float, cy: Float, r: Float): Brush =
    Brush.linearGradient(listOf(c1, c2, c3), start = Offset(cx, cy - r), end = Offset(cx, cy + r))

private fun DrawScope.rb(c1: Color, c2: Color, c3: Color, cx: Float, cy: Float, r: Float): Brush =
    Brush.radialGradient(
        listOf(c1, c2, c3),
        center = Offset(cx - r * 0.4f, cy - r * 0.4f),
        radius = r * 2.1f,
    )

/** Стеклянный блик. */
private fun DrawScope.gloss(cx: Float, cy: Float, r: Float) {
    oval(WHITE.copy(alpha = 0.5f), cx - r * 0.62f, cy - r * 0.78f, cx + r * 0.02f, cy - r * 0.28f)
}

/** Бегущий перелив. */
private fun DrawScope.shimmer(t: Float, cx: Float, cy: Float, r: Float) {
    val a = (sinF(PI2 * t) + 1f) / 2f
    val x = cx - r * 1.1f + a * r * 1.6f
    rotate(-20f, Offset(cx, cy)) {
        oval(WHITE.copy(alpha = 0.10f + 0.20f * a), x, cy - r, x + r * 0.45f, cy + r)
    }
}

private fun DrawScope.tri(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
    drawPath(
        Path().apply {
            moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close()
        },
        color,
    )
}

private fun DrawScope.arcStroke(color: Color, x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, sw: Float) {
    drawPath(
        Path().apply {
            moveTo(x1, y1); quadraticBezierTo(cx, cy, x2, y2)
        },
        color,
        style = Stroke(width = sw),
    )
}

// ── 100 форм ─────────────────────────────────────────────────────────────────

private val DRAWERS: Map<String, DrawScope.(Float) -> Unit> = mapOf(

    "chat" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2 - size.height * 0.06f
        val r = w * (0.30f + 0.02f * sinF(PI2 * t))
        drawCircle(rb(Color(0xFFFFD87A), GOLD, Color(0xFF9C6B12), cx, cy, r), r, Offset(cx, cy))
        tri(GOLD, cx - r * 0.4f, cy + r * 0.75f, cx - r * 0.15f, cy + r * 1.45f, cx + r * 0.35f, cy + r * 0.85f)
        drawCircle(WHITE, r * 0.15f, Offset(cx - r * 0.4f, cy))
        drawCircle(WHITE, r * 0.15f, Offset(cx, cy))
        drawCircle(WHITE, r * 0.15f, Offset(cx + r * 0.4f, cy))
        gloss(cx, cy, r)
    },

    "bolt" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        val a = 0.75f + 0.25f * sinF(PI2 * t)
        drawPath(
            Path().apply {
                moveTo(cx + w * 0.10f, cy - h * 0.38f)
                lineTo(cx - w * 0.16f, cy + h * 0.06f)
                lineTo(cx - w * 0.02f, cy + h * 0.06f)
                lineTo(cx - w * 0.10f, cy + h * 0.38f)
                lineTo(cx + w * 0.16f, cy - h * 0.06f)
                lineTo(cx + w * 0.02f, cy - h * 0.06f)
                close()
            },
            vb(Color(0xFFFFF3B0), YELLOW, ORANGE, cx, cy, w * 0.4f),
            alpha = a,
        )
    },

    "mic" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        oval(rb(GRAY_L, GRAY, GRAY_D, cx, h * 0.34f, w * 0.2f), cx - w * 0.13f, h * 0.12f, cx + w * 0.13f, h * 0.52f)
        arcStroke(GRAY_D, cx - w * 0.20f, h * 0.40f, cx, h * 0.72f, cx + w * 0.20f, h * 0.40f, w * 0.04f)
        line(GRAY_D, cx, h * 0.66f, cx, h * 0.80f, w * 0.04f)
        line(GRAY_D, cx - w * 0.14f, h * 0.84f, cx + w * 0.14f, h * 0.84f, w * 0.05f)
        gloss(cx, h * 0.30f, w * 0.16f)
    },

    "top" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        tri(vb(SKY, BLUE, BLUE_D, cx, h * 0.3f, w * 0.3f), cx, h * 0.10f, cx - w * 0.26f, h * 0.44f, cx + w * 0.26f, h * 0.44f)
        rect(vb(SKY, BLUE, BLUE_D, cx, h * 0.6f, w * 0.2f), cx - w * 0.12f, h * 0.44f, cx + w * 0.12f, h * 0.86f)
        gloss(cx, h * 0.3f, w * 0.2f)
    },

    "cool" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        drawCircle(rb(Color(0xFFFFE29A), YELLOW, ORANGE, cx, cy, w * 0.34f), w * 0.34f, Offset(cx, cy))
        rect(DARK, cx - w * 0.26f, cy - w * 0.12f, cx - w * 0.04f, cy + w * 0.04f)
        rect(DARK, cx + w * 0.04f, cy - w * 0.12f, cx + w * 0.26f, cy + w * 0.04f)
        line(DARK, cx - w * 0.04f, cy - w * 0.06f, cx + w * 0.04f, cy - w * 0.06f, w * 0.03f)
        arcStroke(DARK, cx - w * 0.14f, cy + w * 0.14f, cx, cy + w * 0.26f, cx + w * 0.14f, cy + w * 0.14f, w * 0.03f)
        gloss(cx, cy - w * 0.1f, w * 0.3f)
    },

    "bang" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(Color(0xFFFF8A7A), RED, RED_D, cx, h * 0.4f, w * 0.1f), cx - w * 0.07f, h * 0.12f, cx + w * 0.07f, h * 0.62f)
        drawCircle(rb(Color(0xFFFF8A7A), RED, RED_D, cx, h * 0.78f, w * 0.1f), w * 0.09f, Offset(cx, h * 0.78f))
        gloss(cx, h * 0.3f, w * 0.14f)
    },

    "memo" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(WHITE, Color(0xFFF2F5F9), GRAY_L, cx, h * 0.5f, w * 0.3f), cx - w * 0.26f, h * 0.16f, cx + w * 0.26f, h * 0.84f)
        line(GRAY, cx - w * 0.16f, h * 0.34f, cx + w * 0.16f, h * 0.34f, w * 0.025f)
        line(GRAY, cx - w * 0.16f, h * 0.48f, cx + w * 0.16f, h * 0.48f, w * 0.025f)
        line(GRAY, cx - w * 0.16f, h * 0.62f, cx + w * 0.06f, h * 0.62f, w * 0.025f)
        rect(ORANGE, cx + w * 0.02f, h * 0.56f, cx + w * 0.24f, h * 0.68f)
        tri(YELLOW, cx + w * 0.02f, h * 0.56f, cx + w * 0.02f, h * 0.68f, cx - w * 0.06f, h * 0.62f)
    },

    "calendar" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(WHITE, Color(0xFFF2F5F9), GRAY_L, cx, h * 0.5f, w * 0.3f), cx - w * 0.30f, h * 0.22f, cx + w * 0.30f, h * 0.84f)
        rect(vb(Color(0xFFFF8A7A), RED, RED_D, cx, h * 0.28f, w * 0.3f), cx - w * 0.30f, h * 0.22f, cx + w * 0.30f, h * 0.40f)
        line(GRAY_D, cx - w * 0.16f, h * 0.14f, cx - w * 0.16f, h * 0.26f, w * 0.04f)
        line(GRAY_D, cx + w * 0.16f, h * 0.14f, cx + w * 0.16f, h * 0.26f, w * 0.04f)
        rect(RED, cx - w * 0.14f, h * 0.52f, cx + w * 0.02f, h * 0.68f)
        rect(GRAY_L, cx + w * 0.08f, h * 0.52f, cx + w * 0.20f, h * 0.60f)
        rect(GRAY_L, cx - w * 0.14f, h * 0.72f, cx - w * 0.02f, h * 0.80f)
    },

    "folder" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(Color(0xFFFFD87A), YELLOW, ORANGE, cx, h * 0.3f, w * 0.3f), cx - w * 0.30f, h * 0.24f, cx - w * 0.02f, h * 0.38f)
        rect(vb(Color(0xFFFFD87A), GOLD, Color(0xFF9C6B12), cx, h * 0.5f, w * 0.34f), cx - w * 0.30f, h * 0.32f, cx + w * 0.30f, h * 0.80f)
        rect(WHITE.copy(alpha = 0.35f), cx - w * 0.30f, h * 0.40f, cx + w * 0.30f, h * 0.46f)
        gloss(cx - w * 0.1f, h * 0.4f, w * 0.24f)
    },

    "search" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2 - w * 0.06f; val cy = h / 2 - h * 0.06f
        drawCircle(WHITE.copy(alpha = 0.7f), w * 0.20f, Offset(cx, cy))
        drawCircle(BLUE, w * 0.24f, Offset(cx, cy), style = Stroke(width = w * 0.06f))
        line(BLUE_D, cx + w * 0.17f, cy + w * 0.17f, cx + w * 0.34f, cy + w * 0.34f, w * 0.07f)
        val a = (sinF(PI2 * t) + 1f) / 2f
        oval(WHITE.copy(alpha = 0.3f + 0.4f * a), cx - w * 0.12f, cy - w * 0.14f, cx - w * 0.02f, cy - w * 0.04f)
    },

    "horn" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        tri(vb(Color(0xFFFFD87A), GOLD, Color(0xFF9C6B12), cx, h * 0.5f, w * 0.3f), cx - w * 0.24f, h * 0.44f, cx - w * 0.24f, h * 0.60f, cx + w * 0.16f, h * 0.20f)
        tri(vb(Color(0xFFFFD87A), GOLD, Color(0xFF9C6B12), cx, h * 0.5f, w * 0.3f), cx - w * 0.24f, h * 0.44f, cx - w * 0.24f, h * 0.60f, cx + w * 0.16f, h * 0.84f)
        rect(GOLD, cx - w * 0.34f, h * 0.44f, cx - w * 0.24f, h * 0.60f)
        line(ORANGE, cx + w * 0.22f, h * 0.34f, cx + w * 0.32f, h * 0.26f, w * 0.03f)
        line(ORANGE, cx + w * 0.26f, h * 0.52f, cx + w * 0.36f, h * 0.52f, w * 0.03f)
        line(ORANGE, cx + w * 0.22f, h * 0.70f, cx + w * 0.32f, h * 0.78f, w * 0.03f)
    },

    "flame" to { t ->
        val w = size.width; val cy = size.height / 2 + size.height * 0.08f; val cx = w / 2
        val wob = sinF(PI2 * t) * w * 0.05f
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.20f, cy + w * 0.20f)
                quadraticBezierTo(cx - w * 0.22f + wob, cy - w * 0.08f, cx + wob, cy - w * 0.34f)
                quadraticBezierTo(cx + w * 0.22f + wob, cy - w * 0.08f, cx + w * 0.20f, cy + w * 0.20f)
                close()
            },
            vb(ORANGE, RED, RED_D, cx, cy, w * 0.4f),
        )
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.10f, cy + w * 0.18f)
                quadraticBezierTo(cx - w * 0.11f - wob * 0.6f, cy + w * 0.02f, cx - wob * 0.6f, cy - w * 0.12f)
                quadraticBezierTo(cx + w * 0.11f - wob * 0.6f, cy + w * 0.02f, cx + w * 0.10f, cy + w * 0.18f)
                close()
            },
            YELLOW,
        )
        shimmer(t, cx, cy, w * 0.3f)
    },

    "heart" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        val rr = w * 0.19f
        val s = 1f + 0.10f * kotlin.math.max(0f, sinF(PI2 * t))
        scale(s, Offset(cx, cy)) {
            drawPath(
                Path().apply {
                    moveTo(cx - rr * 1.85f, cy - rr * 0.15f)
                    lineTo(cx + rr * 1.85f, cy - rr * 0.15f)
                    lineTo(cx, cy + rr * 2.1f)
                    close()
                },
                vb(Color(0xFFFF8A7A), RED, RED_D, cx, cy, w * 0.4f),
            )
            drawCircle(vb(Color(0xFFFF8A7A), RED, RED_D, cx, cy, w * 0.4f), rr, Offset(cx - rr * 0.95f, cy - rr * 0.6f))
            drawCircle(vb(Color(0xFFFF8A7A), RED, RED_D, cx, cy, w * 0.4f), rr, Offset(cx + rr * 0.95f, cy - rr * 0.6f))
            gloss(cx - rr * 0.8f, cy - rr * 0.7f, rr)
        }
    },

    "ask" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        arcStroke(RED, cx - w * 0.16f, h * 0.34f, cx, h * 0.10f, cx + w * 0.16f, h * 0.34f, w * 0.07f)
        line(RED, cx + w * 0.16f, h * 0.34f, cx, h * 0.52f, w * 0.07f)
        line(RED, cx, h * 0.52f, cx, h * 0.64f, w * 0.07f)
        drawCircle(RED, w * 0.06f, Offset(cx, h * 0.80f))
    },

    "chartup" to { _ ->
        val w = size.width; val h = size.height
        line(GRAY_D, w * 0.16f, h * 0.16f, w * 0.16f, h * 0.84f, w * 0.03f)
        line(GRAY_D, w * 0.16f, h * 0.84f, w * 0.86f, h * 0.84f, w * 0.03f)
        line(GREEN, w * 0.22f, h * 0.70f, w * 0.44f, h * 0.46f, w * 0.05f)
        line(GREEN, w * 0.44f, h * 0.46f, w * 0.58f, h * 0.58f, w * 0.05f)
        line(GREEN, w * 0.58f, h * 0.58f, w * 0.80f, h * 0.26f, w * 0.05f)
        tri(GREEN, w * 0.80f, h * 0.26f, w * 0.66f, h * 0.26f, w * 0.78f, h * 0.40f)
    },

    "chartdown" to { _ ->
        val w = size.width; val h = size.height
        line(GRAY_D, w * 0.16f, h * 0.16f, w * 0.16f, h * 0.84f, w * 0.03f)
        line(GRAY_D, w * 0.16f, h * 0.84f, w * 0.86f, h * 0.84f, w * 0.03f)
        line(RED, w * 0.22f, h * 0.30f, w * 0.44f, h * 0.54f, w * 0.05f)
        line(RED, w * 0.44f, h * 0.54f, w * 0.58f, h * 0.42f, w * 0.05f)
        line(RED, w * 0.58f, h * 0.42f, w * 0.80f, h * 0.74f, w * 0.05f)
        tri(RED, w * 0.80f, h * 0.74f, w * 0.66f, h * 0.74f, w * 0.78f, h * 0.60f)
    },

    "gem" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        val r = w * 0.32f
        val top = cy - r * 0.75f; val mid = cy - r * 0.15f; val bot = cy + r
        drawPath(
            Path().apply {
                moveTo(cx - r * 0.55f, top); lineTo(cx + r * 0.55f, top); lineTo(cx + r * 0.95f, mid)
                lineTo(cx, bot); lineTo(cx - r * 0.95f, mid); close()
            },
            vb(SKY, TEAL, BLUE_D, cx, cy, r),
        )
        tri(WHITE.copy(alpha = 0.35f), cx - r * 0.55f, top, cx - r * 0.25f, mid, cx - r * 0.95f, mid)
        tri(BLUE_D.copy(alpha = 0.45f), cx + r * 0.55f, top, cx + r * 0.25f, mid, cx + r * 0.95f, mid)
        tri(WHITE.copy(alpha = 0.30f), cx - r * 0.25f, mid, cx + r * 0.25f, mid, cx, bot)
        val tw = (sinF(PI2 * t) + 1f) / 2f
        line(WHITE.copy(alpha = 0.4f + 0.6f * tw), cx - r * 0.3f, top + r * 0.2f, cx + r * 0.05f, top + r * 0.2f, w * 0.03f)
    },

    "moneybag" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawCircle(rb(Color(0xFFFFD87A), GOLD, Color(0xFF9C6B12), cx, h * 0.58f, w * 0.28f), w * 0.28f, Offset(cx, h * 0.58f))
        rect(GOLD, cx - w * 0.10f, h * 0.24f, cx + w * 0.10f, h * 0.34f)
        tri(Color(0xFF9C6B12), cx - w * 0.14f, h * 0.24f, cx + w * 0.14f, h * 0.24f, cx, h * 0.14f)
        line(Color(0xFF9C6B12), cx, h * 0.44f, cx, h * 0.72f, w * 0.05f)
        line(Color(0xFF9C6B12), cx - w * 0.10f, h * 0.50f, cx + w * 0.10f, h * 0.50f, w * 0.04f)
        line(Color(0xFF9C6B12), cx - w * 0.10f, h * 0.66f, cx + w * 0.10f, h * 0.66f, w * 0.04f)
        gloss(cx, h * 0.5f, w * 0.26f)
    },

    "wings" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        val flap = sinF(PI2 * t) * 18f
        rotate(-flap, Offset(cx - w * 0.22f, cy)) {
            tri(WHITE, cx - w * 0.20f, cy - w * 0.02f, cx - w * 0.44f, cy - w * 0.22f, cx - w * 0.30f, cy + w * 0.10f)
        }
        rotate(flap, Offset(cx + w * 0.22f, cy)) {
            tri(WHITE, cx + w * 0.20f, cy - w * 0.02f, cx + w * 0.44f, cy - w * 0.22f, cx + w * 0.30f, cy + w * 0.10f)
        }
        rect(vb(Color(0xFFB7E2A5), GREEN, GREEN_D, cx, cy, w * 0.24f), cx - w * 0.22f, cy - w * 0.13f, cx + w * 0.22f, cy + w * 0.13f)
        drawCircle(GREEN_D, w * 0.08f, Offset(cx, cy))
        gloss(cx, cy - w * 0.04f, w * 0.2f)
    },

    "coin" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        drawCircle(rb(Color(0xFFFFF3B0), YELLOW, ORANGE, cx, cy, w * 0.32f), w * 0.32f, Offset(cx, cy))
        drawCircle(ORANGE, w * 0.24f, Offset(cx, cy), style = Stroke(width = w * 0.03f))
        line(ORANGE, cx, cy - w * 0.14f, cx, cy + w * 0.14f, w * 0.04f)
        line(ORANGE, cx - w * 0.08f, cy - w * 0.06f, cx + w * 0.08f, cy - w * 0.06f, w * 0.03f)
        line(ORANGE, cx - w * 0.08f, cy + w * 0.06f, cx + w * 0.08f, cy + w * 0.06f, w * 0.03f)
        gloss(cx, cy, w * 0.3f)
        shimmer(t, cx, cy, w * 0.32f)
    },

    "exchange" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(GREEN, cx - w * 0.24f, h * 0.26f, cx + w * 0.14f, h * 0.38f)
        tri(GREEN, cx + w * 0.14f, h * 0.20f, cx + w * 0.14f, h * 0.44f, cx + w * 0.30f, h * 0.32f)
        rect(BLUE, cx - w * 0.14f, h * 0.58f, cx + w * 0.24f, h * 0.70f)
        tri(BLUE, cx - w * 0.14f, h * 0.52f, cx - w * 0.14f, h * 0.76f, cx - w * 0.30f, h * 0.64f)
    },

    "pad" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        oval(rb(GRAY_L, GRAY, GRAY_D, cx, cy, w * 0.34f), cx - w * 0.34f, cy - w * 0.18f, cx + w * 0.34f, cy + w * 0.20f)
        rect(DARK, cx - w * 0.24f, cy - w * 0.03f, cx - w * 0.10f, cy + w * 0.03f)
        rect(DARK, cx - w * 0.20f, cy - w * 0.07f, cx - w * 0.14f, cy + w * 0.07f)
        drawCircle(RED, w * 0.035f, Offset(cx + w * 0.14f, cy - w * 0.05f))
        drawCircle(BLUE, w * 0.035f, Offset(cx + w * 0.22f, cy + w * 0.02f))
        gloss(cx, cy - w * 0.08f, w * 0.28f)
    },

    "laptop" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(SKY, BLUE, BLUE_D, cx, h * 0.4f, w * 0.3f), cx - w * 0.24f, h * 0.20f, cx + w * 0.24f, h * 0.62f)
        rect(SKY.copy(alpha = 0.6f), cx - w * 0.20f, h * 0.24f, cx + w * 0.20f, h * 0.58f)
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.32f, h * 0.78f); lineTo(cx - w * 0.24f, h * 0.62f)
                lineTo(cx + w * 0.24f, h * 0.62f); lineTo(cx + w * 0.32f, h * 0.78f); close()
            },
            GRAY_L,
        )
    },

    "phone" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(GRAY_L, GRAY, GRAY_D, cx, h * 0.5f, w * 0.24f), cx - w * 0.16f, h * 0.14f, cx + w * 0.16f, h * 0.86f)
        rect(vb(SKY, BLUE, BLUE_D, cx, h * 0.5f, w * 0.2f), cx - w * 0.12f, h * 0.22f, cx + w * 0.12f, h * 0.72f)
        drawCircle(GRAY_L, w * 0.03f, Offset(cx, h * 0.79f))
        gloss(cx, h * 0.4f, w * 0.16f)
    },

    "car" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.20f, h * 0.50f)
                quadraticBezierTo(cx - w * 0.14f, h * 0.30f, cx, h * 0.30f)
                quadraticBezierTo(cx + w * 0.14f, h * 0.30f, cx + w * 0.20f, h * 0.50f)
                close()
            },
            SKY,
        )
        rect(vb(Color(0xFFFF8A7A), RED, RED_D, cx, h * 0.58f, w * 0.34f), cx - w * 0.34f, h * 0.48f, cx + w * 0.34f, h * 0.68f)
        drawCircle(DARK, w * 0.08f, Offset(cx - w * 0.18f, h * 0.70f))
        drawCircle(DARK, w * 0.08f, Offset(cx + w * 0.18f, h * 0.70f))
        drawCircle(GRAY_L, w * 0.03f, Offset(cx - w * 0.18f, h * 0.70f))
        drawCircle(GRAY_L, w * 0.03f, Offset(cx + w * 0.18f, h * 0.70f))
        gloss(cx, h * 0.52f, w * 0.26f)
    },

    "house" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        tri(vb(ORANGE, RED, RED_D, cx, h * 0.3f, w * 0.36f), cx, h * 0.12f, cx - w * 0.34f, h * 0.46f, cx + w * 0.34f, h * 0.46f)
        rect(vb(Color(0xFFFFE29A), YELLOW, ORANGE, cx, h * 0.66f, w * 0.28f), cx - w * 0.26f, h * 0.46f, cx + w * 0.26f, h * 0.86f)
        rect(BROWN, cx - w * 0.06f, h * 0.62f, cx + w * 0.06f, h * 0.86f)
        rect(SKY, cx + w * 0.10f, h * 0.54f, cx + w * 0.20f, h * 0.64f)
    },

    "heartarrow" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        val rr = w * 0.17f
        drawPath(
            Path().apply {
                moveTo(cx - rr * 1.85f, cy - rr * 0.15f); lineTo(cx + rr * 1.85f, cy - rr * 0.15f); lineTo(cx, cy + rr * 2.1f); close()
            },
            vb(PINK, PINK, RED, cx, cy, w * 0.4f),
        )
        drawCircle(PINK, rr, Offset(cx - rr * 0.95f, cy - rr * 0.6f))
        drawCircle(PINK, rr, Offset(cx + rr * 0.95f, cy - rr * 0.6f))
        val dx = sinF(PI2 * t) * w * 0.05f
        line(BLUE, cx - w * 0.34f + dx, cy + w * 0.30f, cx + w * 0.30f + dx, cy - w * 0.26f, w * 0.035f)
        tri(BLUE, cx + w * 0.30f + dx, cy - w * 0.26f, cx + w * 0.14f + dx, cy - w * 0.24f, cx + w * 0.28f + dx, cy - w * 0.10f)
    },

    "party" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        tri(vb(PINK, VIOLET, BLUE_D, cx - w * 0.1f, h * 0.6f, w * 0.3f), cx - w * 0.22f, h * 0.86f, cx + w * 0.02f, h * 0.78f, cx - w * 0.06f, h * 0.40f)
        for (i in 0..4) {
            val p = (t + i / 5f) % 1f
            val px = cx + w * 0.02f + i * w * 0.07f - w * 0.10f + sinF(p * 6f) * w * 0.04f
            val py = h * 0.36f - p * h * 0.24f
            drawCircle(listOf(RED, YELLOW, GREEN, BLUE, PINK)[i], w * 0.03f, Offset(px, py))
        }
        gloss(cx - w * 0.1f, h * 0.7f, w * 0.14f)
    },

    "bang2" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rotate(-8f, Offset(cx - w * 0.12f, h / 2)) {
            rect(RED, cx - w * 0.20f, h * 0.14f, cx - w * 0.08f, h * 0.60f)
            drawCircle(RED, w * 0.07f, Offset(cx - w * 0.14f, h * 0.76f))
        }
        rotate(8f, Offset(cx + w * 0.12f, h / 2)) {
            rect(ORANGE, cx + w * 0.08f, h * 0.14f, cx + w * 0.20f, h * 0.60f)
            drawCircle(ORANGE, w * 0.07f, Offset(cx + w * 0.14f, h * 0.76f))
        }
    },

    "trophy" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        oval(rb(Color(0xFFFFF3B0), YELLOW, ORANGE, cx, h * 0.34f, w * 0.22f), cx - w * 0.20f, h * 0.14f, cx + w * 0.20f, h * 0.50f)
        arcStroke(GOLD, cx - w * 0.26f, h * 0.20f, cx - w * 0.34f, h * 0.36f, cx - w * 0.18f, h * 0.44f, w * 0.03f)
        arcStroke(GOLD, cx + w * 0.26f, h * 0.20f, cx + w * 0.34f, h * 0.36f, cx + w * 0.18f, h * 0.44f, w * 0.03f)
        rect(GOLD, cx - w * 0.05f, h * 0.50f, cx + w * 0.05f, h * 0.66f)
        rect(ORANGE, cx - w * 0.14f, h * 0.66f, cx + w * 0.14f, h * 0.76f)
        rect(BROWN, cx - w * 0.18f, h * 0.76f, cx + w * 0.18f, h * 0.86f)
        gloss(cx, h * 0.3f, w * 0.18f)
        shimmer(t, cx, h * 0.34f, w * 0.2f)
    },

    "checkflag" to { t ->
        val w = size.width; val h = size.height
        line(GRAY_D, w * 0.20f, h * 0.12f, w * 0.20f, h * 0.88f, w * 0.04f)
        val sw = w * 0.14f
        val wave = sinF(PI2 * t) * w * 0.02f
        for (ry in 0..2) {
            for (rx in 0..3) {
                val dark = (rx + ry) % 2 == 0
                rect(
                    if (dark) DARK else WHITE,
                    w * 0.24f + rx * sw, h * 0.16f + ry * sw + wave * (rx / 3f),
                    w * 0.24f + (rx + 1) * sw, h * 0.16f + (ry + 1) * sw + wave * (rx / 3f),
                )
            }
        }
    },

    "clapper" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(GRAY_L, GRAY, GRAY_D, cx, h * 0.66f, w * 0.32f), cx - w * 0.32f, h * 0.46f, cx + w * 0.32f, h * 0.84f)
        rotate(-12f, Offset(cx - w * 0.30f, h * 0.42f)) {
            rect(DARK, cx - w * 0.32f, h * 0.30f, cx + w * 0.32f, h * 0.44f)
            tri(YELLOW, cx - w * 0.24f, h * 0.30f, cx - w * 0.12f, h * 0.30f, cx - w * 0.18f, h * 0.44f)
            tri(YELLOW, cx + w * 0.00f, h * 0.30f, cx + w * 0.12f, h * 0.30f, cx + w * 0.06f, h * 0.44f)
            tri(YELLOW, cx + w * 0.24f, h * 0.30f, cx + w * 0.36f, h * 0.30f, cx + w * 0.30f, h * 0.44f)
        }
    },

    "note" to { t ->
        val w = size.width; val h = size.height
        val bob = (sinF(PI2 * t) * 0.5f + 0.5f) * h * 0.12f
        val y = h * 0.68f - bob
        val nx = w / 2 - w * 0.06f
        drawCircle(rb(SKY, BLUE, BLUE_D, nx, y, w * 0.14f), w * 0.12f, Offset(nx, y))
        line(BLUE, nx + w * 0.11f, y, nx + w * 0.11f, y - h * 0.38f, w * 0.045f)
        drawPath(
            Path().apply {
                moveTo(nx + w * 0.11f, y - h * 0.38f)
                quadraticBezierTo(nx + w * 0.32f, y - h * 0.30f, nx + w * 0.27f, y - h * 0.15f)
                lineTo(nx + w * 0.11f, y - h * 0.24f)
                close()
            },
            vb(SKY, BLUE, BLUE_D, nx, y - h * 0.2f, w * 0.2f),
        )
        gloss(nx, y, w * 0.14f)
    },

    "sun" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        rotate(t * 90f, Offset(cx, cy)) {
            for (i in 0 until 8) {
                rotate(i * 45f, Offset(cx, cy)) {
                    line(GOLD, cx, cy - w * 0.24f, cx, cy - w * 0.40f, w * 0.05f)
                }
            }
        }
        drawCircle(rb(Color(0xFFFFF3B0), YELLOW, ORANGE, cx, cy, w * 0.4f), w * 0.18f, Offset(cx, cy))
        gloss(cx, cy, w * 0.2f)
    },

    "books" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(RED, RED, RED_D, cx, h * 0.76f, w * 0.3f), cx - w * 0.30f, h * 0.68f, cx + w * 0.30f, h * 0.84f)
        rect(vb(SKY, BLUE, BLUE_D, cx, h * 0.60f, w * 0.28f), cx - w * 0.26f, h * 0.52f, cx + w * 0.26f, h * 0.68f)
        rect(vb(Color(0xFFB7E2A5), GREEN, GREEN_D, cx, h * 0.44f, w * 0.24f), cx - w * 0.22f, h * 0.36f, cx + w * 0.22f, h * 0.52f)
        line(WHITE, cx - w * 0.24f, h * 0.76f, cx + w * 0.24f, h * 0.76f, w * 0.02f)
        line(WHITE, cx - w * 0.20f, h * 0.60f, cx + w * 0.20f, h * 0.60f, w * 0.02f)
    },

    "crown" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.30f, h * 0.70f); lineTo(cx - w * 0.30f, h * 0.34f); lineTo(cx - w * 0.15f, h * 0.52f)
                lineTo(cx, h * 0.24f); lineTo(cx + w * 0.15f, h * 0.52f); lineTo(cx + w * 0.30f, h * 0.34f)
                lineTo(cx + w * 0.30f, h * 0.70f); close()
            },
            vb(Color(0xFFFFF3B0), GOLD, Color(0xFF9C6B12), cx, h * 0.5f, w * 0.34f),
        )
        rect(Color(0xFF9C6B12), cx - w * 0.30f, h * 0.70f, cx + w * 0.30f, h * 0.78f)
        drawCircle(RED, w * 0.035f, Offset(cx - w * 0.16f, h * 0.74f))
        drawCircle(SKY, w * 0.035f, Offset(cx, h * 0.74f))
        drawCircle(GREEN, w * 0.035f, Offset(cx + w * 0.16f, h * 0.74f))
        gloss(cx, h * 0.44f, w * 0.24f)
        shimmer(t, cx, h * 0.5f, w * 0.3f)
    },

    "soccer" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        drawCircle(rb(WHITE, Color(0xFFF2F5F9), GRAY_L, cx, cy, w * 0.32f), w * 0.32f, Offset(cx, cy))
        rotate(t * 180f, Offset(cx, cy)) {
            drawCircle(DARK, w * 0.07f, Offset(cx, cy))
            for (i in 0 until 5) {
                rotate(i * 72f, Offset(cx, cy)) {
                    drawCircle(DARK, w * 0.045f, Offset(cx, cy - w * 0.20f))
                }
            }
        }
        gloss(cx, cy, w * 0.3f)
    },

    "basket" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        drawCircle(rb(Color(0xFFFFC46B), ORANGE, Color(0xFFA34E08), cx, cy, w * 0.32f), w * 0.32f, Offset(cx, cy))
        rotate(t * 120f, Offset(cx, cy)) {
            line(RED_D, cx - w * 0.32f, cy, cx + w * 0.32f, cy, w * 0.025f)
            line(RED_D, cx, cy - w * 0.32f, cx, cy + w * 0.32f, w * 0.025f)
            arcStroke(RED_D, cx - w * 0.16f, cy - w * 0.28f, cx - w * 0.30f, cy, cx - w * 0.16f, cy + w * 0.28f, w * 0.025f)
            arcStroke(RED_D, cx + w * 0.16f, cy - w * 0.28f, cx + w * 0.30f, cy, cx + w * 0.16f, cy + w * 0.28f, w * 0.025f)
        }
        gloss(cx, cy, w * 0.3f)
    },

    "tv" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        line(GRAY_D, cx - w * 0.16f, h * 0.14f, cx - w * 0.02f, h * 0.28f, w * 0.03f)
        line(GRAY_D, cx + w * 0.16f, h * 0.14f, cx + w * 0.02f, h * 0.28f, w * 0.03f)
        rect(vb(GRAY_L, GRAY, GRAY_D, cx, h * 0.55f, w * 0.34f), cx - w * 0.32f, h * 0.28f, cx + w * 0.32f, h * 0.78f)
        rect(vb(SKY, BLUE, BLUE_D, cx, h * 0.53f, w * 0.28f), cx - w * 0.26f, h * 0.34f, cx + w * 0.26f, h * 0.72f)
        gloss(cx - w * 0.08f, h * 0.44f, w * 0.2f)
    },

    "eyes" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        val dx = sinF(PI2 * t) * w * 0.04f
        oval(WHITE, cx - w * 0.30f, cy - w * 0.18f, cx - w * 0.04f, cy + w * 0.18f)
        oval(WHITE, cx + w * 0.04f, cy - w * 0.18f, cx + w * 0.30f, cy + w * 0.18f)
        drawCircle(BLUE, w * 0.07f, Offset(cx - w * 0.17f + dx, cy))
        drawCircle(BLUE, w * 0.07f, Offset(cx + w * 0.17f + dx, cy))
        drawCircle(DARK, w * 0.03f, Offset(cx - w * 0.17f + dx, cy))
        drawCircle(DARK, w * 0.03f, Offset(cx + w * 0.17f + dx, cy))
    },

    "lips" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.32f, cy)
                quadraticBezierTo(cx - w * 0.16f, cy - w * 0.20f, cx, cy - w * 0.08f)
                quadraticBezierTo(cx + w * 0.16f, cy - w * 0.20f, cx + w * 0.32f, cy)
                close()
            },
            vb(PINK, RED, RED_D, cx, cy, w * 0.3f),
        )
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.32f, cy)
                quadraticBezierTo(cx, cy + w * 0.26f, cx + w * 0.32f, cy)
                close()
            },
            vb(RED, RED, RED_D, cx, cy + w * 0.1f, w * 0.2f),
        )
        gloss(cx - w * 0.1f, cy - w * 0.06f, w * 0.14f)
    },

    "strawberry" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.26f, h * 0.40f)
                quadraticBezierTo(cx - w * 0.30f, h * 0.70f, cx, h * 0.88f)
                quadraticBezierTo(cx + w * 0.30f, h * 0.70f, cx + w * 0.26f, h * 0.40f)
                quadraticBezierTo(cx, h * 0.30f, cx - w * 0.26f, h * 0.40f)
                close()
            },
            rb(Color(0xFFFF8A7A), RED, RED_D, cx, h * 0.55f, w * 0.3f),
        )
        tri(GREEN, cx - w * 0.14f, h * 0.34f, cx + w * 0.14f, h * 0.34f, cx, h * 0.46f)
        rect(GREEN, cx - w * 0.02f, h * 0.22f, cx + w * 0.02f, h * 0.36f)
        for (sx in -1..1) {
            drawCircle(YELLOW, w * 0.02f, Offset(cx + sx * w * 0.12f, h * 0.56f))
            drawCircle(YELLOW, w * 0.02f, Offset(cx + sx * w * 0.08f, h * 0.70f))
        }
        gloss(cx - w * 0.08f, h * 0.48f, w * 0.14f)
    },

    "lipstick" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(GRAY_L, GRAY, GRAY_D, cx, h * 0.74f, w * 0.14f), cx - w * 0.12f, h * 0.60f, cx + w * 0.12f, h * 0.88f)
        rect(GOLD, cx - w * 0.09f, h * 0.46f, cx + w * 0.09f, h * 0.60f)
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.07f, h * 0.46f); lineTo(cx - w * 0.07f, h * 0.20f)
                lineTo(cx + w * 0.07f, h * 0.12f); lineTo(cx + w * 0.07f, h * 0.46f); close()
            },
            vb(PINK, RED, RED_D, cx, h * 0.3f, w * 0.2f),
        )
        gloss(cx, h * 0.28f, w * 0.1f)
    },

    "heel" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.24f, h * 0.30f)
                quadraticBezierTo(cx - w * 0.10f, h * 0.56f, cx + w * 0.10f, h * 0.62f)
                quadraticBezierTo(cx + w * 0.28f, h * 0.68f, cx + w * 0.30f, h * 0.80f)
                lineTo(cx + w * 0.16f, h * 0.80f)
                quadraticBezierTo(cx + w * 0.10f, h * 0.70f, cx - w * 0.06f, h * 0.66f)
                lineTo(cx - w * 0.10f, h * 0.86f)
                lineTo(cx - w * 0.18f, h * 0.86f)
                lineTo(cx - w * 0.16f, h * 0.52f)
                close()
            },
            vb(PINK, RED, RED_D, cx, h * 0.55f, w * 0.34f),
        )
        gloss(cx - w * 0.12f, h * 0.42f, w * 0.12f)
    },

    "plane" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        val tilt = sinF(PI2 * t) * 6f
        rotate(tilt, Offset(cx, cy)) {
            drawPath(
                Path().apply {
                    moveTo(cx - w * 0.34f, cy); quadraticBezierTo(cx, cy - w * 0.12f, cx + w * 0.34f, cy - w * 0.02f)
                    lineTo(cx + w * 0.34f, cy + w * 0.04f); quadraticBezierTo(cx, cy + w * 0.12f, cx - w * 0.34f, cy)
                    close()
                },
                vb(WHITE, Color(0xFFF2F5F9), GRAY_L, cx, cy, w * 0.3f),
            )
            tri(vb(SKY, BLUE, BLUE_D, cx, cy, w * 0.2f), cx - w * 0.04f, cy - w * 0.02f, cx + w * 0.10f, cy - w * 0.30f, cx + w * 0.16f, cy - w * 0.02f)
            tri(SKY, cx - w * 0.34f, cy, cx - w * 0.22f, cy - w * 0.16f, cx - w * 0.18f, cy)
            for (i in 0..3) drawCircle(BLUE, w * 0.02f, Offset(cx - w * 0.16f + i * w * 0.10f, cy - w * 0.01f))
        }
    },

    "case" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        arcStroke(BROWN, cx - w * 0.10f, h * 0.32f, cx, h * 0.16f, cx + w * 0.10f, h * 0.32f, w * 0.04f)
        rect(vb(BROWN, BROWN, Color(0xFF6B4426), cx, h * 0.6f, w * 0.32f), cx - w * 0.30f, h * 0.32f, cx + w * 0.30f, h * 0.84f)
        line(GOLD, cx - w * 0.14f, h * 0.32f, cx - w * 0.14f, h * 0.84f, w * 0.03f)
        line(GOLD, cx + w * 0.14f, h * 0.32f, cx + w * 0.14f, h * 0.84f, w * 0.03f)
        gloss(cx, h * 0.46f, w * 0.24f)
    },

    "island" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        val wave = sinF(PI2 * t) * w * 0.03f
        oval(vb(SKY, BLUE, BLUE_D, cx, h * 0.84f, w * 0.3f), cx - w * 0.36f + wave, h * 0.76f, cx + w * 0.36f + wave, h * 0.92f)
        oval(YELLOW, cx - w * 0.22f, h * 0.66f, cx + w * 0.22f, h * 0.80f)
        arcStroke(BROWN, cx, h * 0.68f, cx + w * 0.06f, h * 0.44f, cx + w * 0.02f, h * 0.30f, w * 0.04f)
        arcStroke(GREEN, cx + w * 0.02f, h * 0.30f, cx - w * 0.16f, h * 0.22f, cx - w * 0.26f, h * 0.34f, w * 0.04f)
        arcStroke(GREEN, cx + w * 0.02f, h * 0.30f, cx + w * 0.20f, h * 0.20f, cx + w * 0.30f, h * 0.32f, w * 0.04f)
        arcStroke(GREEN_D, cx + w * 0.02f, h * 0.30f, cx + w * 0.02f, h * 0.16f, cx - w * 0.02f, h * 0.12f, w * 0.04f)
    },

    "suncloud" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        rotate(t * 60f, Offset(cx - w * 0.10f, h * 0.34f)) {
            for (i in 0 until 8) {
                rotate(i * 45f, Offset(cx - w * 0.10f, h * 0.34f)) {
                    line(GOLD, cx - w * 0.10f, h * 0.34f - w * 0.16f, cx - w * 0.10f, h * 0.34f - w * 0.26f, w * 0.04f)
                }
            }
        }
        drawCircle(YELLOW, w * 0.14f, Offset(cx - w * 0.10f, h * 0.34f))
        val dx = sinF(PI2 * t) * w * 0.03f
        drawCircle(WHITE, w * 0.12f, Offset(cx - w * 0.12f + dx, h * 0.66f))
        drawCircle(WHITE, w * 0.15f, Offset(cx + w * 0.04f + dx, h * 0.60f))
        drawCircle(WHITE, w * 0.12f, Offset(cx + w * 0.18f + dx, h * 0.68f))
        rect(WHITE, cx - w * 0.12f + dx, h * 0.66f, cx + w * 0.18f + dx, h * 0.78f)
    },

    "unicorn" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawCircle(rb(WHITE, Color(0xFFF2F5F9), GRAY_L, cx, h * 0.55f, w * 0.24f), w * 0.24f, Offset(cx, h * 0.55f))
        tri(vb(Color(0xFFFFF3B0), GOLD, ORANGE, cx, h * 0.2f, w * 0.1f), cx - w * 0.05f, h * 0.36f, cx + w * 0.05f, h * 0.36f, cx, h * 0.10f)
        arcStroke(PINK, cx - w * 0.20f, h * 0.40f, cx - w * 0.32f, h * 0.56f, cx - w * 0.22f, h * 0.74f, w * 0.05f)
        arcStroke(VIOLET, cx - w * 0.14f, h * 0.36f, cx - w * 0.26f, h * 0.52f, cx - w * 0.16f, h * 0.70f, w * 0.05f)
        drawCircle(DARK, w * 0.03f, Offset(cx + w * 0.08f, h * 0.52f))
        oval(PINK, cx + w * 0.02f, h * 0.64f, cx + w * 0.12f, h * 0.70f)
    },

    "shops" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        val sway = sinF(PI2 * t) * 4f
        rotate(sway, Offset(cx - w * 0.12f, h * 0.2f)) {
            arcStroke(PINK, cx - w * 0.20f, h * 0.40f, cx - w * 0.12f, h * 0.24f, cx - w * 0.04f, h * 0.40f, w * 0.03f)
            drawPath(
                Path().apply {
                    moveTo(cx - w * 0.26f, h * 0.40f); lineTo(cx - w * 0.02f, h * 0.40f)
                    lineTo(cx + w * 0.02f, h * 0.76f); lineTo(cx - w * 0.30f, h * 0.76f); close()
                },
                vb(PINK, PINK, VIOLET, cx - w * 0.14f, h * 0.58f, w * 0.2f),
            )
        }
        rotate(-sway, Offset(cx + w * 0.12f, h * 0.2f)) {
            arcStroke(ORANGE, cx + w * 0.04f, h * 0.44f, cx + w * 0.12f, h * 0.28f, cx + w * 0.20f, h * 0.44f, w * 0.03f)
            drawPath(
                Path().apply {
                    moveTo(cx + w * 0.00f, h * 0.44f); lineTo(cx + w * 0.26f, h * 0.44f)
                    lineTo(cx + w * 0.30f, h * 0.84f); lineTo(cx - w * 0.04f, h * 0.84f); close()
                },
                vb(YELLOW, ORANGE, Color(0xFFA34E08), cx + w * 0.13f, h * 0.64f, w * 0.2f),
            )
        }
    },

    "handbag" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        arcStroke(BROWN, cx - w * 0.12f, h * 0.42f, cx, h * 0.18f, cx + w * 0.12f, h * 0.42f, w * 0.04f)
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.26f, h * 0.42f); lineTo(cx + w * 0.26f, h * 0.42f)
                quadraticBezierTo(cx + w * 0.30f, h * 0.84f, cx, h * 0.84f)
                quadraticBezierTo(cx - w * 0.30f, h * 0.84f, cx - w * 0.26f, h * 0.42f)
                close()
            },
            rb(Color(0xFFFF8A7A), RED, RED_D, cx, h * 0.6f, w * 0.3f),
        )
        drawCircle(GOLD, w * 0.04f, Offset(cx, h * 0.52f))
        gloss(cx - w * 0.08f, h * 0.54f, w * 0.16f)
    },

    "cart" to { _ ->
        val w = size.width; val h = size.height
        line(GRAY_D, w * 0.12f, h * 0.20f, w * 0.24f, h * 0.20f, w * 0.04f)
        line(GRAY_D, w * 0.24f, h * 0.20f, w * 0.30f, h * 0.34f, w * 0.04f)
        drawPath(
            Path().apply {
                moveTo(w * 0.30f, h * 0.34f); lineTo(w * 0.86f, h * 0.34f)
                lineTo(w * 0.78f, h * 0.62f); lineTo(w * 0.38f, h * 0.62f); close()
            },
            vb(SKY, BLUE, BLUE_D, w * 0.58f, h * 0.48f, w * 0.3f),
        )
        drawCircle(DARK, w * 0.06f, Offset(w * 0.42f, h * 0.76f))
        drawCircle(DARK, w * 0.06f, Offset(w * 0.72f, h * 0.76f))
        drawCircle(GRAY_L, w * 0.02f, Offset(w * 0.42f, h * 0.76f))
        drawCircle(GRAY_L, w * 0.02f, Offset(w * 0.72f, h * 0.76f))
    },

    "train" to { t ->
        val w = size.width; val h = size.height
        val bodyW = w * 0.66f; val left = (w - bodyW) / 2; val top = h * 0.44f
        for (i in 0..2) {
            val p = (t + i / 3f) % 1f
            val px = left + bodyW * 0.20f + sinF(p * 5f) * w * 0.03f
            val py = top - h * 0.08f - p * h * 0.26f
            drawCircle(GRAY.copy(alpha = 0.55f * (1f - p)), w * (0.05f + 0.06f * p), Offset(px, py))
        }
        rect(DARK, left + bodyW * 0.13f, top - h * 0.10f, left + bodyW * 0.13f + w * 0.07f, top + h * 0.02f)
        rect(vb(SKY, BLUE, BLUE_D, left + bodyW * 0.3f, top + h * 0.11f, w * 0.2f), left, top, left + bodyW * 0.62f, top + h * 0.22f)
        rect(vb(Color(0xFFFF8A7A), RED, RED_D, left + bodyW * 0.8f, top + h * 0.11f, w * 0.2f), left + bodyW * 0.62f, top - h * 0.10f, left + bodyW, top + h * 0.22f)
        val wy = top + h * 0.30f
        for (fx in listOf(0.18f, 0.5f, 0.84f)) {
            val wx = left + bodyW * fx
            drawCircle(DARK, w * 0.08f, Offset(wx, wy))
            rotate(t * 360f, Offset(wx, wy)) {
                line(WHITE, wx - w * 0.055f, wy, wx + w * 0.055f, wy, w * 0.02f)
                line(WHITE, wx, wy - w * 0.055f, wx, wy + w * 0.055f, w * 0.02f)
            }
        }
    },

    "boat" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        val rock = sinF(PI2 * t) * 6f
        rotate(rock, Offset(cx, h * 0.7f)) {
            line(BROWN, cx, h * 0.16f, cx, h * 0.66f, w * 0.03f)
            tri(vb(WHITE, Color(0xFFF2F5F9), GRAY_L, cx + w * 0.14f, h * 0.4f, w * 0.2f), cx + w * 0.03f, h * 0.18f, cx + w * 0.03f, h * 0.62f, cx + w * 0.30f, h * 0.62f)
            tri(RED, cx - w * 0.03f, h * 0.30f, cx - w * 0.03f, h * 0.62f, cx - w * 0.24f, h * 0.62f)
            drawPath(
                Path().apply {
                    moveTo(cx - w * 0.32f, h * 0.66f); lineTo(cx + w * 0.32f, h * 0.66f)
                    lineTo(cx + w * 0.20f, h * 0.82f); lineTo(cx - w * 0.20f, h * 0.82f); close()
                },
                vb(BROWN, BROWN, Color(0xFF6B4426), cx, h * 0.74f, w * 0.3f),
            )
        }
        val wave = sinF(PI2 * t) * w * 0.03f
        arcStroke(SKY, cx - w * 0.36f + wave, h * 0.88f, cx - w * 0.18f + wave, h * 0.82f, cx + wave, h * 0.88f, w * 0.03f)
        arcStroke(SKY, cx + wave, h * 0.88f, cx + w * 0.18f + wave, h * 0.82f, cx + w * 0.36f + wave, h * 0.88f, w * 0.03f)
    },

    "mountain" to { _ ->
        val w = size.width; val h = size.height
        tri(vb(SKY, BLUE, BLUE_D, w * 0.66f, h * 0.5f, w * 0.3f), w * 0.66f, h * 0.24f, w * 0.34f, h * 0.84f, w * 0.94f, h * 0.84f)
        tri(WHITE, w * 0.66f, h * 0.24f, w * 0.56f, h * 0.40f, w * 0.76f, h * 0.40f)
        tri(vb(Color(0xFFB7E2A5), GREEN, GREEN_D, w * 0.32f, h * 0.6f, w * 0.26f), w * 0.32f, h * 0.40f, w * 0.06f, h * 0.84f, w * 0.58f, h * 0.84f)
        tri(WHITE, w * 0.32f, h * 0.40f, w * 0.25f, h * 0.52f, w * 0.39f, h * 0.52f)
    },

    "tent" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        tri(vb(ORANGE, RED, RED_D, cx, h * 0.5f, w * 0.36f), cx, h * 0.16f, cx - w * 0.36f, h * 0.84f, cx + w * 0.36f, h * 0.84f)
        tri(DARK, cx, h * 0.40f, cx - w * 0.12f, h * 0.84f, cx + w * 0.12f, h * 0.84f)
        line(YELLOW, cx, h * 0.16f, cx, h * 0.08f, w * 0.02f)
        tri(TEAL, cx, h * 0.08f, cx + w * 0.12f, h * 0.12f, cx, h * 0.16f)
    },

    "robot" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        line(GRAY_D, cx, h * 0.16f, cx, h * 0.26f, w * 0.03f)
        val blink = if (sinF(PI2 * t) > 0f) YELLOW else RED
        drawCircle(blink, w * 0.04f, Offset(cx, h * 0.14f))
        rect(vb(GRAY_L, GRAY, GRAY_D, cx, h * 0.48f, w * 0.28f), cx - w * 0.26f, h * 0.26f, cx + w * 0.26f, h * 0.68f)
        drawCircle(TEAL, w * 0.05f, Offset(cx - w * 0.11f, h * 0.42f))
        drawCircle(TEAL, w * 0.05f, Offset(cx + w * 0.11f, h * 0.42f))
        rect(DARK, cx - w * 0.12f, h * 0.56f, cx + w * 0.12f, h * 0.62f)
        rect(GRAY, cx - w * 0.34f, h * 0.72f, cx + w * 0.34f, h * 0.86f)
        gloss(cx, h * 0.36f, w * 0.22f)
    },

    "disco" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        drawCircle(rb(WHITE, GRAY_L, GRAY, cx, cy, w * 0.32f), w * 0.32f, Offset(cx, cy))
        val sh = t * 360f
        rotate(sh, Offset(cx, cy)) {
            line(GRAY.copy(alpha = 0.6f), cx - w * 0.32f, cy, cx + w * 0.32f, cy, w * 0.02f)
            line(GRAY.copy(alpha = 0.6f), cx, cy - w * 0.32f, cx, cy + w * 0.32f, w * 0.02f)
            line(GRAY.copy(alpha = 0.6f), cx - w * 0.22f, cy - w * 0.22f, cx + w * 0.22f, cy + w * 0.22f, w * 0.02f)
            line(GRAY.copy(alpha = 0.6f), cx - w * 0.22f, cy + w * 0.22f, cx + w * 0.22f, cy - w * 0.22f, w * 0.02f)
        }
        for (i in 0..3) {
            val a = (sinF(PI2 * t + i * 1.7f) + 1f) / 2f
            val ang = i * 90f + 30f
            drawCircle(
                listOf(PINK, TEAL, YELLOW, VIOLET)[i].copy(alpha = 0.3f + 0.7f * a),
                w * 0.04f,
                Offset(cx + cosF(ang * PI2 / 360f) * w * 0.18f, cy + sinF(ang * PI2 / 360f) * w * 0.18f),
            )
        }
        gloss(cx, cy, w * 0.3f)
    },

    "ticket" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(PINK, VIOLET, BLUE_D, cx, h * 0.5f, w * 0.32f), cx - w * 0.32f, h * 0.30f, cx + w * 0.32f, h * 0.70f)
        drawCircle(HOLE, w * 0.06f, Offset(cx - w * 0.32f, h * 0.5f))
        drawCircle(HOLE, w * 0.06f, Offset(cx + w * 0.32f, h * 0.5f))
        line(WHITE.copy(alpha = 0.7f), cx + w * 0.14f, h * 0.32f, cx + w * 0.14f, h * 0.68f, w * 0.02f)
        line(WHITE, cx - w * 0.22f, h * 0.44f, cx + w * 0.04f, h * 0.44f, w * 0.03f)
        line(WHITE, cx - w * 0.22f, h * 0.56f, cx - w * 0.02f, h * 0.56f, w * 0.03f)
    },

    "pirate" to { t ->
        val w = size.width; val h = size.height
        val wave = sinF(PI2 * t) * w * 0.02f
        line(GRAY_D, w * 0.20f, h * 0.10f, w * 0.20f, h * 0.90f, w * 0.04f)
        rect(DARK, w * 0.24f, h * 0.16f + wave, w * 0.84f, h * 0.56f + wave)
        drawCircle(WHITE, w * 0.09f, Offset(w * 0.54f, h * 0.32f + wave))
        drawCircle(DARK, w * 0.02f, Offset(w * 0.51f, h * 0.30f + wave))
        drawCircle(DARK, w * 0.02f, Offset(w * 0.57f, h * 0.30f + wave))
        line(WHITE, w * 0.42f, h * 0.48f + wave, w * 0.66f, h * 0.48f + wave, w * 0.03f)
    },

    "ballot" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        val drop = (sinF(PI2 * t) * 0.5f + 0.5f) * h * 0.10f
        rect(WHITE, cx - w * 0.08f, h * 0.14f + drop, cx + w * 0.08f, h * 0.34f + drop)
        line(TEAL, cx - w * 0.04f, h * 0.22f + drop, cx + w * 0.04f, h * 0.22f + drop, w * 0.02f)
        rect(vb(SKY, BLUE, BLUE_D, cx, h * 0.62f, w * 0.3f), cx - w * 0.30f, h * 0.42f, cx + w * 0.30f, h * 0.82f)
        rect(DARK, cx - w * 0.12f, h * 0.40f, cx + w * 0.12f, h * 0.46f)
        gloss(cx, h * 0.56f, w * 0.24f)
    },

    "gradcap" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawPath(
            Path().apply {
                moveTo(cx, h * 0.18f); lineTo(cx + w * 0.36f, h * 0.36f); lineTo(cx, h * 0.54f); lineTo(cx - w * 0.36f, h * 0.36f); close()
            },
            vb(GRAY_L, GRAY, GRAY_D, cx, h * 0.36f, w * 0.3f),
        )
        oval(DARK, cx - w * 0.18f, h * 0.46f, cx + w * 0.18f, h * 0.70f)
        line(GOLD, cx + w * 0.36f, h * 0.36f, cx + w * 0.36f, h * 0.66f, w * 0.025f)
        drawCircle(GOLD, w * 0.04f, Offset(cx + w * 0.36f, h * 0.70f))
    },

    "telescope" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        rotate(-30f, Offset(cx, cy)) {
            rect(vb(BROWN, BROWN, Color(0xFF6B4426), cx, cy, w * 0.34f), cx - w * 0.30f, cy - w * 0.07f, cx + w * 0.24f, cy + w * 0.07f)
            rect(GOLD, cx + w * 0.24f, cy - w * 0.09f, cx + w * 0.34f, cy + w * 0.09f)
            drawCircle(SKY, w * 0.05f, Offset(cx + w * 0.30f, cy))
        }
        line(GRAY_D, cx, cy + w * 0.02f, cx - w * 0.18f, h * 0.86f, w * 0.03f)
        line(GRAY_D, cx, cy + w * 0.02f, cx + w * 0.18f, h * 0.86f, w * 0.03f)
    },

    "microscope" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(GRAY_D, cx - w * 0.26f, h * 0.80f, cx + w * 0.26f, h * 0.88f)
        arcStroke(GRAY, cx + w * 0.10f, h * 0.70f, cx + w * 0.22f, h * 0.44f, cx + w * 0.02f, h * 0.30f, w * 0.05f)
        rotate(20f, Offset(cx, h * 0.4f)) {
            rect(vb(SKY, BLUE, BLUE_D, cx, h * 0.36f, w * 0.1f), cx - w * 0.06f, h * 0.20f, cx + w * 0.06f, h * 0.50f)
        }
        rect(GRAY_L, cx - w * 0.16f, h * 0.58f, cx + w * 0.10f, h * 0.64f)
        drawCircle(TEAL, w * 0.03f, Offset(cx - w * 0.02f, h * 0.52f))
    },

    "notes2" to { t ->
        val w = size.width; val h = size.height
        val bob = (sinF(PI2 * t) * 0.5f + 0.5f) * h * 0.08f
        val y = h * 0.70f - bob
        line(VIOLET, w * 0.36f, y - h * 0.36f, w * 0.72f, y - h * 0.44f, w * 0.06f)
        line(VIOLET, w * 0.34f, y, w * 0.34f, y - h * 0.34f, w * 0.035f)
        line(VIOLET, w * 0.70f, y - h * 0.08f, w * 0.70f, y - h * 0.42f, w * 0.035f)
        drawCircle(rb(PINK, VIOLET, BLUE_D, w * 0.30f, y, w * 0.1f), w * 0.09f, Offset(w * 0.30f, y))
        drawCircle(rb(PINK, VIOLET, BLUE_D, w * 0.66f, y - h * 0.08f, w * 0.1f), w * 0.09f, Offset(w * 0.66f, y - h * 0.08f))
    },

    "moon" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        rotate(sinF(PI2 * t) * 12f, Offset(cx, cy)) {
            drawCircle(vb(Color(0xFFFFF3B0), YELLOW, ORANGE, cx, cy, w * 0.3f), w * 0.30f, Offset(cx, cy))
            drawCircle(HOLE, w * 0.26f, Offset(cx + w * 0.14f, cy - w * 0.10f))
        }
        val tw = (sinF(PI2 * t) + 1f) / 2f
        val sx = cx - w * 0.26f; val sy = cy - w * 0.26f
        line(YELLOW.copy(alpha = 0.4f + 0.6f * tw), sx - w * 0.07f, sy, sx + w * 0.07f, sy, w * 0.03f)
        line(YELLOW.copy(alpha = 0.4f + 0.6f * tw), sx, sy - w * 0.07f, sx, sy + w * 0.07f, w * 0.03f)
    },

    "dancer" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        val step = sinF(PI2 * t) * 10f
        rotate(step, Offset(cx, h * 0.6f)) {
            drawCircle(SKIN, w * 0.09f, Offset(cx, h * 0.20f))
            rect(vb(VIOLET, VIOLET, BLUE_D, cx, h * 0.44f, w * 0.1f), cx - w * 0.08f, h * 0.30f, cx + w * 0.08f, h * 0.58f)
            line(VIOLET, cx - w * 0.08f, h * 0.34f, cx - w * 0.26f, h * 0.18f, w * 0.04f)
            line(VIOLET, cx + w * 0.08f, h * 0.34f, cx + w * 0.26f, h * 0.48f, w * 0.04f)
            line(BLUE_D, cx - w * 0.04f, h * 0.58f, cx - w * 0.14f, h * 0.84f, w * 0.05f)
            line(BLUE_D, cx + w * 0.04f, h * 0.58f, cx + w * 0.16f, h * 0.84f, w * 0.05f)
        }
    },

    "dancer2" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        val step = sinF(PI2 * t + 1.5f) * 10f
        rotate(step, Offset(cx, h * 0.6f)) {
            drawCircle(SKIN, w * 0.09f, Offset(cx, h * 0.18f))
            tri(vb(RED, RED, RED_D, cx, h * 0.5f, w * 0.2f), cx - w * 0.08f, h * 0.30f, cx + w * 0.08f, h * 0.30f, cx, h * 0.44f)
            drawPath(
                Path().apply {
                    moveTo(cx - w * 0.08f, h * 0.36f); lineTo(cx + w * 0.08f, h * 0.36f)
                    lineTo(cx + w * 0.24f, h * 0.68f); lineTo(cx - w * 0.24f, h * 0.68f); close()
                },
                vb(RED, RED, RED_D, cx, h * 0.5f, w * 0.24f),
            )
            line(SKIN, cx - w * 0.08f, h * 0.34f, cx - w * 0.26f, h * 0.20f, w * 0.035f)
            line(SKIN, cx + w * 0.08f, h * 0.34f, cx + w * 0.26f, h * 0.20f, w * 0.035f)
            line(SKIN, cx - w * 0.06f, h * 0.68f, cx - w * 0.06f, h * 0.86f, w * 0.04f)
            line(SKIN, cx + w * 0.06f, h * 0.68f, cx + w * 0.06f, h * 0.86f, w * 0.04f)
        }
    },

    "helmet" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.28f, h * 0.58f)
                quadraticBezierTo(cx - w * 0.28f, h * 0.20f, cx, h * 0.20f)
                quadraticBezierTo(cx + w * 0.28f, h * 0.20f, cx + w * 0.28f, h * 0.58f)
                close()
            },
            rb(Color(0xFFB7E2A5), GREEN, GREEN_D, cx, h * 0.4f, w * 0.3f),
        )
        rect(vb(Color(0xFFB7E2A5), GREEN, GREEN_D, cx, h * 0.62f, w * 0.32f), cx - w * 0.34f, h * 0.56f, cx + w * 0.34f, h * 0.68f)
        rect(GREEN_D, cx - w * 0.05f, h * 0.24f, cx + w * 0.05f, h * 0.56f)
        gloss(cx - w * 0.1f, h * 0.34f, w * 0.18f)
    },

    "briefcase" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        arcStroke(BROWN, cx - w * 0.10f, h * 0.34f, cx, h * 0.18f, cx + w * 0.10f, h * 0.34f, w * 0.04f)
        rect(vb(Color(0xFFC79A6B), BROWN, Color(0xFF6B4426), cx, h * 0.6f, w * 0.32f), cx - w * 0.30f, h * 0.34f, cx + w * 0.30f, h * 0.82f)
        line(Color(0xFF6B4426), cx - w * 0.30f, h * 0.52f, cx + w * 0.30f, h * 0.52f, w * 0.025f)
        rect(GOLD, cx - w * 0.05f, h * 0.48f, cx + w * 0.05f, h * 0.58f)
        gloss(cx, h * 0.44f, w * 0.24f)
    },

    "tube" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        rotate(-20f, Offset(cx, h / 2)) {
            rect(WHITE.copy(alpha = 0.8f), cx - w * 0.08f, h * 0.16f, cx + w * 0.08f, h * 0.78f)
            drawCircle(WHITE.copy(alpha = 0.8f), w * 0.08f, Offset(cx, h * 0.78f))
            rect(vb(TEAL, TEAL, GREEN_D, cx, h * 0.6f, w * 0.1f), cx - w * 0.06f, h * 0.46f, cx + w * 0.06f, h * 0.78f)
            drawCircle(TEAL, w * 0.06f, Offset(cx, h * 0.78f))
            val p = (t * 2f) % 1f
            drawCircle(TEAL.copy(alpha = 0.7f * (1f - p)), w * 0.02f, Offset(cx, h * 0.70f - p * h * 0.20f))
        }
        rect(GRAY_L, cx - w * 0.12f, h * 0.12f, cx + w * 0.12f, h * 0.18f)
    },

    "family" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawCircle(SKIN, w * 0.10f, Offset(cx - w * 0.16f, h * 0.30f))
        oval(BLUE, cx - w * 0.30f, h * 0.42f, cx - w * 0.02f, h * 0.80f)
        drawCircle(SKIN, w * 0.10f, Offset(cx + w * 0.16f, h * 0.30f))
        oval(RED, cx + w * 0.02f, h * 0.42f, cx + w * 0.30f, h * 0.80f)
        drawCircle(SKIN, w * 0.07f, Offset(cx, h * 0.48f))
        oval(YELLOW, cx - w * 0.10f, h * 0.58f, cx + w * 0.10f, h * 0.84f)
    },

    "baby" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        drawCircle(rb(Color(0xFFFFE29A), SKIN, Color(0xFFC98B60), cx, cy, w * 0.3f), w * 0.30f, Offset(cx, cy))
        arcStroke(BROWN, cx - w * 0.02f, cy - w * 0.28f, cx, cy - w * 0.40f, cx + w * 0.06f, cy - w * 0.30f, w * 0.025f)
        val blink = sinF(PI2 * t) > -0.8f
        if (blink) {
            drawCircle(DARK, w * 0.03f, Offset(cx - w * 0.10f, cy - w * 0.04f))
            drawCircle(DARK, w * 0.03f, Offset(cx + w * 0.10f, cy - w * 0.04f))
        } else {
            line(DARK, cx - w * 0.13f, cy - w * 0.04f, cx - w * 0.07f, cy - w * 0.04f, w * 0.02f)
            line(DARK, cx + w * 0.07f, cy - w * 0.04f, cx + w * 0.13f, cy - w * 0.04f, w * 0.02f)
        }
        arcStroke(RED, cx - w * 0.08f, cy + w * 0.10f, cx, cy + w * 0.18f, cx + w * 0.08f, cy + w * 0.10f, w * 0.025f)
        oval(PINK.copy(alpha = 0.5f), cx - w * 0.22f, cy + w * 0.04f, cx - w * 0.12f, cy + w * 0.10f)
        oval(PINK.copy(alpha = 0.5f), cx + w * 0.12f, cy + w * 0.04f, cx + w * 0.22f, cy + w * 0.10f)
    },

    "bank" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        tri(vb(GRAY_L, GRAY, GRAY_D, cx, h * 0.26f, w * 0.34f), cx, h * 0.12f, cx - w * 0.34f, h * 0.34f, cx + w * 0.34f, h * 0.34f)
        for (i in -1..1) {
            rect(vb(WHITE, GRAY_L, GRAY, cx + i * w * 0.18f, h * 0.56f, w * 0.06f), cx + i * w * 0.18f - w * 0.045f, h * 0.38f, cx + i * w * 0.18f + w * 0.045f, h * 0.74f)
        }
        rect(GRAY, cx - w * 0.34f, h * 0.74f, cx + w * 0.34f, h * 0.82f)
        rect(GRAY_D, cx - w * 0.38f, h * 0.82f, cx + w * 0.38f, h * 0.88f)
        drawCircle(GOLD, w * 0.05f, Offset(cx, h * 0.26f))
    },

    "abacus" to { t ->
        val w = size.width; val h = size.height
        rect(BROWN, w * 0.16f, h * 0.14f, w * 0.84f, h * 0.86f)
        rect(HOLE, w * 0.22f, h * 0.20f, w * 0.78f, h * 0.80f)
        val slide = sinF(PI2 * t) * w * 0.06f
        for (ry in 0..2) {
            val y = h * (0.32f + ry * 0.20f)
            line(GRAY_D, w * 0.22f, y, w * 0.78f, y, w * 0.02f)
            for (bx in 0..2) {
                val off = if ((ry + bx) % 2 == 0) slide else -slide
                drawCircle(listOf(RED, BLUE, GREEN)[ry], w * 0.05f, Offset(w * (0.32f + bx * 0.14f) + off, y))
            }
        }
    },

    "printer" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(WHITE, cx - w * 0.16f, h * 0.14f, cx + w * 0.16f, h * 0.36f)
        rect(vb(GRAY_L, GRAY, GRAY_D, cx, h * 0.5f, w * 0.32f), cx - w * 0.32f, h * 0.34f, cx + w * 0.32f, h * 0.66f)
        val out = (sinF(PI2 * t) * 0.5f + 0.5f) * h * 0.10f
        rect(WHITE, cx - w * 0.18f, h * 0.62f + out, cx + w * 0.18f, h * 0.86f + out * 0.2f)
        line(GRAY, cx - w * 0.12f, h * 0.72f + out, cx + w * 0.12f, h * 0.72f + out, w * 0.02f)
        drawCircle(GREEN, w * 0.025f, Offset(cx + w * 0.24f, h * 0.42f))
    },

    "police" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawCircle(SKIN, w * 0.22f, Offset(cx, h * 0.44f))
        rect(vb(SKY, BLUE, BLUE_D, cx, h * 0.24f, w * 0.24f), cx - w * 0.24f, h * 0.16f, cx + w * 0.24f, h * 0.32f)
        rect(BLUE_D, cx - w * 0.26f, h * 0.30f, cx + w * 0.26f, h * 0.36f)
        drawCircle(GOLD, w * 0.04f, Offset(cx, h * 0.24f))
        oval(BLUE, cx - w * 0.26f, h * 0.66f, cx + w * 0.26f, h * 0.90f)
        drawCircle(GOLD, w * 0.03f, Offset(cx - w * 0.10f, h * 0.76f))
        drawCircle(GOLD, w * 0.03f, Offset(cx + w * 0.10f, h * 0.76f))
    },

    "steth" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        arcStroke(GRAY_D, cx - w * 0.18f, h * 0.16f, cx - w * 0.24f, h * 0.44f, cx - w * 0.02f, h * 0.52f, w * 0.04f)
        arcStroke(GRAY_D, cx + w * 0.18f, h * 0.16f, cx + w * 0.24f, h * 0.44f, cx + w * 0.02f, h * 0.52f, w * 0.04f)
        arcStroke(GRAY_D, cx, h * 0.52f, cx + w * 0.10f, h * 0.72f, cx + w * 0.24f, h * 0.66f, w * 0.04f)
        val beat = 1f + 0.15f * kotlin.math.max(0f, sinF(PI2 * t))
        drawCircle(vb(SKY, BLUE, BLUE_D, cx - w * 0.22f, h * 0.72f, w * 0.1f), w * 0.09f * beat, Offset(cx - w * 0.22f, h * 0.72f))
        drawCircle(GRAY_L, w * 0.04f, Offset(cx - w * 0.22f, h * 0.72f))
    },

    "pill" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        rotate(-30f + sinF(PI2 * t) * 8f, Offset(cx, cy)) {
            oval(vb(RED, RED, RED_D, cx - w * 0.14f, cy, w * 0.16f), cx - w * 0.30f, cy - w * 0.12f, cx, cy + w * 0.12f)
            oval(vb(Color(0xFFFFF3B0), YELLOW, ORANGE, cx + w * 0.14f, cy, w * 0.16f), cx, cy - w * 0.12f, cx + w * 0.30f, cy + w * 0.12f)
            gloss(cx - w * 0.12f, cy - w * 0.06f, w * 0.14f)
        }
    },

    "syringe" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        rotate(45f, Offset(cx, cy)) {
            line(GRAY, cx, cy - w * 0.42f, cx, cy - w * 0.26f, w * 0.02f)
            rect(WHITE.copy(alpha = 0.85f), cx - w * 0.07f, cy - w * 0.26f, cx + w * 0.07f, cy + w * 0.16f)
            rect(TEAL, cx - w * 0.05f, cy - w * 0.02f, cx + w * 0.05f, cy + w * 0.14f)
            rect(GRAY_L, cx - w * 0.10f, cy + w * 0.16f, cx + w * 0.10f, cy + w * 0.22f)
            line(GRAY, cx, cy + w * 0.22f, cx, cy + w * 0.34f, w * 0.03f)
            line(GRAY, cx - w * 0.08f, cy + w * 0.34f, cx + w * 0.08f, cy + w * 0.34f, w * 0.03f)
        }
    },

    "soap" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(TEAL, TEAL, GREEN_D, cx, h * 0.6f, w * 0.3f), cx - w * 0.28f, h * 0.42f, cx + w * 0.28f, h * 0.78f)
        oval(WHITE.copy(alpha = 0.5f), cx - w * 0.16f, h * 0.48f, cx + w * 0.02f, h * 0.58f)
        for (i in 0..2) {
            val p = (t + i / 3f) % 1f
            drawCircle(
                WHITE.copy(alpha = 0.7f * (1f - p)),
                w * (0.03f + 0.04f * p),
                Offset(cx - w * 0.14f + i * w * 0.14f, h * 0.36f - p * h * 0.20f),
            )
        }
    },

    "idcard" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(WHITE, Color(0xFFF2F5F9), GRAY_L, cx, h * 0.5f, w * 0.32f), cx - w * 0.32f, h * 0.24f, cx + w * 0.32f, h * 0.76f)
        rect(SKY, cx - w * 0.24f, h * 0.34f, cx - w * 0.04f, h * 0.56f)
        drawCircle(SKIN, w * 0.05f, Offset(cx - w * 0.14f, h * 0.42f))
        oval(BLUE, cx - w * 0.20f, h * 0.50f, cx - w * 0.08f, h * 0.56f)
        line(GRAY, cx + w * 0.02f, h * 0.38f, cx + w * 0.24f, h * 0.38f, w * 0.03f)
        line(GRAY, cx + w * 0.02f, h * 0.50f, cx + w * 0.24f, h * 0.50f, w * 0.03f)
        line(GRAY, cx - w * 0.24f, h * 0.66f, cx + w * 0.24f, h * 0.66f, w * 0.03f)
    },

    "plate" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        drawCircle(rb(WHITE, Color(0xFFF2F5F9), GRAY_L, cx, cy, w * 0.3f), w * 0.30f, Offset(cx, cy))
        drawCircle(GRAY_L, w * 0.18f, Offset(cx, cy))
        line(GRAY, cx - w * 0.40f, cy - w * 0.24f, cx - w * 0.40f, cy + w * 0.24f, w * 0.025f)
        line(GRAY, cx - w * 0.44f, cy - w * 0.24f, cx - w * 0.44f, cy - w * 0.08f, w * 0.02f)
        line(GRAY, cx - w * 0.36f, cy - w * 0.24f, cx - w * 0.36f, cy - w * 0.08f, w * 0.02f)
        rect(GRAY, cx + w * 0.38f, cy - w * 0.24f, cx + w * 0.44f, cy + w * 0.24f)
    },

    "fish" to { t ->
        val w = size.width; val h = size.height
        val cx = w / 2 + w * 0.04f; val cy = h / 2
        val swing = sinF(PI2 * t) * 20f
        rotate(swing, Offset(cx - w * 0.18f, cy)) {
            tri(ORANGE, cx - w * 0.16f, cy, cx - w * 0.36f, cy - h * 0.14f, cx - w * 0.36f, cy + h * 0.14f)
        }
        drawOval(
            rb(Color(0xFFFFC46B), ORANGE, Color(0xFFA34E08), cx - w * 0.05f, cy - h * 0.08f, w * 0.5f),
            Offset(cx - w * 0.20f, cy - h * 0.15f),
            Size(w * 0.46f, h * 0.30f),
        )
        tri(RED, cx - w * 0.02f, cy - h * 0.13f, cx + w * 0.08f, cy - h * 0.26f, cx + w * 0.10f, cy - h * 0.10f)
        drawCircle(DARK, w * 0.03f, Offset(cx + w * 0.16f, cy - h * 0.04f))
        gloss(cx + w * 0.02f, cy - h * 0.02f, w * 0.16f)
        val p = t % 1f
        drawCircle(BLUE.copy(alpha = 0.6f * (1f - p)), w * 0.035f, Offset(cx + w * 0.30f, cy - h * 0.10f - p * h * 0.25f))
    },

    "palette" to { _ ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        drawCircle(rb(Color(0xFFC79A6B), BROWN, Color(0xFF6B4426), cx, cy, w * 0.34f), w * 0.34f, Offset(cx, cy))
        drawCircle(HOLE, w * 0.07f, Offset(cx + w * 0.14f, cy + w * 0.10f))
        drawCircle(RED, w * 0.05f, Offset(cx - w * 0.14f, cy - w * 0.12f))
        drawCircle(YELLOW, w * 0.05f, Offset(cx + w * 0.02f, cy - w * 0.18f))
        drawCircle(BLUE, w * 0.05f, Offset(cx + w * 0.16f, cy - w * 0.08f))
        drawCircle(GREEN, w * 0.05f, Offset(cx - w * 0.16f, cy + w * 0.06f))
        gloss(cx - w * 0.08f, cy - w * 0.10f, w * 0.2f)
    },

    "masks" to { _ ->
        val w = size.width; val h = size.height
        rotate(-6f, Offset(w * 0.36f, h * 0.42f)) {
            oval(vb(Color(0xFFFFF3B0), YELLOW, ORANGE, w * 0.36f, h * 0.42f, w * 0.2f), w * 0.16f, h * 0.18f, w * 0.56f, h * 0.66f)
            drawCircle(DARK, w * 0.025f, Offset(w * 0.28f, h * 0.36f))
            drawCircle(DARK, w * 0.025f, Offset(w * 0.44f, h * 0.36f))
            arcStroke(DARK, w * 0.26f, h * 0.46f, w * 0.36f, h * 0.56f, w * 0.46f, h * 0.46f, w * 0.025f)
        }
        rotate(6f, Offset(w * 0.62f, h * 0.56f)) {
            oval(vb(SKY, BLUE, BLUE_D, w * 0.62f, h * 0.56f, w * 0.2f), w * 0.42f, h * 0.32f, w * 0.82f, h * 0.80f)
            drawCircle(DARK, w * 0.025f, Offset(w * 0.54f, h * 0.50f))
            drawCircle(DARK, w * 0.025f, Offset(w * 0.70f, h * 0.50f))
            arcStroke(DARK, w * 0.52f, h * 0.68f, w * 0.62f, h * 0.60f, w * 0.72f, h * 0.68f, w * 0.025f)
        }
    },

    "tophat" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(GRAY_L, DARK, Color(0xFF10141B), cx, h * 0.44f, w * 0.22f), cx - w * 0.20f, h * 0.16f, cx + w * 0.20f, h * 0.68f)
        oval(vb(GRAY_L, DARK, Color(0xFF10141B), cx, h * 0.7f, w * 0.34f), cx - w * 0.34f, h * 0.62f, cx + w * 0.34f, h * 0.80f)
        rect(RED, cx - w * 0.20f, h * 0.52f, cx + w * 0.20f, h * 0.62f)
        gloss(cx - w * 0.08f, h * 0.3f, w * 0.16f)
    },

    "crystal" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        tri(vb(GOLD, GOLD, Color(0xFF9C6B12), cx, h * 0.8f, w * 0.2f), cx - w * 0.16f, h * 0.86f, cx + w * 0.16f, h * 0.86f, cx, h * 0.66f)
        drawCircle(rb(Color(0xFFE6D9FF), VIOLET, BLUE_D, cx, h * 0.42f, w * 0.28f), w * 0.28f, Offset(cx, h * 0.42f))
        val tw = (sinF(PI2 * t) + 1f) / 2f
        line(WHITE.copy(alpha = 0.4f + 0.6f * tw), cx - w * 0.16f, h * 0.30f, cx - w * 0.02f, h * 0.30f, w * 0.025f)
        line(WHITE.copy(alpha = 0.4f + 0.6f * tw), cx - w * 0.09f, h * 0.23f, cx - w * 0.09f, h * 0.37f, w * 0.025f)
        oval(WHITE.copy(alpha = 0.35f), cx + w * 0.02f, h * 0.50f, cx + w * 0.14f, h * 0.60f)
    },

    "cocktail" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        tri(WHITE.copy(alpha = 0.75f), cx - w * 0.26f, h * 0.20f, cx + w * 0.26f, h * 0.20f, cx, h * 0.52f)
        tri(vb(ORANGE, RED, RED_D, cx, h * 0.32f, w * 0.2f), cx - w * 0.20f, h * 0.26f, cx + w * 0.20f, h * 0.26f, cx, h * 0.50f)
        line(GRAY_L, cx, h * 0.52f, cx, h * 0.78f, w * 0.03f)
        line(GRAY_L, cx - w * 0.14f, h * 0.82f, cx + w * 0.14f, h * 0.82f, w * 0.04f)
        drawCircle(ORANGE, w * 0.06f, Offset(cx + w * 0.20f, h * 0.18f))
        val sway = sinF(PI2 * t) * 4f
        rotate(sway, Offset(cx + w * 0.10f, h * 0.24f)) {
            line(RED, cx + w * 0.10f, h * 0.24f, cx + w * 0.20f, h * 0.06f, w * 0.025f)
        }
    },

    "cake" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        rect(vb(PINK, PINK, VIOLET, cx, h * 0.68f, w * 0.3f), cx - w * 0.30f, h * 0.52f, cx + w * 0.30f, h * 0.84f)
        for (i in -2..2) {
            drawCircle(WHITE, w * 0.05f, Offset(cx + i * w * 0.12f, h * 0.52f))
        }
        rect(TEAL, cx - w * 0.02f, h * 0.30f, cx + w * 0.02f, h * 0.50f)
        val fl = 1f + 0.3f * sinF(PI2 * t)
        oval(YELLOW, cx - w * 0.03f * fl, h * 0.20f, cx + w * 0.03f * fl, h * 0.30f)
        gloss(cx - w * 0.12f, h * 0.6f, w * 0.2f)
    },

    "coffee" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        oval(vb(WHITE, Color(0xFFF2F5F9), GRAY_L, cx, h * 0.6f, w * 0.28f), cx - w * 0.26f, h * 0.44f, cx + w * 0.26f, h * 0.78f)
        oval(vb(BROWN, BROWN, Color(0xFF6B4426), cx, h * 0.48f, w * 0.2f), cx - w * 0.20f, h * 0.42f, cx + w * 0.20f, h * 0.54f)
        arcStroke(GRAY_L, cx + w * 0.26f, h * 0.50f, cx + w * 0.38f, h * 0.60f, cx + w * 0.26f, h * 0.70f, w * 0.04f)
        for (i in 0..1) {
            val p = (t + i / 2f) % 1f
            arcStroke(
                GRAY.copy(alpha = 0.6f * (1f - p)),
                cx - w * 0.08f + i * w * 0.16f, h * 0.38f - p * h * 0.10f,
                cx - w * 0.02f + i * w * 0.16f, h * 0.28f - p * h * 0.10f,
                cx - w * 0.08f + i * w * 0.16f, h * 0.18f - p * h * 0.10f,
                w * 0.025f,
            )
        }
    },

    "sushi" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        oval(WHITE, cx - w * 0.30f, cy - w * 0.14f, cx + w * 0.30f, cy + w * 0.16f)
        oval(vb(Color(0xFFFFC46B), ORANGE, Color(0xFFA34E08), cx, cy - w * 0.12f, w * 0.26f), cx - w * 0.26f, cy - w * 0.26f, cx + w * 0.26f, cy - w * 0.02f)
        rect(Color(0xFF2E3A2E), cx - w * 0.06f, cy - w * 0.26f, cx + w * 0.06f, cy + w * 0.16f)
        gloss(cx - w * 0.12f, cy - w * 0.16f, w * 0.16f)
    },

    "burger" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        oval(vb(Color(0xFFE8B06B), BROWN, Color(0xFF6B4426), cx, h * 0.34f, w * 0.32f), cx - w * 0.32f, h * 0.18f, cx + w * 0.32f, h * 0.44f)
        drawCircle(YELLOW, w * 0.02f, Offset(cx - w * 0.12f, h * 0.30f))
        drawCircle(YELLOW, w * 0.02f, Offset(cx + w * 0.02f, h * 0.26f))
        drawCircle(YELLOW, w * 0.02f, Offset(cx + w * 0.14f, h * 0.32f))
        rect(GREEN, cx - w * 0.34f, h * 0.44f, cx + w * 0.34f, h * 0.52f)
        rect(vb(RED, RED, RED_D, cx, h * 0.58f, w * 0.34f), cx - w * 0.32f, h * 0.52f, cx + w * 0.32f, h * 0.64f)
        oval(vb(Color(0xFFE8B06B), BROWN, Color(0xFF6B4426), cx, h * 0.74f, w * 0.32f), cx - w * 0.32f, h * 0.64f, cx + w * 0.32f, h * 0.84f)
    },

    "pizza" to { _ ->
        val w = size.width; val h = size.height; val cx = w / 2
        drawPath(
            Path().apply {
                moveTo(cx - w * 0.30f, h * 0.22f); lineTo(cx + w * 0.30f, h * 0.22f); lineTo(cx, h * 0.88f); close()
            },
            vb(YELLOW, YELLOW, ORANGE, cx, h * 0.5f, w * 0.34f),
        )
        rect(vb(Color(0xFFE8B06B), BROWN, Color(0xFF6B4426), cx, h * 0.2f, w * 0.32f), cx - w * 0.32f, h * 0.14f, cx + w * 0.32f, h * 0.26f)
        drawCircle(RED, w * 0.05f, Offset(cx - w * 0.10f, h * 0.36f))
        drawCircle(RED, w * 0.05f, Offset(cx + w * 0.10f, h * 0.42f))
        drawCircle(RED, w * 0.04f, Offset(cx, h * 0.58f))
        drawCircle(GREEN, w * 0.02f, Offset(cx + w * 0.04f, h * 0.32f))
        drawCircle(GREEN, w * 0.02f, Offset(cx - w * 0.06f, h * 0.50f))
    },

    "virus" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        rotate(t * 60f, Offset(cx, cy)) {
            for (i in 0 until 8) {
                rotate(i * 45f, Offset(cx, cy)) {
                    line(GREEN_D, cx, cy - w * 0.22f, cx, cy - w * 0.36f, w * 0.03f)
                    drawCircle(GREEN, w * 0.04f, Offset(cx, cy - w * 0.38f))
                }
            }
        }
        drawCircle(rb(Color(0xFFB7E2A5), GREEN, GREEN_D, cx, cy, w * 0.3f), w * 0.24f, Offset(cx, cy))
        drawCircle(DARK, w * 0.03f, Offset(cx - w * 0.08f, cy - w * 0.04f))
        drawCircle(DARK, w * 0.03f, Offset(cx + w * 0.08f, cy - w * 0.04f))
        arcStroke(DARK, cx - w * 0.08f, cy + w * 0.06f, cx, cy + w * 0.14f, cx + w * 0.08f, cy + w * 0.06f, w * 0.025f)
        gloss(cx, cy, w * 0.22f)
    },

    "star" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        val r = w * 0.36f
        rotate(t * 120f, Offset(cx, cy)) {
            drawPath(
                Path().apply {
                    for (i in 0 until 10) {
                        val angle = i * 36f - 90f
                        val rad = if (i % 2 == 0) r else r * 0.45f
                        val px = cx + cosF(angle * PI2 / 360f) * rad
                        val py = cy + sinF(angle * PI2 / 360f) * rad
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                },
                vb(Color(0xFFFFF3B0), YELLOW, ORANGE, cx, cy, r),
            )
        }
        val tw = (sinF(PI2 * t) + 1f) / 2f
        drawCircle(WHITE.copy(alpha = 0.4f + 0.6f * tw), w * 0.05f, Offset(cx - r * 0.25f, cy - r * 0.3f))
    },

    "ball" to { t ->
        val w = size.width; val h = size.height; val cx = w / 2
        val bounce = kotlin.math.abs(sinF(PI2 * t / 2f))
        drawCircle(DARK.copy(alpha = 0.12f + 0.14f * (1f - bounce)), w * (0.16f + 0.08f * (1f - bounce)), Offset(cx, h * 0.86f))
        val y = h * 0.62f - bounce * h * 0.34f
        drawCircle(rb(Color(0xFFFF8A7A), RED, RED_D, cx, y, w * 0.22f), w * 0.20f, Offset(cx, y))
        arcStroke(WHITE.copy(alpha = 0.6f), cx - w * 0.14f, y - w * 0.06f, cx, y - w * 0.16f, cx + w * 0.14f, y - w * 0.06f, w * 0.03f)
        gloss(cx, y - w * 0.02f, w * 0.18f)
    },

    "drop" to { t ->
        val w = size.width; val cy = size.height / 2; val cx = w / 2
        val r = w * 0.30f
        drawPath(
            Path().apply {
                moveTo(cx, cy - r * 1.15f)
                quadraticBezierTo(cx + r * 0.95f, cy + r * 0.15f, cx + r * 0.66f, cy + r * 0.62f)
                quadraticBezierTo(cx + r * 0.3f, cy + r * 1.05f, cx, cy + r * 1.05f)
                quadraticBezierTo(cx - r * 0.3f, cy + r * 1.05f, cx - r * 0.66f, cy + r * 0.62f)
                quadraticBezierTo(cx - r * 0.95f, cy + r * 0.15f, cx, cy - r * 1.15f)
                close()
            },
            rb(Color(0xFFD6F0FF), SKY, BLUE_D, cx, cy, r),
        )
        gloss(cx - r * 0.1f, cy, r * 0.8f)
        shimmer(t, cx, cy, r)
    },

    "flower" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        val r = w * 0.30f
        for (i in 0 until 6) {
            rotate(i * 60f + t * 60f, Offset(cx, cy)) {
                oval(
                    Brush.linearGradient(listOf(PINK, VIOLET), start = Offset(cx, cy - r * 1.15f), end = Offset(cx, cy)),
                    cx - r * 0.34f, cy - r * 1.15f,
                    cx + r * 0.34f, cy - r * 0.15f,
                )
            }
        }
        drawCircle(rb(Color(0xFFFFF3B0), YELLOW, ORANGE, cx, cy, r * 0.4f), r * 0.36f, Offset(cx, cy))
        gloss(cx, cy, r * 0.5f)
    },

    "gear" to { t ->
        val w = size.width; val cx = w / 2; val cy = size.height / 2
        rotate(t * 180f, Offset(cx, cy)) {
            for (i in 0 until 8) {
                rotate(i * 45f, Offset(cx, cy)) {
                    rect(vb(GRAY_L, GRAY, GRAY_D, cx, cy - w * 0.3f, w * 0.1f), cx - w * 0.055f, cy - w * 0.38f, cx + w * 0.055f, cy - w * 0.24f)
                }
            }
            drawCircle(rb(GRAY_L, GRAY, GRAY_D, cx, cy, w * 0.5f), w * 0.27f, Offset(cx, cy))
            drawCircle(HOLE, w * 0.12f, Offset(cx, cy))
            gloss(cx, cy, w * 0.3f)
        }
    },
)
