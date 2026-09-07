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
      if (path === "/i" && request.method === "GET") {
        return handleInviteLanding(url);
      } else if (path === "/vault/put" && request.method === "POST") {
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

// ---- страница приглашения ---------------------------------------------------

/**
 * Открывает ссылку на канал или пост.
 *
 * Если APU установлен, Android перехватит этот адрес и до сюда дело не дойдёт -
 * откроется приложение сразу на нужной записи. Сюда попадают те, у кого APU
 * ещё нет: показываем кнопку установки, а под ней ту же ссылку, чтобы после
 * установки открыть её повторно и попасть в канал.
 */
function handleInviteLanding(url) {
  const slug = url.searchParams.get("slug") || "";
  if (!/^[A-Za-z0-9_-]{8,32}$/.test(slug)) {
    return new Response("Ссылка неполная", {
      status: 400,
      headers: { "Content-Type": "text/plain; charset=utf-8" },
    });
  }
  // Ссылка сохраняется целиком: в ней параметры канала и поста.
  const deepLink = "p2pmessenger://group?" + url.searchParams.toString();
  const page = `<!doctype html>
<html lang="ru"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Открыть в APU</title>
<style>
 body{font-family:system-ui,sans-serif;margin:0;padding:32px 20px;
      background:#eef3ea;color:#1E2430;text-align:center}
 h1{font-size:22px;margin:0 0 8px}
 p{color:#5A6472;line-height:1.5}
 a.btn{display:block;margin:18px auto;max-width:320px;padding:14px 20px;
       border-radius:14px;background:#8a6d1f;color:#fff;text-decoration:none;
       font-weight:600}
 a.alt{color:#8a6d1f}
 code{word-break:break-all;font-size:12px;color:#5A6472}
</style></head><body>
<h1>Запись в APU</h1>
<p>Чтобы открыть её, нужен мессенджер APU.</p>
<a class="btn" href="${deepLink}">Открыть в APU</a>
<a class="btn" href="https://github.com/vzhem/APUMIR/releases/latest/download/app-release.apk">Установить APU</a>
<p>После установки вернитесь сюда и нажмите «Открыть в APU».</p>
<p><code>${deepLink}</code></p>
<script>
 // Если приложение уже стоит, уводим сразу - без лишнего нажатия.
 setTimeout(function(){ location.href = ${JSON.stringify(deepLink)}; }, 400);
</script>
</body></html>`;
  return new Response(page, {
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
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
