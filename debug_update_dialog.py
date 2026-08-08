# -*- coding: utf-8 -*-
import os

BASE = r"C:\APUMIR\p2p-messenger"
MAIN_FILE = os.path.join(BASE, "android-app", "app", "src", "main", "java",
                         "com", "vladimir", "messenger", "MainActivity.kt")

with open(MAIN_FILE, "r", encoding="utf-8") as f:
    content = f.read()

# Добавить логирование в UpdateDialog блок
old_block = '''                // Диалог обновления
                val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
                updateState.updateAvailable?.let { release ->
                    UpdateDialog('''

new_block = '''                // Диалог обновления
                val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
                Log.d("MainActivity", "UpdateDialog check: isChecking=${updateState.isChecking}, updateAvailable=${updateState.updateAvailable != null}, isDownloading=${updateState.isDownloading}")
                updateState.updateAvailable?.let { release ->
                    Log.i("MainActivity", "Rendering UpdateDialog for ${release.version}")
                    UpdateDialog('''

if old_block in content:
    content = content.replace(old_block, new_block)
    print("OK: added logging to UpdateDialog rendering")
else:
    print("WARNING: UpdateDialog block not found, trying alternative")
    # Попробуем другой паттерн
    if "updateState.updateAvailable" in content:
        print("INFO: updateState.updateAvailable exists but pattern doesn't match")

with open(MAIN_FILE, "w", encoding="utf-8") as f:
    f.write(content)

print("\nDone. Rebuild and test.")