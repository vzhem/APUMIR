// =============================================================================
// p2p-relay — worker целиком. Заменить содержимое редактора Cloudflare этим.
// =============================================================================
// Что делает:
//   /register, /lookup   — реестр узлов (как было);
//   /version             — сведения об обновлении (как было);
//   /health              — проверка живости;
//   /vault/put, /vault/get — хранилище личности (новое).
//
// Про хранилище личности: сервер НЕ МОЖЕТ прочитать то, что хранит. Сундук
// запирается паролем на телефоне, сюда приходят непрозрачные байты в base64.
// Полка (shelf) — отпечаток никнейма, а не сам никнейм, поэтому по содержимому
// хранилища нельзя понять, кто в нём есть.
//
// Требуется привязка KV с именем APU_VAULT (см. docs/IDENTITY_VAULT_SETUP.md).
// Существующая привязка REGISTRY используется как раньше.
// =============================================================================

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Content-Type": "application/json",
};

/** Полка — ровно 64 шестнадцатеричных знака (отпечаток никнейма). */
const SHELF_PATTERN = /^[0-9a-f]{64}$/;
/** Сундук с запасом: реальный занимает около килобайта. */
const MAX_VAULT_CHARS = 8192;

export default {
  async fetch(request, env) {
    // Предполётный запрос отвечаем первым: он приходит методом OPTIONS на
    // любой путь, включая /vault/*, и до разбора маршрутов доходить не должен.
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    try {
      if (path === "/vault/put" && request.method === "POST") {
        return await handleVaultPut(request, env);
      } else if (path === "/vault/get" && request.method === "GET") {
        return await handleVaultGet(url, env);
      } else if (path === "/register" && request.method === "POST") {
        return await handleRegister(request, env);
      } else if (path === "/lookup" && request.method === "GET") {
        return await handleLookup(url, env);
      } else if (path === "/version" && request.method === "GET") {
        return await handleVersion(env);
      } else if (path === "/health") {
        return json({ status: "ok" });
      } else {
        return json({ error: "not found", path }, 404);
      }
    } catch (e) {
      return json({ error: e.message }, 500);
    }
  },
};

// ---- хранилище личности -----------------------------------------------------

async function handleVaultPut(request, env) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ error: "bad json" }, 400);
  }

  const shelf = String(body.shelf || "");
  const vault = String(body.vault || "");

  if (!SHELF_PATTERN.test(shelf)) {
    return json({ error: "bad shelf" }, 400);
  }
  if (!vault || vault.length > MAX_VAULT_CHARS) {
    return json({ error: "bad vault" }, 400);
  }

  // Перезапись — штатный случай: человек сменил пароль, и на полку ложится
  // тот же ключ под новым замком. Срок жизни НЕ ставим: личность не должна
  // протухать, пока человек ею не пользуется.
  await env.APU_VAULT.put(shelf, vault);
  return json({ success: true });
}

async function handleVaultGet(url, env) {
  const shelf = url.searchParams.get("shelf") || "";
  if (!SHELF_PATTERN.test(shelf)) {
    return json({ error: "bad shelf" }, 400);
  }

  const vault = await env.APU_VAULT.get(shelf);
  if (!vault) {
    // Пустая полка и неверный пароль для звонящего выглядят одинаково: так
    // перебором нельзя узнать, какие никнеймы заняты.
    return json({ error: "not found" }, 404);
  }
  return json({ vault });
}

// ---- реестр узлов -----------------------------------------------------------

async function handleRegister(request, env) {
  const body = await request.json();
  const { node_id, public_key, display_name } = body;
  if (!node_id || !public_key) {
    return json({ error: "node_id and public_key required" }, 400);
  }
  const key = "registry:" + node_id;
  const value = JSON.stringify({
    node_id,
    public_key,
    display_name: display_name || "Unknown",
    registered_at: Date.now(),
  });
  await env.REGISTRY.put(key, value, { expirationTtl: 2592000 });
  return json({ success: true, node_id });
}

async function handleLookup(url, env) {
  const node_id = url.searchParams.get("node_id");
  if (!node_id) {
    return json({ error: "node_id required" }, 400);
  }
  const value = await env.REGISTRY.get("registry:" + node_id);
  if (!value) {
    return json({ error: "not found", node_id }, 404);
  }
  return new Response(value, { headers: CORS_HEADERS });
}

async function handleVersion(env) {
  const version = (await env.REGISTRY.get("meta:latest_version")) || "v11.60.0";
  return json({
    version,
    min_version: "v11.60.0",
    // Ссылка на настоящий репозиторий: раньше здесь стояла заготовка
    // "your-username/p2p-messenger", и обновление по ней не скачалось бы.
    update_url:
      "https://github.com/vzhem/APUMIR/releases/download/" + version + "/app-release.apk",
  });
}

// ---- общее ------------------------------------------------------------------

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), { status, headers: CORS_HEADERS });
}
