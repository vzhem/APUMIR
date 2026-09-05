// =============================================================================
// ХРАНИЛИЩЕ ЛИЧНОСТИ — два обработчика для worker'а p2p-relay
// =============================================================================
// Это ХРАНИЛИЩЕ «ключ → значение» и ничего больше. Сервер не может прочитать
// то, что хранит: сундук запирается паролем прямо на телефоне, сюда приходят
// непрозрачные байты в base64.
//
// Что нужно от вас:
//   1. в Cloudflare создать хранилище KV и привязать его к worker'у под
//      именем APU_VAULT (инструкция — в docs/IDENTITY_VAULT_SETUP.md);
//   2. вставить эти две ветки в свой обработчик fetch.
//
// Полка (shelf) — отпечаток никнейма, а не сам никнейм: по содержимому
// хранилища нельзя понять, кто в нём есть.
// =============================================================================

/** Ограничения: полка — 64 hex-знака, сундук — не больше 8 КБ в base64. */
const SHELF_PATTERN = /^[0-9a-f]{64}$/;
const MAX_VAULT_CHARS = 8192;

/**
 * Вставьте эти две ветки в свой обработчик запросов.
 *
 * @param {Request} request
 * @param {{ APU_VAULT: KVNamespace }} env
 * @returns {Promise<Response|null>} ответ или null, если путь не наш
 */
export async function handleVaultRoutes(request, env) {
  const url = new URL(request.url);

  // ---- сохранить сундук -----------------------------------------------
  if (url.pathname === '/vault/put' && request.method === 'POST') {
    let body;
    try {
      body = await request.json();
    } catch (_) {
      return json({ error: 'bad json' }, 400);
    }

    const shelf = String(body.shelf || '');
    const vault = String(body.vault || '');

    if (!SHELF_PATTERN.test(shelf)) {
      return json({ error: 'bad shelf' }, 400);
    }
    if (!vault || vault.length > MAX_VAULT_CHARS) {
      return json({ error: 'bad vault' }, 400);
    }

    // Перезапись — штатный случай: человек сменил пароль, и на полку ложится
    // тот же ключ под новым замком.
    await env.APU_VAULT.put(shelf, vault);
    return json({ success: true });
  }

  // ---- забрать сундук --------------------------------------------------
  if (url.pathname === '/vault/get' && request.method === 'GET') {
    const shelf = url.searchParams.get('shelf') || '';
    if (!SHELF_PATTERN.test(shelf)) {
      return json({ error: 'bad shelf' }, 400);
    }

    const vault = await env.APU_VAULT.get(shelf);
    if (!vault) {
      // Пустая полка и неверный пароль для звонящего выглядят одинаково —
      // так по ответу нельзя перебором узнать, какие никнеймы существуют.
      return json({ error: 'not found' }, 404);
    }
    return json({ vault });
  }

  return null; // не наш путь — пусть обрабатывает остальной код
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

// =============================================================================
// ЕСЛИ У ВАС ЕЩЁ НЕТ WORKER'А ИЛИ ХОЧЕТСЯ ЦЕЛИКОМ
// =============================================================================
// Полный рабочий файл — просто замените им содержимое редактора Cloudflare.
// Существующие пути /register, /lookup и /version сохранены.
//
// export default {
//   async fetch(request, env) {
//     const vaultResponse = await handleVaultRoutes(request, env);
//     if (vaultResponse) return vaultResponse;
//
//     // ... здесь остаётся ваш прежний код: /register, /lookup, /version ...
//
//     return new Response('Not found', { status: 404 });
//   },
// };
