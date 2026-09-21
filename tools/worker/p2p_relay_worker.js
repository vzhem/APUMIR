// =============================================================================
// p2p-relay — worker целиком. Заменить содержимое редактора Cloudflare этим.
// =============================================================================
// Что делает:
//   /register, /lookup   — реестр узлов (как было);
//   /version             — сведения об обновлении (как было);
//   /update/latest       — сведения о последнем релизе (worker сам ходит на GitHub);
//   /update/apk          — поток APK последнего релиза (белый список мобильных сетей);
//   /mqtt                — НАШ MQTT-брокер (Durable Object MQTT_BRIDGE, привязка
//                          в дашборде): единый рой для всех телефонов, без
//                          сторонних брокеров (v11.74.7);
//   /health              — проверка живости;
//   /vault/put, /vault/get — хранилище личности;
//   /addrbook/put, /addrbook/get — резервные копии азбуки адресов (зашифрованы
//                          на телефоне; v11.74.8);
//   /gif/search            — каталог GIF через Tenor, ключ в TENOR_KEY
//                          (v11.74.14); выбранная гифка едет через файловый рой;
//   /i?slug=...          — страница пересланной ссылки старого (длинного) вида:
//                          открыть в APU или установить его;
//   /s/<код>             — КОРОТКАЯ ссылка: та же страница, но адрес не выдаёт
//                          ни канал, ни владельца, ни запись (код непрозрачен);
//   POST /short          — приложение регистрирует короткую ссылку;
//   GET  /short/<код>    — приложение узнаёт, куда ведёт короткая ссылка;
//   /.well-known/assetlinks.json — подтверждение для Android, что ссылки
//                          этого хоста можно открывать сразу в APU (App Links).
//
// Про App Links: Android 12+ открывает https-ссылку в приложении БЕЗ вопроса
// только если хост опубликовал этот файл с отпечатком ключа подписи APK.
// Иначе ссылка уходит в браузер, и человеку нужно лишнее нажатие «Открыть в
// APU». Отпечаток ключа - публичная величина (он лежит в каждом APK), секрета
// здесь нет. Порядок важен: сначала опубликовать worker, потом ставить APK -
// Android проверяет файл в момент установки/обновления приложения.
//
// Про хранилище личности: сервер НЕ МОЖЕТ прочитать то, что хранит. Сундук
// запирается паролем на телефоне, сюда приходят непрозрачные байты в base64.
// Полка (shelf) — отпечаток никнейма, а не сам никнейм, поэтому по содержимому
// хранилища нельзя понять, кто в нём есть.
//
// Про короткие ссылки: пересланная ссылка на пост выглядела так:
//   https://<хост>/i?slug=...&g=<канал>&o=pk_<владелец>&p=<запись>&c=1
// и выдавала посторонним идентификаторы канала и владельца. Теперь приложение
// присылает сюда длинную ссылку (POST /short), получает код из 10 знаков, и
// наружу уходит только
//   https://<хост>/s/<код>
// Код считает сервер - это отпечаток (SHA-256) самой ссылки в алфавите без
// похожих знаков (0 O 1 I l): 10 знаков, 57^10 ≈ 3,6·10^17 сочетаний. Одна и
// та же ссылка всегда даёт один и тот же код, а подобрать код нельзя.
//
// Хранится в той же привязке REGISTRY под ключом short:<код>, без срока
// жизни: пересланная ссылка должна открываться и через год. Перед записью
// ключ НЕ читается нарочно: KV на минуту запоминает и ответ «ключа нет», и
// получатель по соседству увидел бы «такой ссылки нет». Запись идёт сразу;
// повторная запись той же ссылки кладёт то же значение. Бесплатный план KV
// даёт 1000 записей в сутки на всё вместе; при бурном росте пересылок
// понадобится платный план Workers.
//
// Путь /s/ нарочно другой, чем /i: старые версии APU перехватывают только /i,
// поэтому короткая ссылка у них откроется в браузере, а страница уже отдаст
// полную ссылку p2pmessenger://, которую старое приложение понимает.
//
// Требуется привязка KV с именем APU_VAULT (см. docs/IDENTITY_VAULT_SETUP.md).
// Существующая привязка REGISTRY используется как раньше.
// =============================================================================

// НАШ MQTT-БРОКЕР (задача владельца 2026-09-19, "полноценный
// маленький сервер"): все телефоны WebSocket'ом сходятся в один
// Durable Object (idFromName константа), поэтому он сам может
// раздавать публикации подписчикам - это и есть брокер.
// Реализован минимальный MQTT 3.1.1 для сигнального трафика
// роя: CONNECT/CONNACK, SUBSCRIBE/SUBACK, PUBLISH (QoS0 вер; QoS1
// входящие подтверждаем PUBACK), retain (нужен presence),
// LastWill, PINGREQ/PINGRESP, дискретные вайлдкарды + #.
// Старые сборки (v11.74.4/5) попадают сюда же - рой единый.

const MQTT_MAX_CLIENTS = 400;
const MQTT_MAX_PUBLISH_BYTES = 256 * 1024;

function encLen(n) {
  const out = [];
  do { let b = n % 128; n = Math.floor(n / 128); if (n > 0) b += 128; out.push(b); } while (n > 0);
  return out;
}

function buildPublish(topic, payload, retain) {
  const t = new TextEncoder().encode(topic);
  const body = new Uint8Array(2 + t.length + payload.length);
  body[0] = t.length >> 8; body[1] = t.length & 255;
  body.set(t, 2); body.set(payload, 2 + t.length);
  const head = [0x30 | (retain ? 1 : 0), ...encLen(body.length)];
  return new Uint8Array([...head, ...body]);
}

function topicMatch(filter, topic) {
  if (filter === topic) return true;
  if (filter === "#") return true;
  if (filter.endsWith("/#")) {
    const prefix = filter.slice(0, -2);
    return topic === prefix || topic.startsWith(prefix + "/");
  }
  const f = filter.split("/"); const t = topic.split("/");
  if (f.length !== t.length) return false;
  for (let i = 0; i < f.length; i++) {
    if (f[i] !== "+" && f[i] !== t[i]) return false;
  }
  return true;
}

class MqttClient {
  constructor(ws) {
    this.ws = ws;
    this.subs = [];       // фильтры подписок
    this.will = null;     // {topic, payload, retain}
    this.buf = new Uint8Array(0);
  }
  send(bytes) { try { this.ws.send(bytes); } catch (_) {} }
}

export class MqttBridge {
  constructor() {
    this.clients = new Set();
    this.retained = new Map(); // topic -> {topic, payload}
  }

  async fetch(request) {
    if (this.clients.size >= MQTT_MAX_CLIENTS) {
      return json({ error: "broker busy" }, 503);
    }
    const pair = new WebSocketPair();
    const server = pair[1];
    server.accept();
    server.binaryType = "arraybuffer";
    const client = new MqttClient(server);
    this.clients.add(client);
    server.addEventListener("message", (event) => {
      try {
        const data = event.data;
        const chunk = typeof data === "string"
          ? new TextEncoder().encode(data)
          : new Uint8Array(data);
        this.feed(client, chunk);
      } catch (_) { this.drop(client); }
    });
    server.addEventListener("close", () => this.drop(client));
    server.addEventListener("error", () => this.drop(client));
    // rumqttc требует эхо субпротокола mqtt.
    return new Response(null, {
      status: 101,
      webSocket: pair[0],
      headers: { "Sec-WebSocket-Protocol": "mqtt" },
    });
  }

  drop(client) {
    if (!this.clients.has(client)) return;
    this.clients.delete(client);
    if (client.will) {
      this.publish(null, client.will.topic, client.will.payload, client.will.retain);
      client.will = null;
    }
  }

  feed(client, chunk) {
    const buf = new Uint8Array(client.buf.length + chunk.length);
    buf.set(client.buf); buf.set(chunk, client.buf.length);
    let pos = 0;
    while (pos + 2 <= buf.length) {
      let len = 0, mult = 1, i = pos + 1, byte = 0;
      do {
        if (i >= buf.length) { client.buf = buf.slice(pos); return; }
        byte = buf[i++]; len += (byte & 127) * mult; mult *= 128;
        if (mult > 128 * 128 * 128 * 2) { this.drop(client); return; }
      } while (byte & 128);
      if (pos + 1 + len > buf.length) { client.buf = buf.slice(pos); return; }
      this.handle(client, buf[pos], buf.slice(i, pos + 1 + len));
      pos += 1 + len;
    }
    client.buf = buf.slice(pos);
  }

  handle(client, first, body) {
    const type = first >> 4;
    const view = new DataView(body.buffer, body.byteOffset, body.byteLength);
    if (type === 1) { // CONNECT
      let p = 0;
      const pnamelen = view.getUint16(p); p += 2 + pnamelen; // "MQTT"
      p += 1; // level
      const flags = body[p]; p += 1;
      p += 2; // keepalive
      const idlen = view.getUint16(p); p += 2 + idlen; // clientId
      if (flags & 4) { // will
        const wtopicLen = view.getUint16(p); const wtopic = new TextDecoder().decode(body.slice(p + 2, p + 2 + wtopicLen)); p += 2 + wtopicLen;
        const wpayLen = view.getUint16(p); const wpay = body.slice(p + 2, p + 2 + wpayLen); p += 2 + wpayLen;
        client.will = { topic: wtopic, payload: wpay, retain: (flags & 32) !== 0 };
      }
      client.send(new Uint8Array([0x20, 0x02, 0x00, 0x00])); // CONNACK ok
    } else if (type === 3) { // PUBLISH
      const qos = (first >> 1) & 3;
      const retain = (first & 1) !== 0;
      const tlen = view.getUint16(0);
      const topic = new TextDecoder().decode(body.slice(2, 2 + tlen));
      let p = 2 + tlen;
      if (qos > 0) {
        const pid = [body[p], body[p + 1]];
        p += 2;
        if (qos === 1) client.send(new Uint8Array([0x40, 0x02, pid[0], pid[1]]));
      }
      const payload = body.slice(p);
      if (payload.length > MQTT_MAX_PUBLISH_BYTES) return;
      this.publish(client, topic, payload, retain);
    } else if (type === 8) { // SUBSCRIBE
      const pid = [body[0], body[1]];
      let p = 2;
      const granted = [];
      while (p < body.length) {
        const flen = view.getUint16(p); p += 2;
        const filter = new TextDecoder().decode(body.slice(p, p + flen)); p += flen;
        p += 1; // requested qos
        client.subs.push(filter);
        granted.push(0);
        // Retained: всё совпавшее - сразу (retain бит стоит).
        for (const entry of this.retained.values()) {
          if (topicMatch(filter, entry.topic)) {
            client.send(buildPublish(entry.topic, entry.payload, true));
          }
        }
      }
      const out = [0x90, ...encLen(2 + granted.length), pid[0], pid[1], ...granted];
      client.send(new Uint8Array(out));
    } else if (type === 12) { // PINGREQ
      client.send(new Uint8Array([0xd0, 0x00]));
    } else if (type === 14) { // DISCONNECT
      client.will = null;
      try { client.ws.close(1000, "bye"); } catch (_) {}
      this.clients.delete(client);
    }
  }

  publish(from, topic, payload, retain) {
    if (retain) {
      if (payload.length === 0) this.retained.delete(topic);
      else this.retained.set(topic, { topic, payload });
    }
    if (this.retained.size > 512) {
      this.retained.delete(this.retained.keys().next().value);
    }
    for (const c of this.clients) {
      if (c === from) continue; // себе эхо не шлем
      for (const filter of c.subs) {
        if (topicMatch(filter, topic)) { c.send(buildPublish(topic, payload, false)); break; }
      }
    }
  }
}

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

/** Код короткой ссылки: приложение шлёт 10 знаков, допускаем с запасом. */
const SHORT_CODE_PATTERN = /^[A-Za-z0-9]{6,32}$/;
/** Длинная ссылка, которую прячем за кодом: длиннее не бывает. */
const MAX_SHORT_TARGET_CHARS = 1024;
/** Слаг приглашения - как в приложении (GroupInviteLinks.isValidSlug). */
const SLUG_PATTERN = /^[A-Za-z0-9]{8,32}$/;
/** Идентификаторы группы, владельца, записи: UUID или pk_<hex>. */
const ID_PATTERN = /^[A-Za-z0-9_-]{1,96}$/;
/** Ссылка на контакт: apu://a/<узел base64url>[/<никнейм>] (util/ApuLink.kt). */
const CONTACT_LINK_PATTERN = /^apu:\/\/a\/[A-Za-z0-9_-]{16,64}(\/[A-Za-z0-9_]{1,32})?$/;
const GROUP_LINK_PREFIX = "p2pmessenger://group?";

export default {
  async fetch(request, env) {
    // Предполётный запрос отвечаем первым: он приходит методом OPTIONS на
    // любой путь, включая /vault/*, и до разбора маршрутов доходить не должен.
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    // MQTT-мост: важен только заголовок Upgrade (rumqttc сам выбирает путь).
    // Обычные запросы (приглашения, ссылки, обновление) не задеваем.
    if ((request.headers.get("Upgrade") || "").toLowerCase() === "websocket") {
      if (!env.MQTT_BRIDGE) {
        return json({ error: "MQTT_BRIDGE binding is not configured" }, 501);
      }
      const stub = env.MQTT_BRIDGE.idFromName("mqtt-bridge");
      return env.MQTT_BRIDGE.get(stub).fetch(request);
    }

    try {
      if (path === "/i" && request.method === "GET") {
        return handleInviteLanding(url);
      } else if (path.startsWith("/s/") && request.method === "GET") {
        return await handleShortLanding(path.slice(3), env);
      } else if (path === "/short" && request.method === "POST") {
        return await handleShortCreate(request, env);
      } else if (path.startsWith("/short/") && request.method === "GET") {
        return await handleShortResolve(path.slice(7), env);
      } else if (path === "/.well-known/assetlinks.json" && request.method === "GET") {
        return handleAssetLinks();
      } else if (path === "/vault/put" && request.method === "POST") {
        return await handleVaultPut(request, env);
      } else if (path === "/gif/search" && request.method === "GET") {
        return await handleGifSearch(url, env);
      } else if (path === "/addrbook/put" && request.method === "POST") {
        return await handleAddrBookPut(request, env);
      } else if (path === "/addrbook/get" && request.method === "GET") {
        return await handleAddrBookGet(url, env);
      } else if (path === "/vault/get" && request.method === "GET") {
        return await handleVaultGet(url, env);
      } else if (path === "/register" && request.method === "POST") {
        return await handleRegister(request, env);
      } else if (path === "/lookup" && request.method === "GET") {
        return await handleLookup(url, env);
      } else if (path === "/version" && request.method === "GET") {
        return await handleVersion(env);
      } else if (path === "/update/latest" && request.method === "GET") {
        return await handleUpdateLatest(request);
      } else if (path === "/update/apk" && request.method === "GET") {
        return await handleUpdateApk();
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

// ── Каталог GIF (v11.74.14): поиск через Tenor, ключ СПРЯТАН на сервере ──
// Телефон спрашивает наш /gif/search, сервер ходит к Tenor со своим ключом
// (binding TENOR_KEY; не задан - честно отвечаем «не настроен»). Выбранная
// гифка скачивается телефоном с CDN Tenor и дальше едёт через НАШ файловый
// рой, зашифрованная: каталог - единственная внешняя точка.
async function handleGifSearch(url, env) {
  try {
    const key = (env && env.TENOR_KEY) || "";
    if (!key) {
      return json({ error: "Каталог GIF не настроен на сервере (нет ключа Tenor)" }, 503);
    }
    const q = (url.searchParams.get("q") || "").trim().slice(0, 64);
    const pos = (url.searchParams.get("pos") || "").slice(0, 64);
    const params = new URLSearchParams({
      key: key,
      limit: "24",
      client_key: "apu_app",
      media_filter: "tinygif,gif",
    });
    let endpoint = "featured";
    if (q) {
      endpoint = "search";
      params.set("q", q);
    } else {
      params.set("random", "false");
    }
    if (pos) params.set("pos", pos);
    const resp = await fetch(
      "https://tenor.googleapis.com/v2/" + endpoint + "?" + params.toString()
    );
    if (!resp.ok) {
      return json({ error: "Каталог GIF ответил ошибкой (" + resp.status + ")" }, 502);
    }
    const data = await resp.json();
    const results = (data.results || [])
      .map((r) => {
        const f = r.media_formats || {};
        return {
          id: String(r.id || ""),
          preview: String(f.tinygif && f.tinygif.url ? f.tinygif.url : ""),
          gif: String(
            f.gif && f.gif.url ? f.gif.url : (f.tinygif && f.tinygif.url) || ""
          ),
        };
      })
      .filter((x) => x.id && x.preview && x.gif);
    return json({ results: results, next: String(data.next || "") }, 200);
  } catch (e) {
    return json({ error: "gif: " + (e && e.message ? e.message : String(e)) }, 502);
  }
}

// ── Резервные копии азбуки адресов (v11.74.8) ────────────────────────────
// Телефон сам (раз в сутки и по кнопке) кладёт ЗАШИФРОВАННУЮ азбуку:
// ключ шифрования выведен из приватного ключа узла, сервер видит только
// непрозрачные байты. Полка = "addrbook|<node_id>".
const MAX_ADDRBOOK_CHARS = 200000;

async function handleAddrBookPut(request, env) {
  try {
    const body = await request.json();
    const shelf = String(body.shelf || "");
    const book = String(body.book || "");
    if (!shelf.startsWith("addrbook|") || shelf.length > 160) {
      return json({ error: "bad shelf" }, 400);
    }
    if (!book || book.length > MAX_ADDRBOOK_CHARS) {
      return json({ error: "bad book" }, 400);
    }
    await env.APU_VAULT.put(shelf, book);
    return json({ success: true }, 200);
  } catch (e) {
    return json({ error: "addrbook put: " + (e && e.message ? e.message : String(e)) }, 502);
  }
}

async function handleAddrBookGet(url, env) {
  try {
    const shelf = url.searchParams.get("shelf") || "";
    if (!shelf.startsWith("addrbook|") || shelf.length > 160) {
      return json({ error: "bad shelf" }, 400);
    }
    const book = await env.APU_VAULT.get(shelf);
    if (!book) return json({ error: "not found" }, 404);
    return json({ book }, 200);
  } catch (e) {
    return json({ error: "addrbook get: " + (e && e.message ? e.message : String(e)) }, 502);
  }
}

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
 * Открывает ДЛИННУЮ ссылку на канал или пост (старый вид, /i?slug=...).
 *
 * Если APU установлен, Android перехватит этот адрес и до сюда дело не дойдёт -
 * откроется приложение сразу на нужной записи. Сюда попадают те, у кого APU
 * ещё нет: показываем кнопку установки, а под ней ту же ссылку, чтобы после
 * установки открыть её повторно и попасть в канал.
 */
function handleInviteLanding(url) {
  const slug = url.searchParams.get("slug") || "";
  if (!/^[A-Za-z0-9_-]{8,32}$/.test(slug)) {
    return plainText("Ссылка неполная", 400);
  }
  // Ссылка сохраняется целиком: в ней параметры канала и поста. Параметры
  // приходят снаружи, поэтому в страницу они попадают только экранированными.
  return landingPage(GROUP_LINK_PREFIX + url.searchParams.toString());
}

/**
 * Открывает КОРОТКУЮ ссылку /s/<код>: достаёт спрятанную за кодом ссылку и
 * показывает ту же страницу, что и для длинной. Сам адрес страницы ничего не
 * выдаёт - идентификаторы есть только внутри страницы, в кнопке «Открыть».
 */
async function handleShortLanding(code, env) {
  if (!SHORT_CODE_PATTERN.test(code)) {
    return plainText("Ссылка неполная", 400);
  }
  const target = await env.REGISTRY.get("short:" + code);
  if (!target) {
    return plainText("Такой ссылки нет. Попросите отправителя прислать её заново.", 404);
  }
  return landingPage(target);
}

/** Слова на странице зависят от того, куда ведёт ссылка. */
function describeTarget(deepLink) {
  if (deepLink.startsWith(GROUP_LINK_PREFIX)) {
    const params = new URLSearchParams(deepLink.slice(GROUP_LINK_PREFIX.length));
    if (params.get("p")) {
      return { title: "Запись в APU", hint: "Чтобы открыть её, нужен мессенджер APU." };
    }
    if (params.get("c") === "1") {
      return { title: "Канал в APU", hint: "Чтобы подписаться, нужен мессенджер APU." };
    }
    return { title: "Группа в APU", hint: "Чтобы войти, нужен мессенджер APU." };
  }
  return { title: "Контакт в APU", hint: "Чтобы добавить контакт, нужен мессенджер APU." };
}

/**
 * Страница «открыть в APU или установить». Длинная ссылка едет только в
 * кнопке и в скрипте перехода - на виду её больше нет, чтобы открывший
 * страницу в браузере не видел идентификаторов канала и владельца.
 */
function landingPage(deepLink) {
  const words = describeTarget(deepLink);
  const safeLink = escapeHtml(deepLink);
  const page = `<!doctype html>
<html lang="ru"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>Открыть в APU</title>
<style>
 body{font-family:system-ui,sans-serif;margin:0;padding:32px 20px;
      background:#eef3ea;color:#1E2430;text-align:center}
 h1{font-size:22px;margin:0 0 8px}
 p{color:#5A6472;line-height:1.5}
 a.btn{display:block;margin:18px auto;max-width:320px;padding:14px 20px;
       border-radius:14px;background:#8a6d1f;color:#fff;text-decoration:none;
       font-weight:600}
</style></head><body>
<h1>${escapeHtml(words.title)}</h1>
<p>${escapeHtml(words.hint)}</p>
<a class="btn" href="${safeLink}">Открыть в APU</a>
<a class="btn" href="https://github.com/vzhem/APUMIR/releases/latest/download/app-release.apk">Установить APU</a>
<p>После установки вернитесь сюда и нажмите «Открыть в APU».</p>
<script>
 // Если приложение уже стоит, уводим сразу - без лишнего нажатия.
 setTimeout(function(){ location.href = ${JSON.stringify(deepLink)}; }, 400);
</script>
</body></html>`;
  return new Response(page, {
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Robots-Tag": "noindex",
    },
  });
}

function plainText(text, status) {
  return new Response(text, {
    status,
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  });
}

// ---- короткие ссылки ----------------------------------------------------------

/** Алфавит кода - как у слагов приглашений в приложении: без 0, O, 1, I, l. */
const SHORT_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
const SHORT_CODE_LENGTH = 10;
/** Параметры ссылки на контакт старого вида p2pmessenger://add?... */
const CONTACT_QUERY_KEYS = new Set(["node_id", "name", "u", "r", "public_key"]);
const CONTACT_QUERY_VALUE = /^[A-Za-z0-9_.%+~-]{1,700}$/;

/**
 * Проверка длинной ссылки, которую прячем за кодом. Принимаем только то, что
 * строит само приложение: приглашение в группу, канал или к записи и ссылку
 * на контакт. Всё остальное (чужие адреса, http, лишние параметры) - отказ:
 * сервис не должен становиться перенаправлялкой куда попало.
 */
function normalizeShortTarget(raw) {
  const target = String(raw || "").trim();
  if (!target || target.length > MAX_SHORT_TARGET_CHARS) return null;

  if (target.startsWith(GROUP_LINK_PREFIX)) {
    const query = target.slice(GROUP_LINK_PREFIX.length);
    if (!/^[A-Za-z0-9_=&-]+$/.test(query)) return null;
    const seen = new Set();
    for (const [key, value] of new URLSearchParams(query)) {
      if (seen.has(key)) return null;
      seen.add(key);
      if (key === "slug") {
        if (!SLUG_PATTERN.test(value)) return null;
      } else if (key === "g" || key === "o" || key === "p") {
        if (!ID_PATTERN.test(value)) return null;
      } else if (key === "c" || key === "a") {
        if (value !== "1") return null;
      } else {
        return null;
      }
    }
    return seen.has("slug") ? target : null;
  }

  if (CONTACT_LINK_PATTERN.test(target)) return target;

  if (target.startsWith("p2pmessenger://add?")) {
    const query = target.slice("p2pmessenger://add?".length);
    let hasNode = false;
    for (const pair of query.split("&")) {
      const eq = pair.indexOf("=");
      if (eq <= 0) return null;
      const key = pair.slice(0, eq);
      const value = pair.slice(eq + 1);
      if (!CONTACT_QUERY_KEYS.has(key) || !CONTACT_QUERY_VALUE.test(value)) return null;
      if (key === "node_id") hasNode = true;
    }
    return hasNode ? target : null;
  }

  return null;
}

/** Код - начало SHA-256 самой ссылки в алфавите без похожих знаков. */
async function shortCodeFor(target, length) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(target));
  const bytes = new Uint8Array(digest);
  let code = "";
  for (let i = 0; i < length && i < bytes.length; i++) {
    code += SHORT_ALPHABET[bytes[i] % SHORT_ALPHABET.length];
  }
  return code;
}

/**
 * POST /short  {"target": "<длинная ссылка>"}  ->  {"code": "...", "url": "..."}
 *
 * Одна и та же ссылка всегда получает один и тот же код. Пишем сразу, без
 * предварительного чтения (см. шапку файла про кэш «ключа нет»).
 */
async function handleShortCreate(request, env) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ error: "bad json" }, 400);
  }
  const target = normalizeShortTarget(body.target);
  if (!target) {
    return json({ error: "bad target" }, 400);
  }
  const code = await shortCodeFor(target, SHORT_CODE_LENGTH);
  const key = "short:" + code;
  try {
    await env.REGISTRY.put(key, target);
  } catch (e) {
    // KV пускает одну запись в секунду на ключ: если ту же ссылку в этот миг
    // записал другой телефон, значение уже на месте - проверяем и отдаём код.
    const existing = await env.REGISTRY.get(key);
    if (existing !== target) {
      return json({ error: "busy" }, 503);
    }
  }
  const host = new URL(request.url).host;
  return json({ code, url: "https://" + host + "/s/" + code });
}

/** GET /short/<код>  ->  {"code": "...", "target": "<длинная ссылка>"} либо 404. */
async function handleShortResolve(code, env) {
  if (!SHORT_CODE_PATTERN.test(code)) {
    return json({ error: "bad code" }, 400);
  }
  const target = await env.REGISTRY.get("short:" + code);
  if (!target) {
    return json({ error: "not found" }, 404);
  }
  return json({ code, target });
}

/** Спецсимволы HTML - в сущности, чтобы чужой параметр не стал разметкой. */
function escapeHtml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

// ---- App Links ---------------------------------------------------------------

/**
 * SHA-256 сертификатов, которыми подписан релизный APK. Это открытые величины:
 * тот же отпечаток Android показывает в сведениях о приложении, и он есть в
 * каждом установленном APK.
 *
 * 1) Прежний ключ: android-app/app/p2p-release.jks, alias `p2p`.
 *    Проверить: keytool -list -v -keystore android-app\app\p2p-release.jks -alias p2p
 * 2) После смены ключа (docs/SIGNING_KEY_ROTATION.md) сюда ДОБАВЛЯЕТСЯ
 *    отпечаток нового ключа вторым элементом; старый остаётся - телефоны с
 *    обновлённым приложением помнят оба.
 */
const RELEASE_CERT_SHA256S = [
  "F8:43:CB:E7:03:32:BA:B6:7A:96:71:EB:DE:32:FE:E5:41:E8:4C:D9:04:D3:A5:08:E5:62:63:46:A1:A4:A5:F7",
];

/**
 * Ответ на проверку Android при установке APU: «ссылки этого хоста можно
 * отдавать com.vladimir.messenger без вопросов». Без этого файла Android 12+
 * открывает нашу https-ссылку в браузере, а не в приложении.
 */
function handleAssetLinks() {
  const statements = [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: "com.vladimir.messenger",
        sha256_cert_fingerprints: RELEASE_CERT_SHA256S,
      },
    },
  ];
  return new Response(JSON.stringify(statements), {
    headers: {
      // Ровно application/json: с другим типом Android файл не принимает.
      "Content-Type": "application/json",
      "Cache-Control": "public, max-age=3600",
    },
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

// ---- обновление приложения (белый список мобильных сетей) -------------------
//
// На «жёстком» мобильном интернете сеть пускает только хосты из белого
// списка: наш домен там есть (приглашения и короткие ссылки живут здесь),
// а GitHub - нет. Телефон спрашивает обновление здесь, а worker сам ходит
// на GitHub (у Cloudflare своих ограничений нет) и отдаёт сведения и APK
// потоком СО СВОЕГО домена. Открытого прокси нет: репозиторий и имя файла
// зашиты намертво, через worker нельзя скачать ничего постороннего.

const RELEASE_REPO = "vzhem/APUMIR";
const RELEASE_ASSET = "app-release.apk";

async function handleUpdateLatest(request) {
  let upstream;
  try {
    upstream = await fetch("https://api.github.com/repos/" + RELEASE_REPO + "/releases/latest", {
      headers: { "Accept": "application/vnd.github.v3+json", "User-Agent": "APU-Relay-Worker" },
    });
  } catch (e) {
    return json({ error: "github unreachable: " + e.message }, 502);
  }
  if (!upstream.ok) {
    return json({ error: "github " + upstream.status }, 502);
  }
  const data = await upstream.json();
  const payload = {
    tag_name: typeof data.tag_name === "string" ? data.tag_name : "",
    notes: typeof data.body === "string" ? data.body.slice(0, 4096) : "",
    published_at: typeof data.published_at === "string" ? data.published_at : "",
    // APK телефон тоже берёт здесь же: /update/apk отдаёт файл последнего
    // релиза с этого домена (в жёсткой сети другой путь всё равно не пройдёт).
    apk_url: new URL("/update/apk", request.url).toString(),
  };
  return new Response(JSON.stringify(payload), {
    status: 200,
    headers: { ...CORS_HEADERS, "Cache-Control": "public, max-age=300" },
  });
}

async function handleUpdateApk() {
  let upstream;
  try {
    upstream = await fetch(
      "https://github.com/" + RELEASE_REPO + "/releases/latest/download/" + RELEASE_ASSET,
      { redirect: "follow", headers: { "User-Agent": "APU-Relay-Worker" } }
    );
  } catch (e) {
    return json({ error: "github unreachable: " + e.message }, 502);
  }
  if (!upstream.ok || !upstream.body) {
    return json({ error: "github " + upstream.status }, 502);
  }
  const headers = new Headers();
  headers.set("Content-Type", "application/vnd.android.package-archive");
  headers.set("Content-Disposition", 'attachment; filename="' + RELEASE_ASSET + '"');
  const length = upstream.headers.get("content-length");
  if (length) headers.set("Content-Length", length);
  headers.set("Cache-Control", "no-store");
  return new Response(upstream.body, { status: 200, headers: headers });
}

// ---- общее ------------------------------------------------------------------

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), { status, headers: CORS_HEADERS });
}
