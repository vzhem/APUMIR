#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ссылочная геометрия редактора фото истории - двойник `data/story/StoryCrop.kt`.

Зачем: Kotlin-код собирает владелец на своей машине, здесь компилятора нет. Поэтому
та же арифметика (кадр, щипок, панорама, поворот, выравнивание, отражение, свободная
обрезка) повторяется на Python и проверяется по-настоящему: синтетический «снимок»
прогоняется через ту же матрицу, что уедет в предпросмотр и в публикацию, и мы
смотрим на пиксели.

Конвенции (совпадают с Kotlin до последнего знака):
  * кадр истории 9:16 (`STORY_ASPECT`), единица длины - высота кадра = 1;
  * рамка (`Frame`) - в долях кадра истории, ось Y вниз;
  * `quarter` - четверти оборота по часовой, `straighten` - доворот ±45°;
  * `pan` - смещение снимка в долях размера рамки; `zoom` ≥ 1 к минимальному
    увеличению, при котором в рамке нет пустых углов;
  * одна матрица «пиксель снимка → пиксель рамки» на предпросмотр и на рендер.

Запуск: python3 tools/sandbox/story_crop_reference.py
"""
import math
import sys

STORY_ASPECT = 360.0 / 640.0
MAX_ZOOM = 8.0
MIN_ZOOM = 1.0
STRAIGHTEN_LIMIT = 45.0
MIN_FRAME = 0.12
MAX_SIDE = 1280
MIN_SIDE = 64
EPS = 0.75

ASPECT_ORIGINAL = "original"
ASPECT_FREE = "free"
ASPECT_SQUARE = "1:1"
ASPECT_PORTRAIT = "4:5"
ASPECT_STORY = "9:16"
ASPECT_WIDE = "16:9"

ASPECT_PRESETS = (ASPECT_ORIGINAL, ASPECT_SQUARE, ASPECT_PORTRAIT, ASPECT_STORY, ASPECT_WIDE)

QUESTIONS_HEADER = "проверок: %d"


def _min_frame_width() -> float:
    return MIN_FRAME / STORY_ASPECT


class Frame:
    """Рамка обрезки в долях кадра истории."""

    __slots__ = ("left", "top", "right", "bottom")

    def __init__(self, left: float, top: float, right: float, bottom: float):
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom

    @property
    def width(self) -> float:
        return self.right - self.left

    @property
    def height(self) -> float:
        return self.bottom - self.top

    @property
    def width_units(self) -> float:
        """Ширина рамки в единицах «высота кадра = 1»."""
        return self.width * STORY_ASPECT

    @property
    def height_units(self) -> float:
        return self.height

    @property
    def center_x(self) -> float:
        return (self.left + self.right) / 2.0

    @property
    def center_y(self) -> float:
        return (self.top + self.bottom) / 2.0

    @property
    def aspect(self) -> float:
        h = self.height_units
        return self.width_units / h if h > 0 else 1.0

    def copy(self) -> "Frame":
        return Frame(self.left, self.top, self.right, self.bottom)

    def key(self) -> tuple:
        return (self.left, self.top, self.right, self.bottom)

    def __repr__(self) -> str:
        return "Frame(%.4f, %.4f, %.4f, %.4f)" % (self.left, self.top, self.right, self.bottom)


FULL = Frame(0.0, 0.0, 1.0, 1.0)


class State:
    """Рецепт правки: то же поле, что `StoryCrop.State` в Kotlin."""

    __slots__ = ("quarter", "straighten", "flip_h", "flip_v", "aspect", "frame", "zoom", "pan_x", "pan_y")

    def __init__(self, quarter=0, straighten=0.0, flip_h=False, flip_v=False, aspect=ASPECT_ORIGINAL,
                 frame=None, zoom=1.0, pan_x=0.0, pan_y=0.0):
        self.quarter = int(quarter) % 4
        self.straighten = float(straighten)
        self.flip_h = bool(flip_h)
        self.flip_v = bool(flip_v)
        self.aspect = aspect
        self.frame = frame if frame is not None else Frame(FULL.left, FULL.top, FULL.right, FULL.bottom)
        self.zoom = float(zoom)
        self.pan_x = float(pan_x)
        self.pan_y = float(pan_y)

    @property
    def rotation_deg(self) -> float:
        return self.quarter * 90.0 + self.straighten

    def copy(self) -> "State":
        return State(self.quarter, self.straighten, self.flip_h, self.flip_v, self.aspect,
                     self.frame.copy(), self.zoom, self.pan_x, self.pan_y)


    def __repr__(self) -> str:
        return ("State(q=%d, straighten=%.1f, flipH=%s, flipV=%s, aspect=%s, zoom=%.2f, pan=%.3f/%.3f, %r)"
                % (self.quarter, self.straighten, self.flip_h, self.flip_v, self.aspect,
                   self.zoom, self.pan_x, self.pan_y, self.frame))


def original_aspect(img_w: int, img_h: int, quarter: int) -> float:
    if img_w <= 0 or img_h <= 0:
        return 1.0
    return float(img_w) / float(img_h) if quarter % 2 == 0 else float(img_h) / float(img_w)


def preset_aspect(aspect_id: str):
    if aspect_id == ASPECT_SQUARE:
        return 1.0
    if aspect_id == ASPECT_PORTRAIT:
        return 4.0 / 5.0
    if aspect_id == ASPECT_STORY:
        return STORY_ASPECT
    if aspect_id == ASPECT_WIDE:
        return 16.0 / 9.0
    return None


def frame_for(aspect: float, center_x: float = 0.5, center_y: float = 0.5) -> Frame:
    """Самая крупная рамка нужной пропорции внутри кадра истории."""
    a = min(max(aspect, 0.05), 20.0)
    width_units = min(STORY_ASPECT, a)
    height_units = width_units / a
    width = width_units / STORY_ASPECT
    height = height_units
    cx = min(max(center_x, width / 2.0), 1.0 - width / 2.0)
    cy = min(max(center_y, height / 2.0), 1.0 - height / 2.0)
    return Frame(cx - width / 2.0, cy - height / 2.0, cx + width / 2.0, cy + height / 2.0)


def target_aspect(state: State, img_w: int, img_h: int):
    if state.aspect == ASPECT_ORIGINAL:
        return original_aspect(img_w, img_h, state.quarter)
    return preset_aspect(state.aspect)


def keep_aspect(aspect_id: str, frame: Frame, img_w: int, img_h: int, quarter: int) -> str:
    """Рамку подвинули - оставляем чип, только если пропорция всё ещё его."""
    if aspect_id == ASPECT_ORIGINAL:
        target = original_aspect(img_w, img_h, quarter)
    else:
        target = preset_aspect(aspect_id)
    if target is None:
        return ASPECT_FREE
    return aspect_id if abs(frame.aspect - target) <= target * 0.02 else ASPECT_FREE


def initial_state(img_w: int, img_h: int) -> State:
    """Открывается весь снимок целиком: рамка в его пропорцию, без обрезки."""
    return State(aspect=ASPECT_ORIGINAL, frame=frame_for(original_aspect(img_w, img_h, 0)))


def with_aspect(state: State, aspect_id: str, img_w: int, img_h: int) -> State:
    if aspect_id == ASPECT_ORIGINAL:
        target = original_aspect(img_w, img_h, state.quarter)
    else:
        target = preset_aspect(aspect_id)
    if target is None:
        return state.copy()
    return bind(State(state.quarter, state.straighten, state.flip_h, state.flip_v, aspect_id,
                      frame_for(target, state.frame.center_x, state.frame.center_y),
                      state.zoom, state.pan_x, state.pan_y), img_w, img_h)


def rotate_frame_quarter(frame: Frame) -> Frame:
    """Рамка поворачивается вместе со снимком и берёт самый крупный размер.

    Пропорция переворачивается, размер - максимальный для новой пропорции:
    на состав кадра размер не влияет (только разрешение), а ужимать рамку при
    повороте незачем - после двух четвертей она обязана вернуться к прежней.
    """
    flipped = 1.0 / frame.aspect if frame.aspect > 0.001 else 1.0
    return frame_for(flipped, frame.center_x, frame.center_y)


def with_quarter(state: State, img_w: int, img_h: int) -> State:
    quarter = (state.quarter + 1) % 4
    frame = rotate_frame_quarter(state.frame)
    aspect = keep_aspect(state.aspect, frame, img_w, img_h, quarter)
    return bind(State(quarter, state.straighten, state.flip_h, state.flip_v, aspect, frame,
                      state.zoom, state.pan_x, state.pan_y), img_w, img_h)


def with_straighten(state: State, deg: float, img_w: int, img_h: int) -> State:
    clamped = min(max(deg, -STRAIGHTEN_LIMIT), STRAIGHTEN_LIMIT)
    return bind(State(state.quarter, clamped, state.flip_h, state.flip_v, state.aspect,
                      state.frame.copy(), state.zoom, state.pan_x, state.pan_y), img_w, img_h)


def with_rotation(state: State, total_deg: float, img_w: int, img_h: int) -> State:
    """Полный угол раскладываем на четверти и «ровно» ±45: двухпальцевый доворот."""
    norm = ((total_deg % 360.0) + 360.0) % 360.0
    steps = int(math.floor((norm + STRAIGHTEN_LIMIT) / 90.0))
    straighten = norm - steps * 90.0
    quarter = steps % 4
    if straighten > STRAIGHTEN_LIMIT:
        straighten = STRAIGHTEN_LIMIT
    if straighten < -STRAIGHTEN_LIMIT:
        straighten = -STRAIGHTEN_LIMIT
    return bind(State(quarter, straighten, state.flip_h, state.flip_v, state.aspect,
                      state.frame.copy(), state.zoom, state.pan_x, state.pan_y), img_w, img_h)


def toggle_flip_h(state: State, img_w: int, img_h: int) -> State:
    return bind(State(state.quarter, state.straighten, not state.flip_h, state.flip_v, state.aspect,
                      state.frame.copy(), state.zoom, state.pan_x, state.pan_y), img_w, img_h)


def toggle_flip_v(state: State, img_w: int, img_h: int) -> State:
    return bind(State(state.quarter, state.straighten, state.flip_h, not state.flip_v, state.aspect,
                      state.frame.copy(), state.zoom, state.pan_x, state.pan_y), img_w, img_h)


def cover_scale(state: State, img_w: int, img_h: int) -> float:
    """Минимальное «пиксель снимка → единица рамки», при котором рамка не пустует."""
    if img_w <= 0 or img_h <= 0:
        return 1.0
    rad = math.radians(state.rotation_deg)
    k_w = abs(math.cos(rad))
    k_h = abs(math.sin(rad))
    f = state.frame
    need_w = f.width_units * k_w + f.height_units * k_h
    need_h = f.width_units * k_h + f.height_units * k_w
    return max(need_w / float(img_w), need_h / float(img_h))


def covers(state: State, img_w: int, img_h: int) -> bool:
    """Точная проверка: повёрнутая рамка целиком внутри снимка."""
    if img_w <= 0 or img_h <= 0:
        return True
    scale = cover_scale(state, img_w, img_h) * state.zoom
    if scale <= 0.0:
        return False
    f = state.frame
    ex = f.width_units / 2.0
    ey = f.height_units / 2.0
    rad = math.radians(state.rotation_deg)
    k_w = abs(math.cos(rad))
    k_h = abs(math.sin(rad))
    px = state.pan_x * f.width_units
    py = state.pan_y * f.height_units
    # Все длины приводим к пикселям снимка: scale - это «единиц рамки на пиксель».
    ox = abs(px * math.cos(rad) + py * math.sin(rad)) / scale
    oy = abs(-px * math.sin(rad) + py * math.cos(rad)) / scale
    extent_x = (ex * k_w + ey * k_h) / scale
    extent_y = (ex * k_h + ey * k_w) / scale
    return (ox + extent_x <= img_w / 2.0 + EPS
            and oy + extent_y <= img_h / 2.0 + EPS)


def bind(state: State, img_w: int, img_h: int) -> State:
    """Привести состояние в допустимый вид: зум в границах, панорама не выходит за край.

    Как и в Kotlin, вход не меняется: возвращается новое состояние. Сдвиг
    ограничивается по осям ПО ОЧЕРЕДИ: у кадра шире снимка по ширине двигать
    нечего, а по высоте запас есть - терять его из-за первой оси нельзя.
    """
    state = state.copy()
    state.frame = sane_frame(state.frame)
    state.zoom = min(max(state.zoom, MIN_ZOOM), MAX_ZOOM)
    if covers(state, img_w, img_h):
        return state
    # Запрошенный сдвиг по высоте не теряем: по ширине его обнуляем только в
    # пробах, а после ограничения по X возвращаем и ограничиваем уже вместе.
    along_x = _clamp_axis(state.copy(), img_w, img_h, True)
    return _clamp_axis(along_x, img_w, img_h, False)


def sane_frame(frame: Frame) -> Frame:
    """Рамка в допустимом виде: внутри кадра истории, не мельче минимума, не вывернута."""
    left = min(max(frame.left, 0.0), 1.0)
    top = min(max(frame.top, 0.0), 1.0)
    right = min(max(frame.right, 0.0), 1.0)
    bottom = min(max(frame.bottom, 0.0), 1.0)
    if right - left < MIN_FRAME / STORY_ASPECT:
        right = min(1.0, left + MIN_FRAME / STORY_ASPECT)
        left = max(0.0, right - MIN_FRAME / STORY_ASPECT)
    if bottom - top < MIN_FRAME:
        bottom = min(1.0, top + MIN_FRAME)
        top = max(0.0, bottom - MIN_FRAME)
    return Frame(left, top, right, bottom)


def _clamp_axis(state: State, img_w: int, img_h: int, horizontal: bool) -> State:
    if covers(state, img_w, img_h):
        return state
    lo, hi = 0.0, 1.0
    for _ in range(24):
        mid = (lo + hi) / 2.0
        probe = state.copy()
        if horizontal:
            probe.pan_x = state.pan_x * mid
            probe.pan_y = 0.0
        else:
            probe.pan_y = state.pan_y * mid
        if covers(probe, img_w, img_h):
            lo = mid
        else:
            hi = mid
    if horizontal:
        state.pan_x = state.pan_x * lo
    else:
        state.pan_y = state.pan_y * lo
    return state


def with_zoom(state: State, zoom: float, img_w: int, img_h: int) -> State:
    st = state.copy()
    st.zoom = zoom
    return bind(st, img_w, img_h)


def zoom_by(state: State, factor: float, img_w: int, img_h: int) -> State:
    return with_zoom(state, state.zoom * factor, img_w, img_h)


def pan_by(state: State, dx: float, dy: float, img_w: int, img_h: int) -> State:
    st = state.copy()
    st.pan_x += dx
    st.pan_y += dy
    return bind(st, img_w, img_h)


def with_corner(state: State, corner: int, dx: float, dy: float, img_w: int, img_h: int) -> State:
    """Свободная обрезка: уголок 0=ЛВ, 1=ПВ, 2=ПН, 3=ЛН тянется на (dx, dy) в долях кадра."""
    state = state.copy()
    f = state.frame
    left, top, right, bottom = f.left, f.top, f.right, f.bottom
    if corner == 0:
        left += dx
        top += dy
    elif corner == 1:
        right += dx
        top += dy
    elif corner == 2:
        right += dx
        bottom += dy
    else:
        left += dx
        bottom += dy

    min_w = _min_frame_width()
    min_h = MIN_FRAME
    # Перекрестие не переворачиваем и слишком мелкую рамку не даём.
    if right - left < min_w:
        if corner in (0, 3):
            left = right - min_w
        else:
            right = left + min_w
    if bottom - top < min_h:
        if corner in (0, 1):
            top = bottom - min_h
        else:
            bottom = top + min_h
    left = min(max(left, 0.0), 1.0)
    right = min(max(right, 0.0), 1.0)
    top = min(max(top, 0.0), 1.0)
    bottom = min(max(bottom, 0.0), 1.0)
    moved = Frame(left, top, right, bottom)
    if moved.key() == f.key():
        return state
    aspect = keep_aspect(state.aspect, moved, img_w, img_h, state.quarter)
    state.frame = moved
    state.aspect = aspect
    return bind(state, img_w, img_h)


def reset(img_w: int, img_h: int) -> State:
    return initial_state(img_w, img_h)


def is_edited(state: State, img_w: int, img_h: int) -> bool:
    """Есть ли что сбрасывать: сравниваем с тем, что видел человек при открытии."""
    start = initial_state(img_w, img_h)
    return (state.quarter != start.quarter or abs(state.straighten - start.straighten) > 0.01
            or state.flip_h != start.flip_h or state.flip_v != start.flip_v
            or state.aspect != start.aspect or abs(state.zoom - start.zoom) > 0.001
            or abs(state.pan_x - start.pan_x) > 0.001 or abs(state.pan_y - start.pan_y) > 0.001
            or abs(state.frame.left - start.frame.left) > 0.001
            or abs(state.frame.top - start.frame.top) > 0.001
            or abs(state.frame.right - start.frame.right) > 0.001
            or abs(state.frame.bottom - start.frame.bottom) > 0.001)


def matrix(state: State, img_w: int, img_h: int, out_w: float, out_h: float) -> tuple:
    """Аффинная карта «пиксель снимка → пиксель рамки»: (a, b, c, d, e, f)."""
    f = state.frame
    scale = cover_scale(state, img_w, img_h) * min(max(state.zoom, MIN_ZOOM), MAX_ZOOM)
    k = out_w / f.width_units if f.width_units > 0 else out_w

    # m = T(out) * S(s*k) * R * [flip] * T(-center)
    a, b, c, d, e, ff = 1.0, 0.0, -img_w / 2.0, 0.0, 1.0, -img_h / 2.0
    if state.flip_h:
        a, c = -a, -c
    if state.flip_v:
        e, ff = -e, -ff
    if state.rotation_deg != 0.0:
        rad = math.radians(state.rotation_deg)
        co, si = math.cos(rad), math.sin(rad)
        a, b, c, d, e, ff = (a * co - d * si, b * co - e * si, c * co - ff * si,
                             a * si + d * co, b * si + e * co, c * si + ff * co)
    sx = sy = scale * k
    a, b, c = a * sx, b * sx, c * sx
    d, e, ff = d * sy, e * sy, ff * sy
    c += out_w / 2.0 + state.pan_x * out_w
    ff += out_h / 2.0 + state.pan_y * out_h
    return (a, b, c, d, e, ff)


def matrix_inverse(m: tuple) -> tuple:
    """Обратная карта: нужна PIL (он тянет пиксель вывода обратно в снимок)."""
    a, b, c, d, e, f = m
    det = a * e - b * d
    if abs(det) < 1e-12:
        return (1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    return (e / det, -b / det, (b * f - e * c) / det,
            -d / det, a / det, (d * c - a * f) / det)


def pil_affine(m: tuple) -> tuple:
    """Карта для PIL: он берёт пиксель по центрам, Skia - по углам.

    Без этой поправки крайние пиксели кадра уходили бы в чёрное, и сверка
    «пустых углов нет» ругалась бы на верной геометрии.
    """
    a, b, c, d, e, f = matrix_inverse(m)
    return (a, b, c + (a + b) * 0.5 - 0.5, d, e, f + (d + e) * 0.5 - 0.5)


def render_size(state: State, img_w: int, img_h: int) -> tuple:
    """Размер кадра для публикации: 1:1 к видимому куску снимка, не крупнее 1280."""
    f = state.frame
    scale = cover_scale(state, img_w, img_h) * min(max(state.zoom, MIN_ZOOM), MAX_ZOOM)
    if scale <= 0.0:
        scale = 1.0
    visible_long = max(f.width_units, f.height_units) / scale
    out_long = int(math.floor(min(max(visible_long, float(MIN_SIDE)), float(MAX_SIDE)) + 0.5))
    aspect = f.aspect
    if aspect >= 1.0:
        return out_long, max(1, int(math.floor(out_long / aspect + 0.5)))
    return max(1, int(math.floor(out_long * aspect + 0.5))), out_long


# ── Проверки ────────────────────────────────────────────────────────────────
# Никаких зависимостей: матрица проверяется по точкам, а не по картинке.

def checks(verbose=True):
    passed = []
    failed = []

    def ok(name, condition, detail=""):
        (passed if condition else failed).append((name, detail))

    img_w, img_h = 1500, 1125
    start = initial_state(img_w, img_h)

    # 1. Открывается весь снимок.
    out_w, out_h = render_size(start, img_w, img_h)
    ok("старт: видно весь снимок",
       abs(max(out_w, out_h) - min(MAX_SIDE, img_w)) <= 2 and abs(out_w / out_h - img_w / img_h) < 0.01,
       "%dx%d" % (out_w, out_h))
    ok("старт: пустых углов нет", covers(start, img_w, img_h))

    # 2. Чипы пропорций дают ровно свою пропорцию и влезают в кадр.
    for aspect_id in (ASPECT_SQUARE, ASPECT_PORTRAIT, ASPECT_STORY, ASPECT_WIDE, ASPECT_ORIGINAL):
        target = preset_aspect(aspect_id) or original_aspect(img_w, img_h, 0)
        st = with_aspect(start, aspect_id, img_w, img_h)
        inside = (st.frame.left >= -1e-6 and st.frame.top >= -1e-6
                  and st.frame.right <= 1 + 1e-6 and st.frame.bottom <= 1 + 1e-6)
        ok("чип %s: пропорция и границы" % aspect_id,
           abs(st.frame.aspect - target) < 1e-3 and inside, "%r" % st.frame)

    # 3. Четыре поворота возвращают к исходному кадру.
    spun = start.copy()
    for _ in range(4):
        spun = with_quarter(spun, img_w, img_h)
    ok("четыре поворота = исходник",
       spun.quarter == 0 and abs(spun.frame.aspect - start.frame.aspect) < 1e-3
       and abs(spun.frame.width - start.frame.width) < 1e-3 and spun.aspect == ASPECT_ORIGINAL)

    # 4. Выравнивание: ±45 и без пустых углов на каждом шаге.
    worst = []
    for deg in range(-60, 61, 5):
        st = with_straighten(start, float(deg), img_w, img_h)
        worst.append((deg, st.straighten, covers(st, img_w, img_h)))
    ok("выравнивание: ±45 и покрытие",
       all(abs(s) <= STRAIGHTEN_LIMIT + 1e-4 and c for _, s, c in worst))

    # 5. Полный угол раскладывается на четверти и остаток.
    cases = [(90.0, 1, 0.0), (45.0, 1, -45.0), (46.0, 1, -44.0), (100.0, 1, 10.0),
             (359.0, 0, -1.0), (-90.0, 3, 0.0), (0.0, 0, 0.0), (180.0, 2, 0.0),
             (270.0, 3, 0.0), (-45.0, 0, -45.0)]
    good = True
    for total, quarter, straighten in cases:
        st = with_rotation(start, total, img_w, img_h)
        if st.quarter != quarter or abs(st.straighten - straighten) > 1e-3:
            good = False
            if verbose:
                print("   раскладка не сошлась: %s -> q=%d s=%.2f" % (total, st.quarter, st.straighten))
    ok("поворот: четверти и остаток", good)

    # 6. Зум в границах, кадр мельче снимка - деталь мельче.
    big = with_zoom(start, 99.0, img_w, img_h)
    small = with_zoom(start, 0.1, img_w, img_h)
    ok("зум: от 1 до 8", abs(big.zoom - MAX_ZOOM) < 1e-4 and abs(small.zoom - MIN_ZOOM) < 1e-4)
    zoomed = zoom_by(start, 2.0, img_w, img_h)
    zw, zh = render_size(zoomed, img_w, img_h)
    ok("зум: кадр мельче", max(zw, zh) < max(out_w, out_h) + 1 and covers(zoomed, img_w, img_h))

    # 7. Панорама упирается в край, а не уезжает.
    dragged = pan_by(start, 5.0, 5.0, img_w, img_h)
    ok("панорама: без приближения двигать нечего",
       covers(dragged, img_w, img_h) and abs(dragged.pan_x) < 0.002 and abs(dragged.pan_y) < 0.002,
       "pan=%.4f/%.4f" % (dragged.pan_x, dragged.pan_y))
    wide = with_aspect(start, ASPECT_WIDE, img_w, img_h)
    # Кадр шире снимка: по ширине двигать нечего, по высоте - есть куда.
    panned = pan_by(wide, 0.2, 0.2, img_w, img_h)
    ok("панорама: сдвиг работает", abs(panned.pan_y) > 0.01 and abs(panned.pan_x) < 0.002
       and covers(panned, img_w, img_h), "pan=%.4f/%.4f" % (panned.pan_x, panned.pan_y))

    # 8. Свободная обрезка уголками.
    free = with_corner(start, 0, 0.1, 0.05, img_w, img_h)
    ok("уголок: кадр стал свободным",
       free.aspect == ASPECT_FREE and free.frame.left > start.frame.left
       and free.frame.top > start.frame.top)
    squeezed = free
    for _ in range(40):
        squeezed = with_corner(squeezed, 2, -0.5, -0.5, img_w, img_h)
    ok("уголок: не переворачивается и не схлопывается",
       squeezed.frame.right > squeezed.frame.left and squeezed.frame.bottom > squeezed.frame.top
       and squeezed.frame.height >= MIN_FRAME - 1e-4 and squeezed.frame.width >= MIN_FRAME / STORY_ASPECT - 1e-4)
    out_of_bounds = with_corner(start, 1, 9.0, -9.0, img_w, img_h)
    ok("уголок: не выходит за кадр истории",
       out_of_bounds.frame.right <= 1.0 + 1e-6 and out_of_bounds.frame.top >= -1e-6)

    # 9. Сброс.
    messy = toggle_flip_h(zoom_by(with_straighten(with_quarter(start, img_w, img_h), 12.0, img_w, img_h), 3.0, img_w, img_h), img_w, img_h)
    fresh = reset(img_w, img_h)
    ok("сброс: как при открытии",
       not is_edited(fresh, img_w, img_h) and is_edited(messy, img_w, img_h) and not is_edited(start, img_w, img_h))

    # 10. Матрица - одна на предпросмотр и публикацию. Пиксели тут не нужны:
    #     берём каждую точку кадра и смотрим, куда она попадает в снимке.
    def frame_bounds(st, w, h, step=11):
        """Границы координат снимка, попадающих в кадр, и число проверенных точек."""
        out_w, out_h = render_size(st, w, h)
        inv = matrix_inverse(matrix(st, w, h, float(out_w), float(out_h)))
        lo_x = lo_y = 1e9
        hi_x = hi_y = -1e9
        probes = []
        for x in range(out_w):
            probes.append((x + 0.5, 0.5))
            probes.append((x + 0.5, out_h - 0.5))
        for y in range(out_h):
            probes.append((0.5, y + 0.5))
            probes.append((out_w - 0.5, y + 0.5))
        for y in range(0, out_h, step):
            for x in range(0, out_w, step):
                probes.append((x + 0.5, y + 0.5))
        for px, py in probes:
            sx = inv[0] * px + inv[1] * py + inv[2]
            sy = inv[3] * px + inv[4] * py + inv[5]
            lo_x = min(lo_x, sx)
            lo_y = min(lo_y, sy)
            hi_x = max(hi_x, sx)
            hi_y = max(hi_y, sy)
        return (lo_x, lo_y, hi_x, hi_y), len(probes)

    def inside(lo_x, lo_y, hi_x, hi_y, w, h, slack=1.5):
        return lo_x >= -slack and lo_y >= -slack and hi_x <= w + slack and hi_y <= h + slack

    def where(st, w, h, point):
        """Доли кадра, куда попала точка снимка (0..1 по обеим осям)."""
        out_w, out_h = render_size(st, w, h)
        m = matrix(st, w, h, float(out_w), float(out_h))
        fx = m[0] * point[0] + m[1] * point[1] + m[2]
        fy = m[3] * point[0] + m[4] * point[1] + m[5]
        return fx / out_w, fy / out_h

    def side(frac, limit=0.45):
        return "left" if frac[0] < limit else ("right" if frac[0] > 1 - limit else "middle"), \
               "up" if frac[1] < limit else ("down" if frac[1] > 1 - limit else "middle")

    st0 = initial_state(800, 600)
    mark = (60.0, 45.0)  # центр метки в левом верхнем углу снимка
    ok("матрица: без правок метка слева сверху", side(where(st0, 800, 600, mark))[0] == "left"
       and side(where(st0, 800, 600, mark))[1] == "up", "%s" % (where(st0, 800, 600, mark),))
    turned = with_quarter(st0, 800, 600)
    ok("матрица: поворот по часовой", side(where(turned, 800, 600, mark))[0] == "right"
       and side(where(turned, 800, 600, mark))[1] == "up", "%s" % (where(turned, 800, 600, mark),))
    mirrored = toggle_flip_h(st0, 800, 600)
    ok("матрица: отражение по горизонтали", side(where(mirrored, 800, 600, mark))[0] == "right")
    flipped_v = toggle_flip_v(st0, 800, 600)
    ok("матрица: отражение по вертикали", side(where(flipped_v, 800, 600, mark))[1] == "down")

    # Кадр всегда внутри снимка: ни одного пустого угла ни в одном состоянии.
    states = [st0]
    for quarter in (0, 1, 2, 3):
        for deg in (-45.0, -20.0, 0.0, 20.0, 45.0):
            st = with_rotation(st0, quarter * 90.0 + deg, 800, 600)
            for aspect in (ASPECT_ORIGINAL, ASPECT_STORY, ASPECT_WIDE, ASPECT_SQUARE):
                states.append(with_aspect(st, aspect, 800, 600))
                states.append(zoom_by(with_aspect(st, aspect, 800, 600), 1.6, 800, 600))
                states.append(pan_by(with_aspect(st, aspect, 800, 600), 0.15, -0.4, 800, 600))
                states.append(pan_by(zoom_by(with_aspect(st, aspect, 800, 600), 1.4, 800, 600), 9.0, 9.0, 800, 600))
                states.append(with_corner(with_aspect(st, aspect, 800, 600), 0, 0.12, 0.2, 800, 600))
                states.append(with_corner(with_aspect(st, aspect, 800, 600), 3, -0.12, 0.25, 800, 600))
    outside = []
    probes = 0
    for st in states:
        bounds, count = frame_bounds(st, 800, 600)
        probes += count
        if not inside(*bounds, 800, 600):
            outside.append("%r -> %.1f/%.1f/%.1f/%.1f" % (st, bounds[0], bounds[1], bounds[2], bounds[3]))
    ok("матрица: кадр всегда внутри снимка (%d состояний, %d точек)" % (len(states), probes),
       not outside, "; ".join(outside[:2]))

    # Проверка не пустая: без ограничителя кадр обязан вылезти за снимок.
    rough = st0.copy()
    rough.zoom = 1.0
    rough.pan_x = 3.0
    bounds, _ = frame_bounds(rough, 800, 600)
    ok("матрица: проверка ловит вылезший кадр", not inside(*bounds, 800, 600),
       "даже ограниченный сдвиг дал %.1f/%.1f" % (bounds[0], bounds[2]))

    # Пропорция результата - ровно пропорция рамки.
    bad = []
    for st in (with_aspect(st0, ASPECT_SQUARE, 800, 600),
               with_aspect(st0, ASPECT_WIDE, 800, 600),
               with_corner(st0, 0, 0.05, 0.08, 800, 600),
               with_rotation(with_aspect(st0, ASPECT_PORTRAIT, 800, 600), 20.0, 800, 600)):
        out_w, out_h = render_size(st, 800, 600)
        if abs(out_w / out_h - st.frame.aspect) > 0.02:
            bad.append("%r -> %dx%d" % (st.frame, out_w, out_h))
    ok("размер кадра: пропорция рамки", not bad, "; ".join(bad))

    return passed, failed


def main():
    passed, failed = checks()
    print("сверок: %d, провалов: %d" % (len(passed) + len(failed), len(failed)))
    for name, detail in failed:
        print("  ПРОВАЛ: %s %s" % (name, detail))
    if not failed:
        print("все прошли")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
