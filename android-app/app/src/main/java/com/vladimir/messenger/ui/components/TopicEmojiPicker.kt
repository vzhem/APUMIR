package com.vladimir.messenger.ui.components

import androidx.compose.ui.res.stringResource
import com.vladimir.messenger.R
// =============================================================================
// TOPICEMOJIPICKER.KT — выбор значка темы, как в Telegram (раунд 247)
// =============================================================================
// Владелец: «Сделай у нас такие же, как в телеграм. Сделай прям копию для
// вставки в темы». В Telegram значок темы выбирается из сетки ЭМОДЗИ с поиском
// и недавними. Эмодзи - символы Unicode, рисуем их системным шрифтом телефона
// (никакой чужой графики); сетка 8 столбцов, поиск по русским словам, строка
// недавних - как у оригинала. Набор - те же сюжеты и порядок, что в сетке
// значков тем Telegram (и что уже повторены нашими живыми значками). Выбранный
// эмодзи хранится в теме строкой: TopicIconView уже показывает эмодзи текстом,
// провод (GroupWire iconEmoji) его возит. Фирменные живые значки остались
// вторым табом в диалоге создания темы.
// =============================================================================

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TopicEmojiCatalog {

    /**
     * Базовый набор значков темы - те же сюжеты и порядок, что в сетке тем
     * Telegram. Это символы Unicode; отрисовка - системным шрифтом телефона.
     * Все 100 уникальны (ключи сетки) и совпадают по смыслу с живыми
     * значками [TopicIconCatalog.kinds] - один сюжет, два вида.
     */
    val EMOJIS: List<String> = listOf(
        "💬", "⚡", "🎙️", "🔝", "🆒", "❗", "📝", "📅", "📁", "🔍",
        "📢", "🔥", "❤️", "❓", "📈", "📉", "💎", "💰", "💸", "🪙",
        "💱", "🎮", "💻", "📱", "🚗", "🏠", "💘", "🎉", "‼️", "🏆",
        "🏁", "🎬", "🎵", "☀️", "📚", "👑", "⚽", "🏀", "📺", "👀",
        "👄", "🍓", "💄", "👠", "✈️", "🧳", "🏝️", "⛅", "🦄", "🛍️",
        "👜", "🛒", "🚂", "⛵", "🏔️", "⛺", "🤖", "🪩", "🎟️", "🏴‍☠️",
        "🗳️", "🎓", "🔭", "🔬", "🎶", "🌙", "🕺", "💃", "🪖", "💼",
        "🧪", "👪", "👶", "🏛️", "🧮", "🖨️", "👮", "🩺", "💊", "💉",
        "🧼", "🪪", "🍽️", "🐟", "🎨", "🎭", "🎩", "🔮", "🍹", "🎂",
        "☕", "🍣", "🍔", "🍕", "🦠", "⭐", "🔴", "💧", "🌸", "⚙️",
    )

    /** Русские ключевые слова для строки «Поиск» - как в телеграме. */
    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "💬" to listOf("чат", "пузырь", "сообщение", "разговор"),
        "⚡" to listOf("молния", "энергия", "ток"),
        "🎙️" to listOf("микрофон", "голос", "запись"),
        "🔝" to listOf("верх", "топ", "стрелка"),
        "🆒" to listOf("круто", "кул", "класс"),
        "❗" to listOf("восклицание", "важно", "внимание"),
        "📝" to listOf("записка", "блокнот", "карандаш", "письмо"),
        "📅" to listOf("календарь", "дата", "июль"),
        "📁" to listOf("папка", "файлы", "каталог"),
        "🔍" to listOf("поиск", "лупа", "найти"),
        "📢" to listOf("громко", "рупор", "анонс", "горн"),
        "🔥" to listOf("огонь", "пламя", "жар"),
        "❤️" to listOf("сердце", "любовь", "красный"),
        "❓" to listOf("вопрос", "знак"),
        "📈" to listOf("рост", "график", "вверх", "прибыль"),
        "📉" to listOf("спад", "график", "вниз", "убыток"),
        "💎" to listOf("алмаз", "кристалл", "бриллиант"),
        "💰" to listOf("деньги", "мешок", "богатство"),
        "💸" to listOf("деньги", "крылья", "трата"),
        "🪙" to listOf("монета", "деньги", "золото"),
        "💱" to listOf("обмен", "валюта", "деньги"),
        "🎮" to listOf("игра", "геймпад", "приставка"),
        "💻" to listOf("ноутбук", "компьютер", "работа"),
        "📱" to listOf("телефон", "смартфон", "мобильный"),
        "🚗" to listOf("машина", "авто", "красный"),
        "🏠" to listOf("дом", "дача", "семья"),
        "💘" to listOf("сердце", "стрела", "влюблен"),
        "🎉" to listOf("праздник", "хлопушка", "конфетти"),
        "‼️" to listOf("два", "восклицание", "важно"),
        "🏆" to listOf("кубок", "победа", "награда"),
        "🏁" to listOf("финиш", "флаг", "гонки"),
        "🎬" to listOf("кино", "фильм", "клапер"),
        "🎵" to listOf("нота", "музыка"),
        "☀️" to listOf("солнце", "день", "тепло"),
        "📚" to listOf("книги", "учеба", "читать"),
        "👑" to listOf("корона", "король", "царь"),
        "⚽" to listOf("футбол", "мяч", "спорт"),
        "🏀" to listOf("баскетбол", "мяч", "спорт"),
        "📺" to listOf("телевизор", "тв", "экран"),
        "👀" to listOf("глаза", "смотреть", "видеть"),
        "👄" to listOf("губы", "поцелуй", "рот"),
        "🍓" to listOf("клубника", "ягода", "сладкое"),
        "💄" to listOf("помада", "макияж", "красота"),
        "👠" to listOf("туфля", "каблук", "обувь"),
        "✈️" to listOf("самолет", "полет", "путешествие"),
        "🧳" to listOf("чемодан", "багаж", "поездка"),
        "🏝️" to listOf("остров", "море", "отдых"),
        "⛅" to listOf("солнце", "облако", "погода"),
        "🦄" to listOf("единорог", "сказка", "радуга"),
        "🛍️" to listOf("пакеты", "покупки", "магазин"),
        "👜" to listOf("сумка", "сумочка"),
        "🛒" to listOf("корзина", "магазин", "покупки"),
        "🚂" to listOf("поезд", "паровоз", "дорога"),
        "⛵" to listOf("яхта", "парус", "море"),
        "🏔️" to listOf("горы", "снег", "вершина"),
        "⛺" to listOf("палатка", "поход", "кемпинг"),
        "🤖" to listOf("робот", "андроид", "техника"),
        "🪩" to listOf("диско", "шар", "вечеринка"),
        "🎟️" to listOf("билет", "кино", "вход"),
        "🏴‍☠️" to listOf("пират", "флаг", "череп"),
        "🗳️" to listOf("выборы", "голос", "урна"),
        "🎓" to listOf("выпуск", "учеба", "шапка"),
        "🔭" to listOf("телескоп", "звезды", "космос"),
        "🔬" to listOf("микроскоп", "наука", "лаборатория"),
        "🎶" to listOf("ноты", "музыка", "песня"),
        "🌙" to listOf("луна", "ночь", "месяц"),
        "🕺" to listOf("танец", "танцор", "дискотека"),
        "💃" to listOf("танец", "танцовщица", "платье"),
        "🪖" to listOf("каска", "армия", "солдат"),
        "💼" to listOf("портфель", "работа", "офис"),
        "🧪" to listOf("пробирка", "химия", "опыт"),
        "👪" to listOf("семья", "дети", "родители"),
        "👶" to listOf("малыш", "ребенок", "детский"),
        "🏛️" to listOf("банк", "музей", "колонны"),
        "🧮" to listOf("счёты", "счет", "математика"),
        "🖨️" to listOf("принтер", "печать"),
        "👮" to listOf("полиция", "полицейский"),
        "🩺" to listOf("стетоскоп", "врач", "медицина"),
        "💊" to listOf("таблетка", "капсула", "лекарство"),
        "💉" to listOf("шприц", "укол", "прививка"),
        "🧼" to listOf("мыло", "пена", "чистота"),
        "🪪" to listOf("карточка", "документ", "удостоверение"),
        "🍽️" to listOf("еда", "обед", "тарелка", "ресторан"),
        "🐟" to listOf("рыба", "рыбка", "аквариум"),
        "🎨" to listOf("палитра", "краски", "рисовать"),
        "🎭" to listOf("маски", "театр", "сцена"),
        "🎩" to listOf("цилиндр", "шляпа", "фокусник"),
        "🔮" to listOf("шар", "гадание", "магия"),
        "🍹" to listOf("коктейль", "напиток", "бар"),
        "🎂" to listOf("торт", "день рождения", "свечи"),
        "☕" to listOf("кофе", "чашка", "утро"),
        "🍣" to listOf("суши", "ролл", "япония"),
        "🍔" to listOf("бургер", "гамбургер", "фастфуд"),
        "🍕" to listOf("пицца", "еда"),
        "🦠" to listOf("вирус", "микроб", "бактерия"),
        "⭐" to listOf("звезда", "звездочка"),
        "🔴" to listOf("мяч", "шар", "красный", "круг"),
        "💧" to listOf("капля", "вода"),
        "🌸" to listOf("цветок", "весна", "лепестки"),
        "⚙️" to listOf("шестеренка", "механизм", "настройки"),
    )

    /** Поиск: пусто - весь набор; иначе - по вхождению в ключевые слова. */
    fun search(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return EMOJIS
        return EMOJIS.filter { e -> (KEYWORDS[e] ?: emptyList()).any { it.contains(q) } }
    }
}

/** Недавние значки темы: строка в prefs, свежие впереди. */
object TopicEmojiRecents {
    private const val PREFS = "apu_topic_icons"
    private const val KEY = "recent_emojis"
    private const val MAX = 12

    fun load(context: android.content.Context): List<String> = context.applicationContext
        .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .getString(KEY, "")
        .orEmpty()
        .split('|')
        .filter { it.isNotEmpty() }

    fun note(context: android.content.Context, emoji: String) {
        if (emoji !in TopicEmojiCatalog.EMOJIS) return
        val next = (listOf(emoji) + load(context)).distinct().take(MAX)
        context.applicationContext
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, next.joinToString("|"))
            .apply()
    }
}

/**
 * Сетка эмодзи в стиле телеграма: поиск, недавние, 8 столбцов. Выбранный
 * подсвечивается рамкой, как у нас везде.
 */
@Composable
fun TopicEmojiPicker(
    selected: String,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val items = remember(query) { TopicEmojiCatalog.search(query) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ApuBubbleField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.action_search)) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Раунд 259: ленты «недавних» между поиском и сеткой больше нет -
        // владелец попросил убрать, сетка начинается сразу.
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(2.dp),
        ) {
            gridItems(items, key = { it }) { emoji ->
                EmojiCell(emoji, selected) { picked ->
                    TopicEmojiRecents.note(context, picked)
                    onPick(picked)
                }
            }        }
    }
}

@Composable
private fun EmojiCell(emoji: String, selected: String, onPick: (String) -> Unit) {
    val isSel = emoji == selected
    // Раунд 258: при тапе эмодзи «подпрыгивает», как в Telegram: резкий
    // рост и пружинный возврат с лёгким перелётом.
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isSel) {
                    Modifier
                        .background(Color(0xFFE8EEF5))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            )
            .clickable {
                scope.launch {
                    scale.snapTo(1.35f)
                    scale.animateTo(
                        1f,
                        androidx.compose.animation.core.spring(
                            dampingRatio = 0.45f,
                            stiffness = 320f,
                        ),
                    )
                }
                onPick(emoji)
            },
        contentAlignment = Alignment.Center,
    ) {
        ShinyEmoji(
            emoji = emoji,
            fontSize = 24.sp,
            modifier = Modifier.size(34.dp),
        )
    }
}
