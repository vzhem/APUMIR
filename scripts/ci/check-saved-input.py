#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Контракт «Избранного»: панель ввода как в чате и не под клавиатурой.

Задача владельца от 2026-10-06: «В избранное нужно писать как в чате с кнопкой
отправить. Когда пишешь чтобы клавиатура не перекрывала».

Проверяется по исходникам (Android-экрана тестировать в песочнице нечем):

* панель ввода живёт в `bottomBar` экрана «Избранное» и состоит из поля и кнопки
  «Отправить» — как панель чата, а не как отдельная форма;
* у экрана и у панели есть `.imePadding()` — клавиатура поднимает панель, а не
  накрывает её (в чате этот приём уже проверен на телефоне);
* текст сохраняется тем же путём, что «+» → «Заметка» (`viewModel.addNote`),
  поэтому новых путей записи в избранное не появилось;
* панель разделов уходит, пока человек печатает, — иначе поле и панель вместе
  съедали бы пол-экрана над клавиатурой.

Запуск: `python3 scripts/ci/check-saved-input.py`; код возврата 0 — контракт цел.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCREEN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui/screens/saved/SavedScreen.kt"
VIEW_MODEL = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/ui/screens/saved/SavedViewModel.kt"


class SavedInputContractTest(unittest.TestCase):

    def test_screen_writes_notes_like_a_chat(self):
        text = SCREEN.read_text()
        # Панель ввода - в нижней панели экрана, рядом с панелью разделов.
        self.assertIn("SavedInputBar(", text)
        self.assertIn("bottomBar = {", text)
        self.assertIn("onSend = {", text)
        # Кнопка подписана «Отправить» - как в чате.
        self.assertIn('"Отправить"', text)
        self.assertIn("TextButton(", text)
        # Поле с подсказкой и пузырём APU, а не серый прямоугольник поверх обоев.
        self.assertIn("Заметка или сообщение себе...", text)
        self.assertIn("apuBubbleSurface(color = Color.White)", text)

    def test_keyboard_does_not_cover_the_input_bar(self):
        text = SCREEN.read_text()
        # Оба уровня, как в чате: экран сжимается над клавиатурой и панель тоже.
        self.assertIn("modifier = Modifier.imePadding()", text)
        self.assertGreaterEqual(text.count(".imePadding()"), 2)
        # Активность кнопки читается из состояния поля: загорается сразу.
        self.assertIn("inputState.text.isNotBlank()", text)

    def test_note_goes_through_the_existing_save_path(self):
        text = SCREEN.read_text()
        self.assertIn("viewModel.addNote(draft)", text)
        self.assertIn("draft = \"\"", text)
        vm = VIEW_MODEL.read_text()
        # Путь сохранения остался единственным: заметка идёт в репозиторий.
        self.assertIn("repository.saveText(text)", vm)

    def test_section_bar_steps_aside_while_typing(self):
        text = SCREEN.read_text()
        self.assertIn("inputFocused", text)
        self.assertRegex(text, re.compile(r"if \(!inputFocused\) bottomBar\(\)"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
