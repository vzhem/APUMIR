// Actual Worker + SQLite Durable Objects in workerd/Miniflare. All provider
// endpoints and envelopes are fake: this does not test Android or live FCM.
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, randomBytes } from "node:crypto";
import { EventEmitter, once } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Miniflare } from "miniflare";
import { routeFor, shardFor } from "./protocol.mjs";
import { access, fakeEnvelope, identity, randomHex, registration } from "./mailbox-test-fixture.mjs";

const rsa = generateKeyPairSync("rsa", { modulusLength: 2048 });
const bindings = {
  FCM_PROJECT_ID: "test-apu-project", FCM_CLIENT_EMAIL: "test-service@test-apu-project.invalid",
  FCM_PRIVATE_KEY: rsa.privateKey.export({ type: "pkcs8", format: "pem" }).toString(),
  PUSH_RATE_KEY: randomBytes(32).toString("hex"), PUSH_ENABLED: "true", MAILBOX_ENABLED: "true",
};
const events = new EventEmitter();
const fcmCalls = [];
let worker;

function options(overrides = {}) {
  return {
    modules: true, scriptPath: new URL("./worker.mjs", import.meta.url).pathname,
    compatibilityDate: "2025-07-18", durableObjects: { PUSH_SHARDS: { className: "PushShard", useSQLite: true } },
    bindings: { ...bindings },
    outboundService: async (request) => {
      if (request.url === "https://oauth2.googleapis.com/token") {
        return Response.json({ access_token: "temporary-access-token", expires_in: 3600 });
      }
      if (request.url === "https://fcm.googleapis.com/v1/projects/test-apu-project/messages:send") {
        fcmCalls.push(await request.json());
        events.emit("fcm");
        return Response.json({ name: "projects/test-apu-project/messages/test" });
      }
      throw new Error("Unexpected outbound host");
    },
    ...overrides,
  };
}

function post(instance, path, body) {
  return instance.dispatchFetch("https://apu.test" + path, {
    method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.77" },
    body: JSON.stringify(body),
  });
}
function requestFor(keys, reg, action, changes = {}) {
  return access(keys, action, { capability: reg.capability, device: reg.device, ...changes });
}
async function waitForFcm(tokens) {
  const deadline = Date.now() + 20_000;
  const selected = () => fcmCalls.filter((entry) => tokens.includes(entry.message.token));
  while (selected().length < tokens.length) {
    await once(events, "fcm", { signal: AbortSignal.timeout(Math.max(1, deadline - Date.now())) });
  }
  return selected();
}

before(async () => { worker = new Miniflare(options()); await worker.ready; });
after(async () => { if (worker) await worker.dispose(); });

test("signed device pull/ack is isolated per target and FCM contains no ciphertext or capability", async () => {
  const receiver = identity(), attacker = identity();
  const regA = await registration(receiver), regB = await registration(receiver, { capability: regA.capability });
  assert.equal((await post(worker, "/v1/registration", regA)).status, 204);
  assert.equal((await post(worker, "/v1/registration", regB)).status, 204);
  const body = { owner: receiver.owner, capability: regA.capability, id: randomHex(16), envelope: fakeEnvelope() };
  const response = await post(worker, "/v1/mailbox/put", body);
  assert.equal(response.status, 201);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const stored = await response.json();
  assert.equal(stored.state, "stored");
  assert.equal(stored.wakeQueued, true);
  assert.ok(stored.expiresAt > Date.now());
  const forged = requestFor(attacker, regA, "pull", { owner: receiver.owner });
  assert.equal((await post(worker, "/v1/mailbox/pull", forged)).status, 403);
  const wrongDevice = requestFor(receiver, regA, "pull", { device: randomHex(16) });
  assert.equal((await post(worker, "/v1/mailbox/pull", wrongDevice)).status, 403);
  const pullA = await post(worker, "/v1/mailbox/pull", requestFor(receiver, regA, "pull"));
  assert.equal(pullA.status, 200);
  const itemA = (await pullA.json()).items[0];
  const itemB = (await (await post(worker, "/v1/mailbox/pull", requestFor(receiver, regB, "pull"))).json()).items[0];
  assert.equal(itemA.envelope, body.envelope);
  assert.equal(itemB.envelope, body.envelope);
  assert.notEqual(itemA.receipt, itemB.receipt);
  const itemsA = [{ id: body.id, receipt: itemA.receipt }];
  const forgedAck = requestFor(attacker, regA, "ack", { owner: receiver.owner, items: itemsA });
  assert.equal((await post(worker, "/v1/mailbox/ack", forgedAck)).status, 403);
  assert.equal((await post(worker, "/v1/mailbox/ack", requestFor(receiver, regA, "ack", { items: itemsA }))).status, 204);
  assert.equal((await (await post(worker, "/v1/mailbox/pull", requestFor(receiver, regA, "pull"))).json()).items.length, 0);
  assert.equal((await (await post(worker, "/v1/mailbox/pull", requestFor(receiver, regB, "pull"))).json()).items.length, 1);
  assert.equal((await post(worker, "/v1/mailbox/ack", requestFor(receiver, regB, "ack", {
    items: [{ id: body.id, receipt: itemB.receipt }],
  }))).status, 204);
  const retry = await post(worker, "/v1/mailbox/put", body);
  assert.equal(retry.status, 200);
  assert.deepEqual(await retry.json(), { state: "handled", expiresAt: stored.expiresAt, wakeQueued: false });
  assert.equal((await post(worker, "/v1/mailbox/put", { ...body, envelope: fakeEnvelope(2) })).status, 409);

  const notifications = await waitForFcm([regA.token, regB.token]);
  for (const call of notifications) {
    assert.deepEqual(call.message.data, { v: "1", route: await routeFor(receiver.owner, regA.capability) });
    assert.equal(JSON.stringify(call.message.data).includes(regA.capability), false);
    assert.equal(JSON.stringify(call.message.data).includes(body.envelope), false);
  }
});

test("HTTP handles large sealed structures, pagination, replay, malformed and unknown routes", async () => {
  const receiver = identity(), reg = await registration(receiver);
  assert.equal((await post(worker, "/v1/registration", reg)).status, 204);
  const base = { owner: receiver.owner, capability: reg.capability };
  const bodies = Array.from({ length: 5 }, (_, i) => ({ ...base, id: randomHex(16), envelope: fakeEnvelope(i + 1, i ? 32 : 64 * 1024 + 16) }));
  for (const body of bodies) assert.equal((await post(worker, "/v1/mailbox/put", body)).status, 201);
  const first = await (await post(worker, "/v1/mailbox/pull", requestFor(receiver, reg, "pull"))).json();
  assert.deepEqual(first.items.map((item) => item.id), bodies.slice(0, 4).map((body) => body.id));
  assert.equal(first.items[0].envelope.length, bodies[0].envelope.length);
  const next = await (await post(worker, "/v1/mailbox/pull", requestFor(receiver, reg, "pull", { after: first.next }))).json();
  assert.deepEqual(next.items.map((item) => item.id), [bodies[4].id]);
  const wrongCursor = requestFor(receiver, reg, "pull", { after: randomHex(16) });
  assert.equal((await post(worker, "/v1/mailbox/pull", wrongCursor)).status, 409);
  const changedCursor = { ...requestFor(receiver, reg, "pull"), after: first.next };
  assert.equal((await post(worker, "/v1/mailbox/pull", changedCursor)).status, 403);
  const fresh = requestFor(receiver, reg, "pull");
  assert.equal((await post(worker, "/v1/mailbox/pull", { ...fresh, issuedAt: fresh.issuedAt - 301 })).status, 400);
  assert.equal((await post(worker, "/v1/mailbox/put", { ...bodies[0], envelope: "not encrypted" })).status, 400);
  assert.equal((await post(worker, "/v1/mailbox/put", { ...bodies[0], capability: randomHex(32) })).status, 404);
  const huge = await worker.dispatchFetch("https://apu.test/v1/mailbox/put", {
    method: "POST", headers: { "content-type": "application/json" }, body: " ".repeat(200_000),
  });
  assert.equal(huge.status, 400);
});

test("mailbox switch is independent and the shard cannot be bypassed", async () => {
  const receiver = identity(), reg = await registration(receiver);
  const disabled = new Miniflare(options({ bindings: { ...bindings, MAILBOX_ENABLED: "false" } }));
  try {
    await disabled.ready;
    assert.equal((await post(disabled, "/v1/registration", reg)).status, 204, "wake registration remains independent");
    const body = { owner: reg.owner, capability: reg.capability, id: randomHex(16), envelope: fakeEnvelope() };
    assert.equal((await post(disabled, "/v1/mailbox/put", body)).status, 503);
    const namespace = await disabled.getDurableObjectNamespace("PUSH_SHARDS");
    const id = namespace.idFromName("apu-push-shard-v1:" + await shardFor(reg.owner));
    const direct = await namespace.get(id).fetch("https://push-internal.invalid/v1/mailbox/put", {
      method: "POST", headers: { "content-type": "application/json", "x-apu-rate-id": "ab".repeat(32) }, body: JSON.stringify(body),
    });
    assert.equal(direct.status, 503);
    assert.equal((await post(disabled, "/v1/mailbox/pull", requestFor(receiver, reg, "pull"))).status, 503);
  } finally { await disabled.dispose(); }
});

test("a workerd restart retains ciphertext and receipt; after ack a later restart retains the tombstone", async () => {
  const directory = await mkdtemp(join(tmpdir(), "apu-mailbox-test-"));
  let runtime;
  const receiver = identity(), reg = await registration(receiver);
  const body = { owner: receiver.owner, capability: reg.capability, id: randomHex(16), envelope: fakeEnvelope() };
  try {
    runtime = new Miniflare(options({ durableObjectsPersist: directory }));
    await runtime.ready;
    assert.equal((await post(runtime, "/v1/registration", reg)).status, 204);
    const accepted = await (await post(runtime, "/v1/mailbox/put", body)).json();
    const before = (await (await post(runtime, "/v1/mailbox/pull", requestFor(receiver, reg, "pull"))).json()).items[0];
    await runtime.dispose(); runtime = null;
    runtime = new Miniflare(options({ durableObjectsPersist: directory }));
    await runtime.ready;
    const recovered = (await (await post(runtime, "/v1/mailbox/pull", requestFor(receiver, reg, "pull"))).json()).items[0];
    assert.deepEqual(recovered, before);
    assert.equal(recovered.envelope, body.envelope);
    assert.equal((await post(runtime, "/v1/mailbox/ack", requestFor(receiver, reg, "ack", {
      items: [{ id: recovered.id, receipt: recovered.receipt }],
    }))).status, 204);
    await runtime.dispose(); runtime = null;
    runtime = new Miniflare(options({ durableObjectsPersist: directory }));
    await runtime.ready;
    const replay = await post(runtime, "/v1/mailbox/put", body);
    assert.equal(replay.status, 200);
    assert.deepEqual(await replay.json(), { state: "handled", expiresAt: accepted.expiresAt, wakeQueued: false });
    assert.equal((await (await post(runtime, "/v1/mailbox/pull", requestFor(receiver, reg, "pull"))).json()).items.length, 0);
  } finally {
    if (runtime) await runtime.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});

test("parallel HTTP retries are one stored item and a conflicting body cannot replace it", async () => {
  const receiver = identity(), reg = await registration(receiver);
  assert.equal((await post(worker, "/v1/registration", reg)).status, 204);
  const body = { owner: receiver.owner, capability: reg.capability, id: randomHex(16), envelope: fakeEnvelope() };
  const responses = await Promise.all(Array.from({ length: 4 }, () => post(worker, "/v1/mailbox/put", body)));
  assert.deepEqual(responses.map((response) => response.status).sort(), [200, 200, 200, 201]);
  assert.equal((await post(worker, "/v1/mailbox/put", { ...body, envelope: fakeEnvelope(9) })).status, 409);
  const result = await (await post(worker, "/v1/mailbox/pull", requestFor(receiver, reg, "pull"))).json();
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].envelope, body.envelope);
});
