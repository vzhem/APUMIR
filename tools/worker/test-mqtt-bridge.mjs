// =============================================================================
// ТЕСТ MQTT-МОСТА (tools/worker/p2p_relay_worker.js) — без Cloudflare.
// =============================================================================
// Зачем: 2026-10-06 на телефоне владельца мост не отвечал ConnAck («Network
// timeout» каждые 5 с, затем «SESSION START FAILED: наш брокер не ответил
// ConnAck за 20 с»). В воркере нашлась настоящая ошибка разбора потока:
// length пакета MQTT — это varint (1..4 байта), а тело и позиция считались как
// «1 + len», то есть тело КАЖДОГО пакета теряло последний байт (а с 128 байт
// начинался ещё и сдвиг всего потока). Точный CONNECT ядра (с LastWill и пустым
// его payload) обрывался на этом байте: DataView бросал RangeError, обработчик
// молча снимал клиента и оставлял WebSocket открытым и тихим.
//
// Тест гоняет класс MqttBridge напрямую: точный CONNECT ядра, SUBSCRIBE,
// PUBLISH на 900 байт, разбиение по чанкам, битый кадр, MQTT v5, здоровье.
// Старый воркер (v11.74.152) на тесте «точный CONNECT ядра» отвечает
// CONNACK=false — это и есть регрессия.
//
// Запуск: node tools/worker/test-mqtt-bridge.mjs   (Node 18+)
// =============================================================================

import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

// Воркер — обычный модуль Cloudflare; чтобы импортировать его в Node,
// кладём копию рядом с расширением .mjs (у файла в репозитории .js, и без
// package.json Node счёл бы его CommonJS).
const source = readFileSync(new URL("./p2p_relay_worker.js", import.meta.url), "utf8");
const { writeFileSync, mkdtempSync } = await import("node:fs");
const { tmpdir } = await import("node:os");
const { join } = await import("node:path");
// Cloudflare-глобали, которых нет в Node: модуль трогает Cache API на верхнем
// уровне. Заглушка достаточна — тестируем MQTT-разбор, а не кэш лендингов.
globalThis.caches = globalThis.caches || {
  default: { match: async () => undefined, put: async () => {}, delete: async () => {} },
};
const scratch = mkdtempSync(join(tmpdir(), "apu-bridge-test-"));
const modulePath = join(scratch, "worker-under-test.mjs");
writeFileSync(modulePath, source, "utf8");
const { MqttBridge } = await import(modulePath);

// ── мини-MQTT: кодирование пакетов ──────────────────────────────────────────

function varint(n) {
  const out = [];
  do {
    let b = n % 128;
    n = Math.floor(n / 128);
    if (n > 0) b += 128;
    out.push(b);
  } while (n > 0);
  return out;
}

function utf8(value) {
  const bytes = [...Buffer.from(value, "utf8")];
  return [bytes.length >> 8, bytes.length & 255, ...bytes];
}

function packet(type, flags, body) {
  return new Uint8Array([(type << 4) | flags, ...varint(body.length), ...body]);
}

function connectPacket(level = 4, clientId = "p2pm_test_client") {
  const body = [
    ...utf8("MQTT"),
    level,
    0x02, // clean session
    0x00, 0x3c, // keepalive 60
    ...utf8(clientId),
  ];
  return packet(1, 0, body);
}

/** Ровно тот CONNECT, что отправляет ядро (mqtt_transport.rs): клиент
 *  p2pm_<16 hex>, clean session, keepalive 60 и LastWill на p2pm2/presence/<id>
 *  с ПУСТЫМ payload (именно этот байт и терялся в старом разборе). */
function coreConnectPacket(nodeId, level = 4) {
  const body = [
    ...utf8("MQTT"),
    level,
    0x02 | 0x04 | 0x20, // clean session + will + will retain
    0x00, 0x3c, // keepalive 60
    ...utf8("p2pm_" + nodeId.slice(0, 16)),
    ...utf8("p2pm2/presence/" + nodeId),
    0x00, 0x00, // пустой payload will
  ];
  return packet(1, 0, body);
}

function subscribePacket(pid, filter) {
  return packet(8, 2, [pid >> 8, pid & 255, ...utf8(filter), 0]);
}

function publishPacket(topic, payload, retain = false) {
  const body = [...utf8(topic), ...payload];
  return packet(3, retain ? 1 : 0, body);
}

/** Разбор PUBLISH из того, что мост реально отправил клиенту. */
function parsePublish(bytes) {
  assert.equal(bytes[0] >> 4, 3, "ожидали PUBLISH");
  let len = 0, mult = 1, i = 1, byte = 0, lengthBytes = 0;
  do {
    byte = bytes[i++]; lengthBytes += 1; len += (byte & 127) * mult; mult *= 128;
  } while (byte & 128);
  assert.equal(i + len, bytes.length, "длина PUBLISH должна сходиться");
  const topicLen = (bytes[i] << 8) | bytes[i + 1];
  const topic = Buffer.from(bytes.slice(i + 2, i + 2 + topicLen)).toString("utf8");
  const payload = bytes.slice(i + 2 + topicLen);
  return { topic, payload, retain: (bytes[0] & 1) === 1 };
}

// ── фальшивый клиент вместо WebSocket ───────────────────────────────────────

function fakeClient() {
  const sent = [];
  return {
    ws: {
      send(data) {
        sent.push(data instanceof Uint8Array ? Uint8Array.from(data) : new Uint8Array(data));
      },
      close(code, reason) {
        this.closeCode = code;
        this.closeReason = reason;
        this.closed = true;
      },
    },
    subs: [],
    will: null,
    buf: new Uint8Array(0),
    sent,
    // Мост зовёт client.send(bytes) (метод MqttClient), а не ws.send напрямую.
    send(bytes) {
      sent.push(bytes instanceof Uint8Array ? Uint8Array.from(bytes) : new Uint8Array(bytes));
    },
    wasClosed: false,
    close(code) {
      this.wasClosed = true;
      this.closeCode = code;
    },
  };
}

function attach(bridge, client) {
  bridge.clients.add(client);
  return client;
}

// ── тесты ───────────────────────────────────────────────────────────────────

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

test("CONNECT отвечает CONNACK, SUBSCRIBE — SUBACK", () => {
  const bridge = new MqttBridge();
  const client = attach(bridge, fakeClient());
  bridge.feed(client, connectPacket());
  assert.deepEqual([...client.sent[0]], [0x20, 0x02, 0x00, 0x00], "CONNACK ok");

  bridge.feed(client, subscribePacket(7, "p2pm2/#"));
  const suback = client.sent[1];
  // SUBACK: [0x90, длина, pid_hi, pid_lo, granted...] — id возвращается как был.
  assert.equal(suback[0], 0x90, "SUBACK");
  assert.equal((suback[2] << 8) | suback[3], 7, "packet id не изменён");
  assert.equal(suback[4], 0, "granted QoS 0");
  assert.deepEqual(client.subs, ["p2pm2/#"]);
});

test("PUBLISH на 900 байт доходит целиком (регрессия разбора varint)", () => {
  const bridge = new MqttBridge();
  const sender = attach(bridge, fakeClient());
  const receiver = attach(bridge, fakeClient());
  bridge.feed(sender, connectPacket());
  bridge.feed(receiver, connectPacket());
  bridge.feed(receiver, subscribePacket(1, "p2pm2/#"));

  const payload = Uint8Array.from({ length: 900 }, (_, i) => (i * 31) % 251);
  bridge.feed(sender, publishPacket("p2pm2/msg/pk_peer", payload));

  const got = receiver.sent.filter((b) => (b[0] >> 4) === 3).map(parsePublish);
  assert.equal(got.length, 1, "у получателя ровно один PUBLISH");
  assert.equal(got[0].topic, "p2pm2/msg/pk_peer");
  assert.equal(got[0].payload.length, payload.length, "длина payload сохранена");
  assert.deepEqual([...got[0].payload], [...payload], "payload не сдвинут и не обрезан");
});

test("PUBLISH QoS1 на 900 байт: PUBACK отправителю, payload целиком получателю", () => {
  // Ядро публикует именно QoS1 (AtLeastOnce): после длины и темы в теле идёт
  // packet id, поэтому важно, что разбор не сдвинулся.
  const bridge = new MqttBridge();
  const sender = attach(bridge, fakeClient());
  const receiver = attach(bridge, fakeClient());
  bridge.feed(sender, connectPacket());
  bridge.feed(receiver, connectPacket());
  bridge.feed(receiver, subscribePacket(1, "p2pm2/#"));

  const payload = Uint8Array.from({ length: 900 }, (_, i) => (i * 17) % 251);
  const body = [...utf8("p2pm2/msg/pk_peer"), 0x12, 0x34, ...payload];
  bridge.feed(sender, packet(3, 2, body)); // flags 0b0010 = QoS1

  const puback = sender.sent.find((b) => b[0] === 0x40);
  assert.ok(puback, "отправителю ушёл PUBACK");
  assert.deepEqual([...puback], [0x40, 0x02, 0x12, 0x34], "packet id в PUBACK тот же");

  const got = receiver.sent.filter((b) => (b[0] >> 4) === 3).map(parsePublish);
  assert.equal(got.length, 1);
  assert.equal(got[0].topic, "p2pm2/msg/pk_peer");
  assert.deepEqual([...got[0].payload], [...payload], "payload не сдвинут и не обрезан");
});

test("короткий пакет и два пакета в одном чанке", () => {
  const bridge = new MqttBridge();
  const client = attach(bridge, fakeClient());
  const both = new Uint8Array([...connectPacket(), ...subscribePacket(2, "p2pm2/presence/#")]);
  bridge.feed(client, both);
  assert.equal(client.sent.length, 2, "и CONNACK, и SUBACK обработаны");
});

test("пакет, разбитый на два чанка, собирается", () => {
  const bridge = new MqttBridge();
  const sender = attach(bridge, fakeClient());
  const receiver = attach(bridge, fakeClient());
  bridge.feed(sender, connectPacket());
  bridge.feed(receiver, connectPacket());
  bridge.feed(receiver, subscribePacket(1, "p2pm2/#"));

  const payload = Uint8Array.from({ length: 1400 }, (_, i) => i % 256);
  const bytes = publishPacket("p2pm2/msg/pk_peer", payload);
  bridge.feed(sender, bytes.slice(0, 100));
  assert.equal(receiver.sent.filter((b) => (b[0] >> 4) === 3).length, 0, "до конца пакета не публикуем");
  bridge.feed(sender, bytes.slice(100));
  const got = receiver.sent.filter((b) => (b[0] >> 4) === 3).map(parsePublish);
  assert.equal(got.length, 1);
  assert.deepEqual([...got[0].payload], [...payload]);
});

test("retained-сообщение приходит новому подписчику", () => {
  const bridge = new MqttBridge();
  const publisher = attach(bridge, fakeClient());
  bridge.feed(publisher, connectPacket());
  bridge.feed(publisher, publishPacket("p2pm2/presence/pk_peer", Uint8Array.from([1, 2, 3]), true));

  const late = attach(bridge, fakeClient());
  bridge.feed(late, connectPacket());
  bridge.feed(late, subscribePacket(3, "p2pm2/presence/#"));
  const retained = late.sent.filter((b) => (b[0] >> 4) === 3).map(parsePublish);
  assert.equal(retained.length, 1, "retained отдан при подписке");
  assert.deepEqual([...retained[0].payload], [1, 2, 3]);
});

test("MQTT 5.0 отклоняется понятным CONNACK, а не молчанием", () => {
  const bridge = new MqttBridge();
  const client = attach(bridge, fakeClient());
  bridge.feed(client, connectPacket(5, "p2pm_v5_client"));
  assert.deepEqual([...client.sent[0]], [0x20, 0x03, 0x00, 0x84, 0x00], "v5 CONNACK: unsupported version");
});

test("PINGREQ отвечает PINGRESP", () => {
  const bridge = new MqttBridge();
  const client = attach(bridge, fakeClient());
  bridge.feed(client, connectPacket());
  bridge.feed(client, packet(12, 0, []));
  assert.deepEqual([...client.sent[1]], [0xd0, 0x00]);
});

test("точный CONNECT ядра (с LastWill) получает CONNACK", () => {
  const bridge = new MqttBridge();
  const client = attach(bridge, fakeClient());
  const connect = coreConnectPacket("a".repeat(64));
  assert.equal(connect.length, 118, "CONNECT ядра — 118 байт (remaining length 116)");
  bridge.onMessage(client, connect);
  assert.deepEqual([...client.sent[0]], [0x20, 0x02, 0x00, 0x00], "CONNACK ok");
  assert.equal(client.will.topic, "p2pm2/presence/" + "a".repeat(64), "will разобран целиком");
  assert.equal(bridge.stats.connacks, 1);
});

test("битый кадр: клиент закрыт, причина в счётчиках (а не тишина)", () => {
  const bridge = new MqttBridge();
  const client = attach(bridge, fakeClient());
  // CONNECT с флагом will, но БЕЗ полей will: чтение за границей тела -> RangeError.
  const broken = packet(1, 0, [...utf8("MQTT"), 4, 0x04, 0x00, 0x3c, ...utf8("p2pm_broken")]);
  bridge.onMessage(client, broken);
  assert.equal(bridge.stats.parse_errors, 1, "ошибка разбора посчитана");
  assert.equal(client.ws.closed, true, "WebSocket закрыт, а не оставлен тихим");
  assert.equal(client.ws.closeCode, 1002, "код закрытия: protocol error");
  assert.match(bridge.stats.last_error, /frame parse error/);
  assert.equal(bridge.clients.has(client), false, "клиент снят с моста");
});

test("здоровье моста отвечает JSON без WebSocket", async () => {
  const bridge = new MqttBridge();
  const client = attach(bridge, fakeClient());
  bridge.onMessage(client, coreConnectPacket("b".repeat(64)));
  const response = await bridge.fetch(new Request("https://relay.example/mqtt/health"));
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.ok, true);
  assert.equal(body.clients, 1);
  assert.equal(body.broker, "apu-mqtt-bridge");
  assert.equal(body.bridge_version, 2, "версия моста видна с телефона");
  assert.equal(body.stats.connacks, 1, "счётчики в health");
});

test("закрытый will публикуется последним", () => {
  const bridge = new MqttBridge();
  const receiver = attach(bridge, fakeClient());
  bridge.feed(receiver, connectPacket());
  bridge.feed(receiver, subscribePacket(1, "p2pm2/presence/#"));

  const author = attach(bridge, fakeClient());
  author.will = { topic: "p2pm2/presence/pk_peer", payload: Uint8Array.from([7]), retain: true };
  bridge.drop(author);

  const got = receiver.sent.filter((b) => (b[0] >> 4) === 3).map(parsePublish);
  assert.equal(got.length, 1, "will доставлен подписчику");
  assert.equal(got[0].topic, "p2pm2/presence/pk_peer");
});

// ── прогон ──────────────────────────────────────────────────────────────────

let failed = 0;
for (const { name, fn } of tests) {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (error) {
    failed += 1;
    console.log(`FAIL ${name}\n     ${error.message}`);
  }
}
console.log(`\n${tests.length - failed}/${tests.length} тестов моста прошли`);
process.exit(failed === 0 ? 0 : 1);
