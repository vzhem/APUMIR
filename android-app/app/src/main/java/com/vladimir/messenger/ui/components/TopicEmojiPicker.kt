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

    /** Ключевые слова для строки «Поиск» - как в телеграме: русские и английские. */
    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "💬" to listOf("чат", "пузырь", "сообщение", "разговор", "chat", "bubble", "message", "talk"),
        "⚡" to listOf("молния", "энергия", "ток", "lightning", "energy", "power"),
        "🎙️" to listOf("микрофон", "голос", "запись", "microphone", "voice", "record"),
        "🔝" to listOf("верх", "топ", "стрелка", "top", "up", "arrow"),
        "🆒" to listOf("круто", "кул", "класс", "cool", "nice", "class"),
        "❗" to listOf("восклицание", "важно", "внимание", "exclamation", "important", "attention"),
        "📝" to listOf("записка", "блокнот", "карандаш", "письмо", "note", "memo", "pencil", "letter"),
        "📅" to listOf("календарь", "дата", "июль", "calendar", "date", "july"),
        "📁" to listOf("папка", "файлы", "каталог", "folder", "files", "catalog"),
        "🔍" to listOf("поиск", "лупа", "найти", "search", "find", "magnifier"),
        "📢" to listOf("громко", "рупор", "анонс", "горн", "loud", "megaphone", "announcement", "horn"),
        "🔥" to listOf("огонь", "пламя", "жар", "fire", "flame", "hot"),
        "❤️" to listOf("сердце", "любовь", "красный", "heart", "love", "red"),
        "❓" to listOf("вопрос", "знак", "question", "question mark"),
        "📈" to listOf("рост", "график", "вверх", "прибыль", "growth", "chart", "up", "profit"),
        "📉" to listOf("спад", "график", "вниз", "убыток", "decline", "chart", "down", "loss"),
        "💎" to listOf("алмаз", "кристалл", "бриллиант", "diamond", "gem", "crystal"),
        "💰" to listOf("деньги", "мешок", "богатство", "money", "bag", "wealth"),
        "💸" to listOf("деньги", "крылья", "трата", "money", "wings", "spending"),
        "🪙" to listOf("монета", "деньги", "золото", "coin", "money", "gold"),
        "💱" to listOf("обмен", "валюта", "деньги", "exchange", "currency", "money"),
        "🎮" to listOf("игра", "геймпад", "приставка", "game", "gamepad", "console"),
        "💻" to listOf("ноутбук", "компьютер", "работа", "laptop", "computer", "work"),
        "📱" to listOf("телефон", "смартфон", "мобильный", "phone", "smartphone", "mobile"),
        "🚗" to listOf("машина", "авто", "красный", "car", "auto", "red"),
        "🏠" to listOf("дом", "дача", "семья", "home", "house", "family"),
        "💘" to listOf("сердце", "стрела", "влюблен", "heart", "arrow", "love"),
        "🎉" to listOf("праздник", "хлопушка", "конфетти", "party", "celebration", "confetti"),
        "‼️" to listOf("два", "восклицание", "важно", "double exclamation", "important"),
        "🏆" to listOf("кубок", "победа", "награда", "cup", "trophy", "victory", "award"),
        "🏁" to listOf("финиш", "флаг", "гонки", "finish", "flag", "race"),
        "🎬" to listOf("кино", "фильм", "клапер", "movie", "film", "clapperboard"),
        "🎵" to listOf("нота", "музыка", "note", "music"),
        "☀️" to listOf("солнце", "день", "тепло", "sun", "day", "warm"),
        "📚" to listOf("книги", "учеба", "читать", "books", "study", "read"),
        "👑" to listOf("корона", "король", "царь", "crown", "king", "queen"),
        "⚽" to listOf("футбол", "мяч", "спорт", "football", "soccer", "ball", "sport"),
        "🏀" to listOf("баскетбол", "мяч", "спорт", "basketball", "ball", "sport"),
        "📺" to listOf("телевизор", "тв", "экран", "tv", "television", "screen"),
        "👀" to listOf("глаза", "смотреть", "видеть", "eyes", "look", "see"),
        "👄" to listOf("губы", "поцелуй", "рот", "lips", "kiss", "mouth"),
        "🍓" to listOf("клубника", "ягода", "сладкое", "strawberry", "berry", "sweet"),
        "💄" to listOf("помада", "макияж", "красота", "lipstick", "makeup", "beauty"),
        "👠" to listOf("туфля", "каблук", "обувь", "shoe", "heel", "footwear"),
        "✈️" to listOf("самолет", "полет", "путешествие", "plane", "flight", "travel"),
        "🧳" to listOf("чемодан", "багаж", "поездка", "suitcase", "luggage", "trip"),
        "🏝️" to listOf("остров", "море", "отдых", "island", "sea", "vacation"),
        "⛅" to listOf("солнце", "облако", "погода", "sun", "cloud", "weather"),
        "🦄" to listOf("единорог", "сказка", "радуга", "unicorn", "fairy tale", "rainbow"),
        "🛍️" to listOf("пакеты", "покупки", "магазин", "shopping", "bags", "store"),
        "👜" to listOf("сумка", "сумочка", "bag", "handbag", "purse"),
        "🛒" to listOf("корзина", "магазин", "покупки", "cart", "shop", "shopping"),
        "🚂" to listOf("поезд", "паровоз", "дорога", "train", "locomotive", "road"),
        "⛵" to listOf("яхта", "парус", "море", "yacht", "sail", "sea"),
        "🏔️" to listOf("горы", "снег", "вершина", "mountains", "snow", "peak"),
        "⛺" to listOf("палатка", "поход", "кемпинг", "tent", "camping", "hike"),
        "🤖" to listOf("робот", "андроид", "техника", "robot", "android", "tech"),
        "🪩" to listOf("диско", "шар", "вечеринка", "disco", "ball", "party"),
        "🎟️" to listOf("билет", "кино", "вход", "ticket", "cinema", "entry"),
        "🏴‍☠️" to listOf("пират", "флаг", "череп", "pirate", "flag", "skull"),
        "🗳️" to listOf("выборы", "голос", "урна", "vote", "ballot", "election"),
        "🎓" to listOf("выпуск", "учеба", "шапка", "graduation", "study", "cap"),
        "🔭" to listOf("телескоп", "звезды", "космос", "telescope", "stars", "space"),
        "🔬" to listOf("микроскоп", "наука", "лаборатория", "microscope", "science", "lab"),
        "🎶" to listOf("ноты", "музыка", "песня", "notes", "music", "song"),
        "🌙" to listOf("луна", "ночь", "месяц", "moon", "night", "month"),
        "🕺" to listOf("танец", "танцор", "дискотека", "dance", "dancer", "disco"),
        "💃" to listOf("танец", "танцовщица", "платье", "dance", "dancer", "dress"),
        "🪖" to listOf("каска", "армия", "солдат", "helmet", "army", "soldier"),
        "💼" to listOf("портфель", "работа", "офис", "briefcase", "work", "office"),
        "🧪" to listOf("пробирка", "химия", "опыт", "test tube", "chemistry", "experiment"),
        "👪" to listOf("семья", "дети", "родители", "family", "kids", "parents"),
        "👶" to listOf("малыш", "ребенок", "детский", "baby", "child", "kid"),
        "🏛️" to listOf("банк", "музей", "колонны", "bank", "museum", "columns"),
        "🧮" to listOf("счёты", "счет", "математика", "abacus", "count", "math"),
        "🖨️" to listOf("принтер", "печать", "printer", "print"),
        "👮" to listOf("полиция", "полицейский", "police", "officer"),
        "🩺" to listOf("стетоскоп", "врач", "медицина", "stethoscope", "doctor", "medicine"),
        "💊" to listOf("таблетка", "капсула", "лекарство", "pill", "capsule", "medicine"),
        "💉" to listOf("шприц", "укол", "прививка", "syringe", "shot", "vaccine"),
        "🧼" to listOf("мыло", "пена", "чистота", "soap", "foam", "clean"),
        "🪪" to listOf("карточка", "документ", "удостоверение", "card", "document", "id"),
        "🍽️" to listOf("еда", "обед", "тарелка", "ресторан", "food", "dinner", "plate", "restaurant"),
        "🐟" to listOf("рыба", "рыбка", "аквариум", "fish", "aquarium"),
        "🎨" to listOf("палитра", "краски", "рисовать", "palette", "paints", "draw"),
        "🎭" to listOf("маски", "театр", "сцена", "masks", "theater", "stage"),
        "🎩" to listOf("цилиндр", "шляпа", "фокусник", "top hat", "hat", "magician"),
        "🔮" to listOf("шар", "гадание", "магия", "crystal ball", "fortune", "magic"),
        "🍹" to listOf("коктейль", "напиток", "бар", "cocktail", "drink", "bar"),
        "🎂" to listOf("торт", "день рождения", "свечи", "cake", "birthday", "candles"),
        "☕" to listOf("кофе", "чашка", "утро", "coffee", "cup", "morning"),
        "🍣" to listOf("суши", "ролл", "япония", "sushi", "roll", "japan"),
        "🍔" to listOf("бургер", "гамбургер", "фастфуд", "burger", "hamburger", "fast food"),
        "🍕" to listOf("пицца", "еда", "pizza", "food"),
        "🦠" to listOf("вирус", "микроб", "бактерия", "virus", "germ", "bacteria"),
        "⭐" to listOf("звезда", "звездочка", "star", "asterisk"),
        "🔴" to listOf("мяч", "шар", "красный", "круг", "ball", "circle", "red"),
        "💧" to listOf("капля", "вода", "drop", "water"),
        "🌸" to listOf("цветок", "весна", "лепестки", "flower", "spring", "petals"),
        "⚙️" to listOf("шестеренка", "механизм", "настройки", "gear", "mechanism", "settings"),
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
