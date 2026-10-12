=== РУЧНОЙ ПЕРЕХОД ДЛЯ СЛЕДУЮЩЕГО АГЕНТА ===
Ветка: arena/7ab0e8d6-apumir (HEAD 3372467 — полный Kotlin stage 3/4 + исправление sha256Hex)
Зелёный релиз: v11.74.334 (9f313c9, CI 38198933743 success) — опубликован с APK
Проблема: Kotlin StoryPhotoParts/StoryStore вызывали failure в CI Build Release APK (5 попыток); изоляция (green-isolate 9f313c9) прошла.
Что исправить: упростить/отладить StoryPhotoParts/StoryStore или интегрировать Assembler отдельно; затем выпустить v11.74.335 с полным этапом 3/4.
Ограничения: нет обещаний ёмкости/NAT; RU/EN только; не переводить логические/БД значения.
