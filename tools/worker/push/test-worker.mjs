// Runs the actual Worker and its SQLite Durable Objects in workerd/Miniflare.
// OAuth and FCM are fake endpoints: this never contacts Google or Cloudflare.
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, randomBytes, sign, verify } from "node:crypto";
import { EventEmitter, once } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Miniflare } from "miniflare";
import { registrationPayload, routeFor, shardFor } from "./protocol.mjs";

const rsa = generateKeyPairSync("rsa", { modulusLength: 2048 });
const credentials = {
  FCM_PROJECT_ID: "test-apu-project",
  FCM_CLIENT_EMAIL: "test-service@test-apu-project.invalid",
  FCM_PRIVATE_KEY: rsa.privateKey.export({ type: "pkcs8", format: "pem" }).toString(),
  PUSH_RATE_KEY: randomBytes(32).toString("hex"),
};
const calls = [];
const events = new EventEmitter();
let worker;

function fcmCallsFor(tokens) {
  const wanted = new Set(tokens);
  return calls.filter((entry) => entry.kind === "fcm" && wanted.has(entry.body.message.token));
}

async function waitForFcm(tokens, count) {
  const deadline = Date.now() + 20_000;
  while (fcmCallsFor(tokens).length < count) {
    const remaining = Math.max(1, deadline - Date.now());
    await once(events, "fcm", { signal: AbortSignal.timeout(remaining) });
  }
}

function identity() {
  const keys = generateKeyPairSync("ed25519");
  return { ...keys, owner: keys.publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("hex") };
}
const randomHex = (size) => randomBytes(size).toString("hex");
const token = () => "fcm-test-token-" + randomHex(16);

async function operation(keys, changes = {}) {
  const value = {
    action: "register", owner: keys.owner, capability: randomHex(32), device: randomHex(16),
    sequence: 1, issuedAt: Math.floor(Date.now() / 1000), token: token(), signature: "00".repeat(64), ...changes,
  };
  value.signature = sign(null, await registrationPayload(value), keys.privateKey).toString("hex");
  return value;
}

async function post(path, body, headers = {}) {
  return worker.dispatchFetch("https://apu.test" + path, {
    method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.2", ...headers },
    body: JSON.stringify(body),
  });
}

before(async () => {
  worker = new Miniflare({
    modules: true,
    scriptPath: new URL("./worker.mjs", import.meta.url).pathname,
    compatibilityDate: "2025-07-18",
    durableObjects: { PUSH_SHARDS: { className: "PushShard", useSQLite: true } },
    bindings: { ...credentials, PUSH_ENABLED: "true" },
    outboundService: async (request) => {
      if (request.url === "https://oauth2.googleapis.com/token") {
        calls.push({ kind: "oauth", body: await request.text() });
        return Response.json({ access_token: "temporary-access-token", expires_in: 3600 });
      }
      if (request.url === "https://fcm.googleapis.com/v1/projects/test-apu-project/messages:send") {
        const body = await request.json();
        calls.push({ kind: "fcm", authorization: request.headers.get("authorization"), body });
        events.emit("fcm");
        if (body.message.token.startsWith("unregistered-")) {
          return Response.json({ error: { details: [{ "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: "UNREGISTERED" }] } }, { status: 404 });
        }
        return Response.json({ name: "projects/test-apu-project/messages/test" });
      }
      throw new Error("Unexpected outbound host");
    },
  });
  await worker.ready;
});
after(async () => { if (worker) await worker.dispose(); });

test("registration and wake reach the real shard and construct an opaque FCM v1 call", async () => {
  const owner = identity();
  const reg = await operation(owner);
  assert.equal((await post("/v1/registration", reg)).status, 204);
  const result = await post("/v1/wake", { owner: reg.owner, capability: reg.capability });
  assert.equal(result.status, 202);
  assert.deepEqual(await result.json(), { accepted: true });
  await waitForFcm([reg.token], 1);
  const wake = fcmCallsFor([reg.token]).at(-1);
  assert.equal(wake.authorization, "Bearer temporary-access-token");
  assert.equal(wake.body.message.token, reg.token);
  assert.deepEqual(wake.body.message.data, { v: "1", route: await routeFor(reg.owner, reg.capability) });
  assert.equal(wake.body.message.android.priority, "HIGH");
  assert.equal(wake.body.message.android.ttl, "3600s");
  assert.equal(wake.body.message.notification, undefined);
  assert.equal(JSON.stringify(wake.body.message.data).includes(reg.capability), false);

  const oauth = calls.find((x) => x.kind === "oauth");
  const assertion = new URLSearchParams(oauth.body).get("assertion");
  const [head, claim, signature] = assertion.split(".");
  assert.deepEqual(JSON.parse(Buffer.from(head, "base64url")), { alg: "RS256", typ: "JWT" });
  const claims = JSON.parse(Buffer.from(claim, "base64url"));
  assert.equal(claims.iss, credentials.FCM_CLIENT_EMAIL);
  assert.equal(claims.aud, "https://oauth2.googleapis.com/token");
  assert.equal(claims.scope, "https://www.googleapis.com/auth/firebase.messaging");
  assert.equal(claims.exp - claims.iat, 3600);
  assert.ok(verify("RSA-SHA256", Buffer.from(head + "." + claim), rsa.publicKey, Buffer.from(signature, "base64url")));

  const before = fcmCallsFor([reg.token]).length;
  assert.equal((await post("/v1/wake", { owner: reg.owner, capability: reg.capability })).status, 202);
  const unknown = await post("/v1/wake", { owner: reg.owner, capability: randomHex(32) });
  assert.equal(unknown.status, 202);
  assert.deepEqual(await unknown.json(), { accepted: true });
  assert.equal(fcmCallsFor([reg.token]).length, before, "rate limit and unknown route do not call FCM immediately");
});

test("a capability holder cannot replace the receiver's token with their own signature", async () => {
  const receiver = identity();
  const attacker = identity();
  const capability = randomHex(32);
  const real = await operation(receiver, { capability });
  assert.equal((await post("/v1/registration", real)).status, 204);
  const forged = await operation(attacker, {
    owner: receiver.owner, capability, device: real.device, sequence: 2, token: token(),
  });
  assert.equal((await post("/v1/registration", forged)).status, 403);
  // Even a valid registration signed by the attacker maps to a different route.
  const other = await operation(attacker, { capability });
  assert.equal((await post("/v1/registration", other)).status, 204);
  assert.notEqual(await routeFor(real.owner, capability), await routeFor(other.owner, capability));
  assert.equal((await post("/v1/wake", { owner: real.owner, capability })).status, 202);
  await waitForFcm([real.token], 1);
  assert.equal(fcmCallsFor([other.token]).length, 0, "the attacker's token was not signalled");
  assert.equal(fcmCallsFor([real.token]).length, 1);
});

test("signed monotonic registration, revoke and tombstone reject replay", async () => {
  const owner = identity();
  const first = await operation(owner);
  const second = await operation(owner, { ...first, sequence: 2, token: token() });
  const revoke = await operation(owner, { ...second, action: "revoke", sequence: 3, token: "" });
  assert.equal((await post("/v1/registration", first)).status, 204);
  assert.equal((await post("/v1/registration", second)).status, 204);
  assert.equal((await post("/v1/registration", first)).status, 409);
  assert.equal((await post("/v1/registration", revoke)).status, 204);
  assert.equal((await post("/v1/registration", second)).status, 409);
  assert.equal((await post("/v1/wake", { owner: first.owner, capability: first.capability })).status, 202);
  assert.equal(fcmCallsFor([first.token, second.token]).length, 0);
  const restored = await operation(owner, { ...first, sequence: 4, token: token() });
  assert.equal((await post("/v1/registration", restored)).status, 204);
  assert.equal((await post("/v1/wake", { owner: first.owner, capability: first.capability })).status, 202);
  await waitForFcm([restored.token], 1);
  assert.equal(fcmCallsFor([first.token, second.token]).length, 0);
});

test("fanout is capped at four installations, and provider acceptance remains only a signal", async () => {
  const owner = identity();
  const capability = randomHex(32);
  const tokens = [];
  for (let i = 0; i < 4; i++) {
    const registration = await operation(owner, { capability });
    tokens.push(registration.token);
    assert.equal((await post("/v1/registration", registration)).status, 204);
  }
  assert.equal((await post("/v1/registration", await operation(owner, { capability }))).status, 429);
  const response = await post("/v1/wake", { owner: owner.owner, capability });
  assert.equal(response.status, 202);
  assert.deepEqual(await response.json(), { accepted: true });
  await waitForFcm(tokens, 4);
  assert.equal(fcmCallsFor(tokens).length, 4);
});

test("an explicitly unregistered token becomes a replay tombstone", async () => {
  const owner = identity();
  const reg = await operation(owner, { token: "unregistered-" + randomHex(16) });
  assert.equal((await post("/v1/registration", reg)).status, 204);
  assert.equal((await post("/v1/wake", { owner: reg.owner, capability: reg.capability })).status, 202);
  await waitForFcm([reg.token], 1);
  assert.equal((await post("/v1/registration", reg)).status, 409);
  const next = await operation(owner, { ...reg, sequence: 2, token: token() });
  assert.equal((await post("/v1/registration", next)).status, 204);
});

test("unknown capabilities cannot exhaust an owner's budget, and valid routes share a bounded budget", async () => {
  const owner = identity();
  for (let i = 0; i < 20; i++) {
    assert.equal((await post("/v1/wake", { owner: owner.owner, capability: randomHex(32) })).status, 202);
  }
  const capabilities = [];
  const tokens = [];
  for (let i = 0; i < 13; i++) {
    const reg = await operation(owner);
    capabilities.push(reg.capability);
    tokens.push(reg.token);
    assert.equal((await post("/v1/registration", reg)).status, 204);
  }
  for (const capability of capabilities) {
    assert.equal((await post("/v1/wake", { owner: owner.owner, capability })).status, 202);
  }
  await waitForFcm(tokens, 12);
  assert.equal(fcmCallsFor(tokens).length, 12);
});

test("invalid data, unsupported methods, plaintext HTTP and oversized bodies fail closed", async () => {
  const owner = identity();
  const reg = await operation(owner);
  assert.equal((await post("/v1/registration", { ...reg, issuedAt: reg.issuedAt - 301 })).status, 400);
  assert.equal((await post("/v1/registration", { ...reg, extra: "message body" })).status, 400);
  assert.equal((await post("/v1/wake", { owner: reg.owner, capability: [], text: "no" })).status, 400);
  assert.equal((await post("/v1/wake", { owner: reg.owner, capability: reg.capability }, { "content-type": "text/plain" })).status, 400);
  const big = await worker.dispatchFetch("https://apu.test/v1/wake", {
    method: "POST", headers: { "content-type": "application/json" }, body: " ".repeat(9000),
  });
  assert.equal(big.status, 400);
  assert.equal((await worker.dispatchFetch("https://apu.test/v1/wake")).status, 404);
  const insecure = await worker.dispatchFetch("http://apu.test/v1/wake", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ owner: reg.owner, capability: reg.capability }),
  });
  assert.equal(insecure.status, 400);
});

test("without explicit configuration no endpoint accepts or sends a wake", async () => {
  const disabled = new Miniflare({
    modules: true, scriptPath: new URL("./worker.mjs", import.meta.url).pathname,
    compatibilityDate: "2025-07-18", durableObjects: { PUSH_SHARDS: { className: "PushShard", useSQLite: true } },
    bindings: { ...credentials, PUSH_ENABLED: "false" },
  });
  try {
    assert.equal((await disabled.dispatchFetch("https://apu.test/health")).status, 503);
    const response = await disabled.dispatchFetch("https://apu.test/v1/wake", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ owner: randomHex(32), capability: randomHex(32) }),
    });
    assert.equal(response.status, 503);
  } finally { await disabled.dispose(); }
});

test("workerd preserves a failed wake and deadline across restart; a re-armed alarm retries it", async () => {
  const directory = await mkdtemp(join(tmpdir(), "apu-push-test-"));
  let runtime;
  let fcmCalls = 0;
  let alarmCompletions = 0;
  const alarmEvents = new EventEmitter();
  const waitForAlarm = async (count) => {
    const deadline = Date.now() + 20_000;
    while (alarmCompletions < count) {
      await once(alarmEvents, "alarm", { signal: AbortSignal.timeout(Math.max(1, deadline - Date.now())) });
    }
  };
  const options = {
    modules: true,
    scriptPath: new URL("./fixture-wake-queue.mjs", import.meta.url).pathname,
    compatibilityDate: "2025-07-18",
    durableObjects: { PUSH_SHARDS: { className: "TestPushShard", useSQLite: true } },
    durableObjectsPersist: directory,
    bindings: { ...credentials, PUSH_ENABLED: "true" },
    outboundService: async (request) => {
      if (request.url === "https://test-alarm.invalid/completed") {
        alarmCompletions++;
        alarmEvents.emit("alarm");
        return new Response(null, { status: 204 });
      }
      if (request.url === "https://oauth2.googleapis.com/token") {
        return Response.json({ access_token: "temporary-access-token", expires_in: 3600 });
      }
      if (request.url === "https://fcm.googleapis.com/v1/projects/test-apu-project/messages:send") {
        fcmCalls++;
        return fcmCalls === 1 ? Response.json({ error: { status: "UNAVAILABLE" } }, { status: 503 }) :
          Response.json({ name: "projects/test-apu-project/messages/test" });
      }
      throw new Error("Unexpected outbound host");
    },
  };
  const owner = identity();
  const reg = await operation(owner);
  const localPost = (instance, path, body) => instance.dispatchFetch("https://apu.test" + path, {
    method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.99" },
    body: JSON.stringify(body),
  });
  const object = async (instance, path) => {
    const namespace = await instance.getDurableObjectNamespace("PUSH_SHARDS");
    const id = namespace.idFromName("apu-push-shard-v1:" + await shardFor(reg.owner));
    const response = await namespace.get(id).fetch("https://push-internal.invalid" + path, { method: "POST" });
    assert.equal(response.status, 200);
    return response.json();
  };
  try {
    runtime = new Miniflare(options);
    await runtime.ready;
    assert.equal((await localPost(runtime, "/v1/registration", reg)).status, 204);
    assert.equal((await localPost(runtime, "/v1/wake", { owner: reg.owner, capability: reg.capability })).status, 202);
    await waitForAlarm(1);
    assert.equal(fcmCalls, 1);
    const beforeRestart = await object(runtime, "/test/pending");
    assert.equal(beforeRestart.count, 1);
    assert.deepEqual(await object(runtime, "/test/prepare-restart"), { count: 1 });
    const scheduled = await object(runtime, "/test/pending");
    assert.ok(scheduled.alarm > Date.now());
    await runtime.dispose();
    runtime = null;
    runtime = new Miniflare(options);
    await runtime.ready;
    const afterRestart = await object(runtime, "/test/pending");
    assert.equal(afterRestart.count, 1);
    assert.equal(afterRestart.alarm, scheduled.alarm);
    assert.deepEqual(await object(runtime, "/test/rearm-persisted-alarm"), { saved: scheduled.alarm });
    await waitForAlarm(2);
    assert.equal((await object(runtime, "/test/pending")).count, 0);
    assert.equal(fcmCalls, 2);
  } finally {
    if (runtime) await runtime.dispose();
    await rm(directory, { recursive: true, force: true });
  }
});

test("workerd responds to another wake while FCM is stalled and retains the later signal", async () => {
  let releaseProvider;
  const providerGate = new Promise((resolve) => { releaseProvider = resolve; });
  const progress = new EventEmitter();
  let started = 0;
  let finished = 0;
  const waitFor = async (predicate) => {
    const deadline = Date.now() + 20_000;
    while (!predicate()) await once(progress, "progress", { signal: AbortSignal.timeout(Math.max(1, deadline - Date.now())) });
  };
  const runtime = new Miniflare({
    modules: true,
    scriptPath: new URL("./fixture-wake-queue.mjs", import.meta.url).pathname,
    compatibilityDate: "2025-07-18",
    durableObjects: { PUSH_SHARDS: { className: "TestPushShard", useSQLite: true } },
    bindings: { ...credentials, PUSH_ENABLED: "true" },
    outboundService: async (request) => {
      if (request.url === "https://oauth2.googleapis.com/token") {
        return Response.json({ access_token: "temporary-access-token", expires_in: 3600 });
      }
      if (request.url === "https://fcm.googleapis.com/v1/projects/test-apu-project/messages:send") {
        started++;
        progress.emit("progress");
        await providerGate;
        return Response.json({ name: "projects/test-apu-project/messages/test" });
      }
      if (request.url === "https://test-alarm.invalid/completed") {
        finished++;
        progress.emit("progress");
        return new Response(null, { status: 204 });
      }
      throw new Error("Unexpected outbound host");
    },
  });
  const owner = identity();
  const reg = await operation(owner);
  const postLocal = (path, body) => runtime.dispatchFetch("https://apu.test" + path, {
    method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.97" },
    body: JSON.stringify(body),
  });
  const state = async () => {
    const namespace = await runtime.getDurableObjectNamespace("PUSH_SHARDS");
    const id = namespace.idFromName("apu-push-shard-v1:" + await shardFor(reg.owner));
    return (await namespace.get(id).fetch("https://push-internal.invalid/test/pending", { method: "POST" })).json();
  };
  try {
    await runtime.ready;
    assert.equal((await postLocal("/v1/registration", reg)).status, 204);
    assert.equal((await postLocal("/v1/wake", { owner: reg.owner, capability: reg.capability })).status, 202);
    assert.equal(started, 0, "acceptance does not wait for FCM");
    await waitFor(() => started === 1);
    assert.equal((await postLocal("/v1/wake", { owner: reg.owner, capability: reg.capability })).status, 202);
    assert.equal((await state()).generation, 2);
    releaseProvider();
    await waitFor(() => finished === 1);
    const retained = await state();
    assert.equal(retained.count, 1);
    assert.equal(retained.generation, 2, "the earlier provider result cannot delete a newer wake");
  } finally {
    releaseProvider();
    await runtime.dispose();
  }
});
