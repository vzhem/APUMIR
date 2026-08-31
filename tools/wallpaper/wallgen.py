#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Генератор фирменных обоев чата: тёмная и светлая тема.

Свой набор из 110 контурных значков на тему децентрализованного
мессенджера (сервера, топологии, соединения, письма, группы, устройства,
приватность, блоки). Значки рисуются штрихами, раскладываются сеткой со
случайным поворотом/масштабом - как в референсе, но со своей тематикой.
"""
import math
import random

# ---------------------------------------------------------------- helpers
class G:
    """Набор штрихов в локальных координатах значка (бокс 48x48, центр 0,0)."""

    def __init__(self):
        self.items = []

    def l(self, x1, y1, x2, y2):
        self.items.append(("line", (x1, y1, x2, y2)))

    def p(self, pts, closed=False):
        self.items.append(("poly", (pts, closed)))

    def r(self, l, t, r, b):
        self.items.append(("rect", (l, t, r, b)))

    def rr(self, l, t, r, b, rad=3):
        self.items.append(("rrect", (l, t, r, b, rad)))

    def c(self, cx, cy, rad):
        self.items.append(("circle", (cx, cy, rad)))

    def e(self, cx, cy, rx, ry):
        self.items.append(("ellipse", (cx, cy, rx, ry)))

    def a(self, cx, cy, rad, a0, a1):
        self.items.append(("arc", (cx, cy, rad, rad, a0, a1)))

    def ae(self, cx, cy, rx, ry, a0, a1):
        self.items.append(("arc", (cx, cy, rx, ry, a0, a1)))

    def dot(self, cx, cy, rad=2):
        self.items.append(("dot", (cx, cy, rad)))


ICONS = {}
NAMES = {}


def icon(name, ru):
    def deco(fn):
        ICONS[name] = fn
        NAMES[name] = ru
        return fn
    return deco


# ---------------------------------------------------------------- 1..40
@icon("server_rack", "серверная стойка")
def _(g):
    g.rr(-14, -16, 14, -4, 3); g.rr(-14, 4, 14, 16, 3)
    g.dot(-9, -10, 1.8); g.dot(-9, 10, 1.8)
    g.l(1, -10, 9, -10); g.l(1, 10, 9, 10)


@icon("server_tower", "сервер-башня")
def _(g):
    g.rr(-8, -16, 8, 16, 3)
    g.l(-4, -10, 4, -10); g.l(-4, -4, 4, -4); g.dot(0, 9, 1.8)


@icon("node", "узел сети")
def _(g):
    g.c(0, 0, 12); g.dot(0, 0, 3)


@icon("mesh", "mesh-сеть")
def _(g):
    pts = [(-12, -12), (12, -12), (12, 12), (-12, 12)]
    for x, y in pts:
        g.c(x, y, 4)
    g.l(-8, -12, 8, -12); g.l(-8, 12, 8, 12)
    g.l(-12, -8, -12, 8); g.l(12, -8, 12, 8)
    g.l(-9, -9, 9, 9); g.l(-9, 9, 9, -9)


@icon("star_topo", "топология звезда")
def _(g):
    g.c(0, 0, 4)
    for x, y in [(0, -14), (14, 0), (0, 14), (-14, 0)]:
        g.dot(x, y, 2.5)
    g.l(0, -4, 0, -11); g.l(4, 0, 11, 0); g.l(0, 4, 0, 11); g.l(-4, 0, -11, 0)


@icon("ring_topo", "топология кольцо")
def _(g):
    n = 5
    pts = [(13 * math.cos(math.tau * i / n - math.pi / 2),
            13 * math.sin(math.tau * i / n - math.pi / 2)) for i in range(n)]
    for i in range(n):
        x, y = pts[i]
        g.c(x, y, 3)
        x2, y2 = pts[(i + 1) % n]
        g.l(x * 0.78, y * 0.78, x2 * 0.78, y2 * 0.78)


@icon("bus_topo", "топология шина")
def _(g):
    g.l(-16, 10, 16, 10)
    for x, h in [(-10, 0), (0, -4), (10, 0)]:
        g.l(x, 10, x, h + 2); g.dot(x, h, 2.5)


@icon("tree_topo", "топология дерево")
def _(g):
    g.dot(0, -12, 3); g.dot(-9, 0, 3); g.dot(9, 0, 3)
    g.dot(-13, 11, 2.5); g.dot(-5, 11, 2.5); g.dot(5, 11, 2.5); g.dot(13, 11, 2.5)
    g.l(-2, -10, -7, -3); g.l(2, -10, 7, -3)
    g.l(-11, 2, -12, 9); g.l(-7, 2, -6, 9); g.l(7, 2, 6, 9); g.l(11, 2, 12, 9)


@icon("p2p", "p2p-обмен")
def _(g):
    g.a(0, 0, 12, 20, 160); g.a(0, 0, 12, 200, 340)
    g.p([(11, 3), (13, 8), (7, 8)])
    g.p([(-11, -3), (-13, -8), (-7, -8)])


@icon("handshake", "рукопожатие пиров")
def _(g):
    g.p([(-15, 6), (-3, 6), (5, -2), (15, -2)])
    g.p([(-15, -2), (-7, -2), (1, 6), (15, 6)])


@icon("envelope", "конверт")
def _(g):
    g.r(-14, -9, 14, 9); g.p([(-14, -9), (0, 2), (14, -9)])


@icon("envelope_seal", "конверт с печатью")
def _(g):
    g.r(-14, -10, 14, 8); g.p([(-14, -10), (0, 0), (14, -10)])
    g.c(0, 3, 3)


@icon("letter_open", "распечатанное письмо")
def _(g):
    g.r(-13, -4, 13, 10)
    g.p([(-13, -4), (0, -14), (13, -4)])
    g.l(-8, 1, 8, 1); g.l(-8, 5, 3, 5)


@icon("at_sign", "собака-адрес")
def _(g):
    g.c(0, 0, 6)
    g.a(0, 0, 12, -50, 230)
    g.l(9, 6, 12, 9)


@icon("hash", "хэш")
def _(g):
    g.l(-5, -12, -7, 12); g.l(6, -12, 4, 12)
    g.l(-12, -5, 12, -5); g.l(-12, 5, 12, 5)


@icon("key", "ключ шифрования")
def _(g):
    g.c(-8, 0, 6); g.l(-2, 0, 14, 0); g.l(8, 0, 8, 6); g.l(13, 0, 13, 5)


@icon("lock", "навесной замок")
def _(g):
    g.rr(-10, -2, 10, 14, 3); g.a(0, -2, 7, 180, 360); g.dot(0, 5, 2)


@icon("shield_lock", "щит с замком")
def _(g):
    g.p([(-11, -13), (11, -13), (11, 1), (0, 14), (-11, 1)], closed=True)
    g.dot(0, -3, 2.5); g.l(0, -1, 0, 5)


@icon("shield_check", "щит с галкой")
def _(g):
    g.p([(-11, -13), (11, -13), (11, 1), (0, 14), (-11, 1)], closed=True)
    g.p([(-5, -3), (-1, 2), (6, -7)])


@icon("fingerprint", "отпечаток")
def _(g):
    g.a(0, 3, 12, 180, 360); g.a(0, 3, 7, 180, 360)
    g.l(0, 3, 0, 12); g.l(-12, 3, -12, 8); g.l(12, 3, 12, 8)


@icon("qr", "QR-код")
def _(g):
    g.r(-14, -14, -4, -4); g.r(4, -14, 14, -4); g.r(-14, 4, -4, 14)
    g.dot(-9, -9, 1.8); g.dot(9, -9, 1.8); g.dot(-9, 9, 1.8)
    g.dot(6, 6, 1.8); g.dot(12, 10, 1.8); g.dot(7, 12, 1.8)


@icon("barcode", "штрих-код")
def _(g):
    for x in (-12, -7, -3, 0, 4, 8, 12):
        g.l(x, -8, x, 8)


@icon("group3", "группа")
def _(g):
    g.c(-11, -5, 4); g.a(-11, 4, 6, 180, 360)
    g.c(11, -5, 4); g.a(11, 4, 6, 180, 360)
    g.c(0, -7, 5); g.a(0, 5, 8, 180, 360)


@icon("users2", "два собеседника")
def _(g):
    g.c(-6, -7, 5); g.a(-6, 5, 8, 180, 360)
    g.c(8, -5, 4); g.a(8, 5, 6, 180, 360)


@icon("user", "профиль")
def _(g):
    g.c(0, -7, 6); g.a(0, 9, 11, 180, 360)


@icon("desktop", "компьютер")
def _(g):
    g.r(-13, -13, 13, 7); g.l(0, 7, 0, 13); g.l(-7, 13, 7, 13)


@icon("laptop", "ноутбук")
def _(g):
    g.r(-11, -11, 11, 5)
    g.l(-15, 9, 15, 9); g.l(-11, 5, -15, 9); g.l(11, 5, 15, 9)


@icon("phone", "смартфон")
def _(g):
    g.rr(-8, -14, 8, 14, 4); g.l(-3, 10, 3, 10)


@icon("tablet", "планшет")
def _(g):
    g.rr(-11, -14, 11, 14, 4); g.dot(0, 10, 1.5)


@icon("router", "роутер")
def _(g):
    g.rr(-13, 2, 13, 12, 3)
    g.l(-8, 2, -8, -9); g.l(8, 2, 8, -9)
    g.dot(-8, 7, 1.5); g.dot(-2, 7, 1.5)


@icon("tower", "вышка связи")
def _(g):
    g.l(0, -10, -8, 14); g.l(0, -10, 8, 14)
    g.l(-4, 2, 4, 2); g.l(-6, 8, 6, 8)
    g.a(0, -12, 5, 180, 360); g.a(0, -12, 9, 200, 340)


@icon("dish", "спутниковая тарелка")
def _(g):
    g.a(-2, 0, 11, 90, 270)
    g.l(-2, -11, 8, -8); g.dot(9, -8, 2)
    g.l(-4, 8, -8, 15); g.l(0, 8, 4, 15)


@icon("satellite", "спутник")
def _(g):
    g.r(-4, -4, 4, 4)
    g.rr(-18, -8, -7, 0, 2); g.rr(7, -8, 18, 0, 2)
    g.l(0, -4, 0, -9); g.dot(0, -11, 1.5)


@icon("wifi", "вай-фай")
def _(g):
    g.a(0, 8, 15, 200, 340); g.a(0, 8, 10, 210, 330); g.a(0, 8, 5, 220, 320)
    g.dot(0, 9, 2)


@icon("radio", "радиоволны")
def _(g):
    g.dot(0, 0, 2.5)
    g.a(0, 0, 8, -45, 45); g.a(0, 0, 13, -40, 40)
    g.a(0, 0, 8, 135, 225); g.a(0, 0, 13, 140, 220)


@icon("rj45", "кабель RJ45")
def _(g):
    g.r(-5, -12, 5, 0)
    g.l(-3, -12, -3, -6); g.l(0, -12, 0, -6); g.l(3, -12, 3, -6)
    g.r(-7, 0, 7, 10); g.l(0, 10, 0, 16)


@icon("plug", "штекер питания")
def _(g):
    g.rr(-7, -4, 7, 6, 2)
    g.l(-3, -4, -3, -13); g.l(3, -4, 3, -13)
    g.l(0, 6, 0, 14)


@icon("switch", "сетевой свитч")
def _(g):
    g.rr(-15, -5, 15, 7, 2)
    g.r(-12, -1, -8, 3); g.r(-5, -1, -1, 3); g.r(2, -1, 6, 3)
    g.l(9, -1, 12, -1); g.dot(12, -1, 1.2)


@icon("hub", "хаб с лучами")
def _(g):
    g.c(0, 0, 6)
    for ang in range(0, 360, 45):
        x, y = math.cos(math.radians(ang)), math.sin(math.radians(ang))
        g.l(x * 6, y * 6, x * 12, y * 12)
        g.dot(x * 14, y * 14, 1.6)


# ---------------------------------------------------------------- 41..110
@icon("cloud", "облако")
def _(g):
    g.a(-7, 2, 7, 90, 270); g.a(0, -3, 8, 180, 360); g.a(8, 2, 6, 270, 90)
    g.l(-7, 9, 8, 9)


@icon("cloud_sync", "облачная синхронизация")
def _(g):
    g.a(-7, -4, 6, 90, 270); g.a(0, -8, 7, 180, 360); g.a(7, -4, 5, 270, 90)
    g.l(-7, 2, 7, 2)
    g.a(0, 11, 6, 0, 150); g.a(0, 11, 6, 180, 330)
    g.p([(6, 9), (8, 13), (3, 14)])


@icon("database", "база данных")
def _(g):
    g.e(0, -10, 12, 5)
    g.l(-12, -10, -12, 10); g.l(12, -10, 12, 10)
    g.ae(0, 0, 12, 5, 0, 180); g.ae(0, 10, 12, 5, 0, 180)


@icon("chain_blocks", "цепочка блоков")
def _(g):
    g.r(-17, -5, -7, 5); g.r(-5, -5, 5, 5); g.r(7, -5, 17, 5)
    g.l(-7, 0, -5, 0); g.l(5, 0, 7, 0)


@icon("link", "звено связи")
def _(g):
    g.rr(-14, -5, 0, 5, 5); g.rr(0, -5, 14, 5, 5); g.l(-4, 0, 4, 0)


@icon("block_hash", "блок с хэшем")
def _(g):
    g.r(-11, -11, 11, 11)
    g.l(-3, -6, -4, 6); g.l(4, -6, 3, 6)
    g.l(-6, -3, 6, -3); g.l(-6, 3, 6, 3)


@icon("globe", "глобус")
def _(g):
    g.c(0, 0, 13); g.e(0, 0, 6, 13); g.l(-13, 0, 13, 0)


@icon("orbit", "орбита узла")
def _(g):
    g.c(0, 0, 8); g.e(0, 0, 16, 6); g.dot(12, -3, 2)


@icon("pin", "метка на карте")
def _(g):
    g.c(0, -5, 7)
    g.l(-4, 1, 0, 13); g.l(4, 1, 0, 13)
    g.dot(0, -5, 2.5)


@icon("compass", "компас")
def _(g):
    g.c(0, 0, 13)
    g.p([(0, -8), (4, 0), (0, 8), (-4, 0)], closed=True)


@icon("clock", "время доставки")
def _(g):
    g.c(0, 0, 13); g.l(0, 0, 0, -8); g.l(0, 0, 6, 3)


@icon("hourglass", "ожидание")
def _(g):
    g.p([(-9, -13), (9, -13), (0, 0)], closed=True)
    g.p([(-9, 13), (9, 13), (0, 0)], closed=True)


@icon("check1", "галочка доставлено")
def _(g):
    g.p([(-9, 0), (-2, 8), (11, -8)])


@icon("check2", "две галочки прочитано")
def _(g):
    g.p([(-13, 0), (-7, 7), (3, -5)])
    g.p([(-3, 1), (3, 8), (13, -4)])


@icon("bubble", "пузырь сообщения")
def _(g):
    g.rr(-13, -11, 13, 7, 6)
    g.p([(-7, 7), (-7, 13), (-1, 7)])


@icon("bubble_dots", "собеседник печатает")
def _(g):
    g.rr(-13, -11, 13, 7, 6)
    g.p([(-7, 7), (-7, 13), (-1, 7)])
    g.dot(-6, -2, 1.6); g.dot(0, -2, 1.6); g.dot(6, -2, 1.6)


@icon("bubble_heart", "сообщение с сердцем")
def _(g):
    g.rr(-13, -11, 13, 7, 6)
    g.p([(-7, 7), (-7, 13), (-1, 7)])
    g.a(-2.5, -4, 3, 90, 270); g.a(2.5, -4, 3, 270, 90)
    g.p([(-5.4, -3), (0, 3), (5.4, -3)])


@icon("megaphone", "мегафон анонсов")
def _(g):
    g.p([(-12, -4), (4, -11), (4, 7), (-12, 2)], closed=True)
    g.r(-11, 2, -5, 9)
    g.a(8, -2, 6, -60, 60)


@icon("bell", "уведомление")
def _(g):
    g.a(0, -1, 11, 180, 360)
    g.l(-11, -1, -13, 6); g.l(11, -1, 13, 6); g.l(-13, 6, 13, 6)
    g.dot(0, 10, 2.5)


@icon("clip", "скрепка вложения")
def _(g):
    g.a(0, -7, 6, 180, 360)
    g.l(-6, -7, -6, 9); g.a(0, 9, 6, 0, 180); g.l(6, 9, 6, -3)
    g.a(0, -3, 3, 180, 360); g.l(-3, -3, -3, 5)


@icon("photo", "фотография")
def _(g):
    g.r(-13, -10, 13, 10)
    g.c(-6, -4, 2.5)
    g.p([(-13, 8), (-4, -2), (2, 4), (7, -1), (13, 8)])


@icon("mic", "голосовое")
def _(g):
    g.rr(-5, -14, 5, 2, 5)
    g.a(0, -3, 9, 0, 180)
    g.l(0, 6, 0, 12); g.l(-6, 12, 6, 12)


@icon("videocam", "видеозвонок")
def _(g):
    g.r(-14, -8, 6, 8)
    g.p([(6, -4), (14, -9), (14, 9), (6, 4)], closed=True)


@icon("call", "звонок")
def _(g):
    g.c(-9, 9, 4); g.c(9, -9, 4)
    g.a(0, 0, 11, 200, 340)


@icon("headset", "гарнитура")
def _(g):
    g.a(0, 0, 12, 180, 360)
    g.rr(-15, 0, -9, 9, 2); g.rr(9, 0, 15, 9, 2)


@icon("search", "поиск")
def _(g):
    g.c(-3, -3, 9); g.l(4, 4, 13, 13)


@icon("gear", "настройки")
def _(g):
    g.c(0, 0, 6); g.dot(0, 0, 2)
    for ang in range(0, 360, 45):
        x, y = math.cos(math.radians(ang)), math.sin(math.radians(ang))
        g.l(x * 6, y * 6, x * 11, y * 11)


@icon("sliders", "ползунки")
def _(g):
    g.l(-13, -8, 13, -8); g.dot(-5, -8, 2.5)
    g.l(-13, 0, 13, 0); g.dot(6, 0, 2.5)
    g.l(-13, 8, 13, 8); g.dot(-1, 8, 2.5)


@icon("star", "избранное")
def _(g):
    pts = []
    for i in range(10):
        r = 13 if i % 2 == 0 else 5.5
        a = math.tau * i / 20 - math.pi / 2
        pts.append((r * math.cos(a), r * math.sin(a)))
    g.p(pts, closed=True)


@icon("bookmark", "закладка")
def _(g):
    g.p([(-8, -13), (8, -13), (8, 13), (0, 6), (-8, 13)], closed=True)


@icon("plane", "бумажный самолётик")
def _(g):
    g.p([(-14, 0), (14, -10), (-4, 12), (-1, 3)], closed=True)
    g.l(-1, 3, 14, -10)


@icon("download", "входящий файл")
def _(g):
    g.l(0, -13, 0, 4); g.p([(-6, -2), (0, 5), (6, -2)])
    g.p([(-11, 6), (-11, 13), (11, 13), (11, 6)])


@icon("upload", "исходящий файл")
def _(g):
    g.l(0, 5, 0, -7); g.p([(-6, -1), (0, -8), (6, -1)])
    g.p([(-11, 6), (-11, 13), (11, 13), (11, 6)])


@icon("sync", "синхронизация")
def _(g):
    g.a(0, 0, 11, 10, 150); g.a(0, 0, 11, 190, 330)
    g.p([(8, 6), (12, 10), (5, 11)])
    g.p([(-8, -6), (-12, -10), (-5, -11)])


@icon("branch", "ветка реплик")
def _(g):
    g.c(-8, -10, 3); g.c(-8, 10, 3); g.c(10, -8, 3)
    g.l(-8, -7, -8, 7)
    g.p([(10, -5), (10, 2), (-2, 6), (-5, 8)])


@icon("commit", "коммит журнала")
def _(g):
    g.l(-14, 0, -5, 0); g.l(5, 0, 14, 0); g.c(0, 0, 5)


@icon("terminal", "терминал")
def _(g):
    g.rr(-13, -10, 13, 10, 2)
    g.p([(-8, -5), (-3, 0), (-8, 5)])
    g.l(1, 6, 8, 6)


@icon("code", "исходный код")
def _(g):
    g.p([(-8, -6), (-14, 0), (-8, 6)])
    g.p([(8, -6), (14, 0), (8, 6)])
    g.l(3, -9, -3, 9)


@icon("binary", "двоичный поток")
def _(g):
    g.l(-10, -12, -10, -5); g.c(-10, 5, 4)
    g.c(0, -8, 4); g.l(0, -1, 0, 7)
    g.l(10, -12, 10, -5); g.c(10, 5, 4)


@icon("matrix", "матрица точек")
def _(g):
    for x in (-9, 0, 9):
        for y in (-9, 0, 9):
            g.dot(x, y, 1.8)


@icon("chart", "график трафика")
def _(g):
    g.l(-12, -12, -12, 12); g.l(-12, 12, 12, 12)
    g.l(-7, 12, -7, 3); g.l(-1, 12, -1, -3); g.l(5, 12, 5, -9)


@icon("log", "журнал событий")
def _(g):
    g.rr(-11, -13, 11, 13, 2)
    g.dot(-6, -7, 1.4); g.l(-2, -7, 7, -7)
    g.dot(-6, 0, 1.4); g.l(-2, 0, 7, 0)
    g.dot(-6, 7, 1.4); g.l(-2, 7, 7, 7)


@icon("file", "файл")
def _(g):
    g.p([(-9, -13), (3, -13), (9, -7), (9, 13), (-9, 13)], closed=True)
    g.l(3, -13, 3, -7); g.l(3, -7, 9, -7)


@icon("folder", "папка")
def _(g):
    g.p([(-13, -9), (-4, -9), (-1, -5), (13, -5), (13, 10), (-13, 10)], closed=True)


@icon("archive", "архив")
def _(g):
    g.r(-13, -11, 13, -3); g.r(-11, -3, 11, 12); g.l(-3, 2, 3, 2)


@icon("keys_pair", "пара ключей")
def _(g):
    g.c(-9, -9, 5); g.l(-5, -5, 11, 11)
    g.l(6, 6, 6, 11); g.l(10, 10, 5, 10)
    g.c(9, -9, 5); g.l(5, -5, -7, 7)
    g.l(-3, 3, -3, 8)


@icon("seal", "печать-сертификат")
def _(g):
    g.c(0, -3, 9); g.c(0, -3, 5)
    g.l(-4, 5, -7, 14); g.l(4, 5, 7, 14)


@icon("pen_sign", "подпись")
def _(g):
    g.p([(9, -14), (14, -9), (1, 4), (-4, 4), (-4, -1)], closed=True)
    g.p([(-13, 12), (-9, 8), (-5, 12), (-1, 8), (3, 12)])


@icon("certificate", "сертификат узла")
def _(g):
    g.r(-12, -12, 12, 6)
    g.l(-8, -7, 4, -7); g.l(-8, -2, 0, -2)
    g.c(5, 7, 5); g.l(3, 11, 1, 16); g.l(7, 11, 9, 16)


@icon("badge", "бейдж участника")
def _(g):
    g.rr(-9, -12, 9, 13, 2)
    g.p([(-4, -16), (0, -12), (4, -16)])
    g.c(0, -5, 4); g.l(-5, 3, 5, 3); g.l(-5, 8, 5, 8)


@icon("card", "карточка контакта")
def _(g):
    g.rr(-14, -9, 14, 9, 2)
    g.c(-7, 0, 3.5); g.l(0, -3, 9, -3); g.l(0, 3, 9, 3)


@icon("relay", "ретранслятор")
def _(g):
    g.p([(-13, 13), (-10, -1), (-7, 13)])
    g.p([(7, 13), (10, -1), (13, 13)])
    g.a(0, -4, 6, 200, 340); g.a(0, -6, 10, 210, 330)


@icon("beacon", "маяк")
def _(g):
    g.p([(-5, 14), (-3, -7), (3, -7), (5, 14)])
    g.r(-3, -12, 3, -7)
    g.l(-7, -10, -12, -12); g.l(7, -10, 12, -12)


@icon("radar", "радар")
def _(g):
    g.c(0, 0, 13)
    g.l(0, 0, 9, -9); g.a(0, 0, 9, 280, 350)
    g.dot(5, 5, 2)


@icon("crossover", "скрещенные кабели")
def _(g):
    g.r(-16, -10, -10, -4); g.r(10, 4, 16, 10)
    g.p([(-10, -7), (10, 7)])
    g.r(-16, 4, -10, 10); g.r(10, -10, 16, -4)
    g.p([(-10, 7), (10, -7)])


@icon("port", "порт с пинами")
def _(g):
    g.c(0, 2, 9); g.dot(0, 2, 3)
    g.l(-4, -7, -4, -13); g.l(0, -7, 0, -13); g.l(4, -7, 4, -13)


@icon("eye", "наблюдение отключено")
def _(g):
    g.ae(0, 0, 13, 8, 180, 360); g.ae(0, 0, 13, 8, 0, 180)
    g.c(0, 0, 4)


@icon("eye_off", "приватность")
def _(g):
    g.ae(0, 0, 13, 8, 180, 360); g.ae(0, 0, 13, 8, 0, 180)
    g.c(0, 0, 4); g.l(-14, 12, 14, -12)


@icon("safe", "сейф")
def _(g):
    g.rr(-13, -13, 13, 13, 3)
    g.c(0, 0, 5); g.l(0, -5, 0, -8); g.l(5, 0, 8, 0)
    g.dot(0, 0, 1.5)


@icon("forward", "пересылка письма")
def _(g):
    g.r(-14, -8, 2, 6)
    g.p([(-14, -8), (-6, -2), (2, -8)])
    g.l(0, 0, 12, 0); g.p([(8, -4), (13, 0), (8, 4)])


@icon("bell_dot", "новое уведомление")
def _(g):
    g.a(0, -1, 10, 180, 360)
    g.l(-10, -1, -12, 6); g.l(10, -1, 12, 6); g.l(-12, 6, 12, 6)
    g.dot(9, -9, 3)


@icon("moon", "ночной режим")
def _(g):
    g.a(0, 0, 12, 90, 270)
    g.ae(0, 0, 6, 12, 270, 90)


@icon("sun", "дневной режим")
def _(g):
    g.c(0, 0, 7)
    for ang in range(0, 360, 45):
        x, y = math.cos(math.radians(ang)), math.sin(math.radians(ang))
        g.l(x * 10, y * 10, x * 14, y * 14)


@icon("battery", "заряд устройства")
def _(g):
    g.rr(-14, -7, 10, 7, 2); g.l(12, -3, 12, 3)
    g.p([(0, -4), (-3, 1), (0, 1), (-2, 4)])


@icon("sim", "сим-карта")
def _(g):
    g.p([(-9, -13), (4, -13), (9, -8), (9, 13), (-9, 13)], closed=True)
    g.r(-4, 0, 4, 8); g.l(-4, 4, 4, 4)


@icon("chip", "чип")
def _(g):
    g.r(-9, -9, 9, 9); g.r(-4, -4, 4, 4)
    for t in (-6, 0, 6):
        g.l(t, -9, t, -14); g.l(t, 9, t, 14)
        g.l(-9, t, -14, t); g.l(9, t, 14, t)


@icon("antenna", "антенна узла")
def _(g):
    g.l(0, 14, 0, -2); g.dot(0, -4, 2)
    g.a(0, -4, 7, 180, 360); g.a(0, -4, 12, 180, 360)


@icon("bars", "уровень сигнала")
def _(g):
    g.l(-12, 12, -12, 6); g.l(-4, 12, -4, 0)
    g.l(4, 12, 4, -6); g.l(12, 12, 12, -12)


@icon("globe_net", "глобальная сеть")
def _(g):
    g.c(0, 0, 13)
    g.dot(-6, -5, 2); g.dot(7, -2, 2); g.dot(-1, 7, 2)
    g.l(-6, -5, 7, -2); g.l(7, -2, -1, 7); g.l(-1, 7, -6, -5)


@icon("mail_stack", "стопка писем")
def _(g):
    g.p([(-9, -6), (-9, -11), (13, -11), (13, -2)])
    g.r(-13, -6, 9, 8)
    g.p([(-13, -6), (-2, 1), (9, -6)])


# ---------------------------------------------------------------- render
SS = 2  # суперсэмплинг значков


def render_icon(fn, px, angle, color, width=3):
    """Рисует значок в px пикселей (после даунскейла), поворот angle."""
    g = G()
    fn(g)
    size = px * SS
    img = None
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = size / 56.0  # 48-бокс + поля
    cx = size / 2.0

    def X(x):
        return cx + x * k

    for kind, a in g.items:
        if kind == "line":
            d.line([X(a[0]), X(a[1]), X(a[2]), X(a[3])], fill=color, width=width)
        elif kind == "poly":
            pts, closed = a
            P = [(X(x), X(y)) for x, y in pts]
            if closed:
                P.append(P[0])
            d.line(P, fill=color, width=width, joint="curve")
        elif kind == "rect":
            d.rectangle([X(a[0]), X(a[1]), X(a[2]), X(a[3])], outline=color, width=width)
        elif kind == "rrect":
            d.rounded_rectangle(
                [X(a[0]), X(a[1]), X(a[2]), X(a[3])],
                radius=a[4] * k, outline=color, width=width)
        elif kind == "circle":
            d.ellipse([X(a[0] - a[2]), X(a[1] - a[2]), X(a[0] + a[2]), X(a[1] + a[2])],
                      outline=color, width=width)
        elif kind == "ellipse":
            d.ellipse([X(a[0] - a[2]), X(a[1] - a[3]), X(a[0] + a[2]), X(a[1] + a[3])],
                      outline=color, width=width)
        elif kind == "arc":
            d.arc([X(a[0] - a[2]), X(a[1] - a[3]), X(a[0] + a[2]), X(a[1] + a[3])],
                  a[4], a[5], fill=color, width=width)
        elif kind == "dot":
            d.ellipse([X(a[0] - a[2]), X(a[1] - a[2]), X(a[0] + a[2]), X(a[1] + a[2])],
                      fill=color)
    img = img.resize((px, px), Image.LANCZOS)
    if angle:
        img = img.rotate(angle, expand=True, resample=Image.BICUBIC)
    return img


def lerp(c1, c2, t):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


def make_background(dark, W, H):
    from PIL import Image
    gw, gh = 54, 96
    px = []
    for j in range(gh):
        for i in range(gw):
            u, v = i / (gw - 1), j / (gh - 1)
            if dark:
                c = lerp((5, 8, 7), (10, 15, 12), v * 0.7 + u * 0.3)
                # лёгкие цветные пятна, как в референсе
                b1 = math.exp(-(((u - 0.2) ** 2 + (v - 0.15) ** 2) * 9)) * 0.5
                b2 = math.exp(-(((u - 0.85) ** 2 + (v - 0.7) ** 2) * 8)) * 0.5
                c = lerp(c, (10, 22, 18), b1)
                c = lerp(c, (14, 16, 26), b2)
            else:
                d0 = (u + v) / 2
                c = lerp((226, 228, 160), (143, 191, 154), min(1, d0 * 1.6))
                c = lerp(c, (222, 230, 214), max(0, (d0 - 0.6) * 2.0))
                b1 = math.exp(-(((u - 0.75) ** 2 + (v - 0.2) ** 2) * 7))
                c = lerp(c, (233, 236, 205), b1 * 0.8)
                b2 = math.exp(-(((u - 0.15) ** 2 + (v - 0.8) ** 2) * 7))
                c = lerp(c, (150, 190, 160), b2 * 0.7)
            px.append(c)
    img = Image.new("RGB", (gw, gh))
    img.putdata(px)
    return img.resize((W, H), Image.BICUBIC)


def compose(dark, W, H, seed):
    from PIL import Image
    rng = random.Random(seed)
    canvas = make_background(dark, W, H).convert("RGBA")
    if dark:
        palette = [(65, 112, 95), (60, 107, 107), (71, 105, 79), (79, 95, 120), (63, 111, 82)]
        alpha = 150
    else:
        palette = [(96, 112, 82), (104, 122, 96), (88, 106, 92)]
        alpha = 105
    names = list(ICONS)
    rng.shuffle(names)
    cols, rows = 8, 14
    cw, ch = W / cols, H / rows
    n = 0
    for j in range(rows):
        for i in range(cols):
            nm = names[n % len(names)]
            n += 1
            col = rng.choice(palette) + (alpha,)
            s = rng.uniform(1.55, 2.15)
            px = int(48 * s)
            ang = rng.uniform(-24, 24)
            ic = render_icon(ICONS[nm], px, ang, col)
            x = int(i * cw + cw / 2 + rng.uniform(-14, 14) - ic.width / 2)
            y = int(j * ch + ch / 2 + rng.uniform(-14, 14) - ic.height / 2)
            canvas.alpha_composite(ic, (x, y))
    return canvas.convert("RGB")


def main():
    import os
    W, H = 1080, 1935
    out = os.path.join(os.path.dirname(__file__), "..", "..",
                       "android-app", "app", "src", "main", "res", "drawable-nodpi")
    dark = compose(True, W, H, seed=7)
    dark.save(os.path.join(out, "chat_wallpaper_dark.jpg"), quality=92)
    light = compose(False, W, H, seed=11)
    light.save(os.path.join(out, "chat_wallpaper_light.jpg"), quality=92)
    print("icons:", len(ICONS))
    print("saved dark/light wallpapers", W, "x", H)

    # превью всех значков для проверки
    from PIL import Image
    per = 11
    names = list(ICONS)
    cols = 10
    rows = (len(names) + cols - 1) // cols
    cell = 96
    sheet = Image.new("RGB", (cols * cell, rows * cell), (18, 20, 18))
    for idx, nm in enumerate(names):
        ic = render_icon(ICONS[nm], 72, 0, (140, 200, 170, 255))
        x = (idx % cols) * cell + (cell - ic.width) // 2
        y = (idx // cols) * cell + (cell - ic.height) // 2
        sheet.paste(ic, (x, y), ic)
    sheet.save(os.path.join(os.path.dirname(__file__), "preview_icons.png"))
    print("preview saved")


if __name__ == "__main__":
    main()
