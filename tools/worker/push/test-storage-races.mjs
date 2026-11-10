// Deterministic race/expiry tests against the production SQL statements. The
// public request path is separately exercised in workerd with SQLite DOs.
import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { PushShard } from "./worker.mjs";

function makeShard() {
  const db = new DatabaseSync(":memory:");
  const sql = {
    exec(query, ...params) {
      const stmt = db.prepare(query);
      if (/^\s*SELECT\b/i.test(query)) return { toArray: () => stmt.all(...params) };
      stmt.run(...params);
      return { toArray: () => [] };
    },
  };
  let alarm = null;
  const storage = {
    sql,
    transactionSync(fn) {
      db.exec("BEGIN");
      try { const value = fn(); db.exec("COMMIT"); return value; }
      catch (error) { db.exec("ROLLBACK"); throw error; }
    },
    async getAlarm() { return alarm; },
    async setAlarm(value) { alarm = value; },
    async deleteAlarm() { alarm = null; },
  };
  const env = {
    PUSH_ENABLED: "true", PUSH_RATE_KEY: "test-rate-key-".repeat(4),
    FCM_PROJECT_ID: "test-project", FCM_CLIENT_EMAIL: "test@example.invalid",
    FCM_PRIVATE_KEY: "-----BEGIN PRIVATE KEY-----test-only",
  };
  const shard = new PushShard({ storage }, env);
  return { db, sql, shard, storage, env, getAlarm: () => alarm };
}

const owner = "12".repeat(32);
const route = "34".repeat(32);
const device = "56".repeat(16);
const oldToken = "old-fcm-token-abcdefghijklmnop";
const newToken = "new-fcm-token-abcdefghijklmnop";

function insert(sql, now, token = oldToken) {
  sql.exec("INSERT INTO registrations(route,owner,device,token,sequence,expires_at) VALUES (?,?,?,?,?,?)",
    route, owner, device, token, 1, now + 100_000);
}

function row(sql) {
  return sql.exec("SELECT token,sequence,expires_at FROM registrations WHERE route=? AND device=?", route, device).toArray()[0];
}

function pending(sql) {
  return sql.exec("SELECT route,owner,device,generation,attempts,next_at,expires_at,claim_id,lease_until FROM pending_wakes ORDER BY device").toArray();
}

// Simulate passage of retry/cooldown time without sleeping or contacting FCM.
function makePendingDue(sql, now = Date.now()) {
  sql.exec("UPDATE pending_wakes SET next_at = ?", now - 1);
  sql.exec("DELETE FROM rate_limits WHERE bucket LIKE 'wake-%'");
}

test("UNREGISTERED retires only the old token and preserves a replay tombstone", async () => {
  const { db, sql, shard } = makeShard();
  const now = Date.now();
  insert(sql, now);
  shard.provider = { send: async () => "unregistered" };
  const response = await shard.wake(owner, route, now);
  assert.equal(response.status, 202);
  await shard.alarm();
  assert.equal(row(sql).token, null);
  assert.equal(row(sql).sequence, 1);
  assert.ok(row(sql).expires_at >= now + 15 * 60_000);
  assert.equal(pending(sql).length, 0);
  db.close();
});

test("late UNREGISTERED response cannot delete a token rotated while FCM was in flight", async () => {
  const { db, sql, shard } = makeShard();
  const now = Date.now();
  insert(sql, now);
  let started;
  const entered = new Promise((resolve) => { started = resolve; });
  let complete;
  const providerResponse = new Promise((resolve) => { complete = resolve; });
  shard.provider = { send: async () => { started(); return providerResponse; } };
  assert.equal((await shard.wake(owner, route, now)).status, 202);
  const alarm = shard.alarm();
  await entered;
  sql.exec("UPDATE registrations SET token=?, sequence=2 WHERE route=? AND device=?", newToken, route, device);
  complete("unregistered");
  await alarm;
  assert.equal(row(sql).token, newToken);
  assert.equal(row(sql).sequence, 2);
  assert.equal(pending(sql).length, 1, "old provider result cannot clear the new token's pending wake");
  const retried = [];
  shard.provider = { send: async (value) => { retried.push(value); return "sent"; } };
  makePendingDue(sql);
  await shard.alarm();
  assert.deepEqual(retried, [newToken]);
  assert.equal(pending(sql).length, 0);
  db.close();
});

test("bounded alarm cleanup schedules a prompt continuation for remaining expired rows", async () => {
  const { db, sql, shard, getAlarm } = makeShard();
  const now = Date.now();
  for (let i = 0; i < 130; i++) {
    sql.exec("INSERT INTO registrations(route,owner,device,token,sequence,expires_at) VALUES (?,?,?,?,?,?)",
      i.toString().padStart(64, "0"), owner, device, oldToken, 1, now - 1);
  }
  await shard.alarm();
  assert.equal(sql.exec("SELECT count(*) AS n FROM registrations").toArray()[0].n, 2);
  assert.ok(getAlarm() >= now + 60_000 && getAlarm() <= Date.now() + 61_000);
  await shard.alarm();
  assert.equal(sql.exec("SELECT count(*) AS n FROM registrations").toArray()[0].n, 0);
  db.close();
});

test("provider failure persists a bounded retry and an alarm resumes it", async () => {
  const { db, sql, shard, getAlarm } = makeShard();
  const now = Date.now();
  insert(sql, now);
  let calls = 0;
  shard.provider = { send: async () => (++calls === 1 ? "retry" : "sent") };
  assert.equal((await shard.wake(owner, route, now)).status, 202);
  assert.equal(calls, 0, "HTTP acceptance does not await or start a provider request");
  await shard.alarm();
  const queued = pending(sql);
  assert.equal(queued.length, 1);
  assert.equal(queued[0].attempts, 1);
  assert.ok(queued[0].next_at >= now + 15_000 && queued[0].next_at <= Date.now() + 18_000);
  assert.ok(queued[0].expires_at <= Date.now() + 60 * 60_000);
  assert.ok(getAlarm() !== null && getAlarm() <= queued[0].next_at);
  assert.equal(JSON.stringify(queued).includes(oldToken), false, "pending work does not copy token");
  makePendingDue(sql);
  await shard.alarm();
  assert.equal(calls, 2);
  assert.equal(pending(sql).length, 0);
  db.close();
});

test("coalesces a wake arriving during a send without losing the later request", async () => {
  const { db, sql, shard } = makeShard();
  const now = Date.now();
  insert(sql, now);
  let started;
  const entered = new Promise((resolve) => { started = resolve; });
  let complete;
  const provider = new Promise((resolve) => { complete = resolve; });
  shard.provider = { send: async () => { started(); return provider; } };
  assert.equal((await shard.wake(owner, route, now)).status, 202);
  const first = shard.alarm();
  await entered;
  assert.equal((await shard.wake(owner, route, Date.now())).status, 202);
  assert.equal(pending(sql).length, 1);
  assert.equal(pending(sql)[0].generation, 2);
  complete("sent");
  await first;
  assert.equal(pending(sql).length, 1, "an older provider success must not erase the later wake");
  let calls = 0;
  shard.provider = { send: async () => { calls++; return "sent"; } };
  // A request arriving within the 30-second route budget waits, rather than
  // being dropped or sending an immediate duplicate.
  await shard.alarm();
  assert.equal(calls, 0);
  assert.equal(pending(sql).length, 1);
  makePendingDue(sql);
  await shard.alarm();
  assert.equal(calls, 1);
  assert.equal(pending(sql).length, 0);
  db.close();
});

test("one device's retry does not repeatedly notify the successful device", async () => {
  const { db, sql, shard } = makeShard();
  const now = Date.now();
  insert(sql, now);
  const device2 = "67".repeat(16);
  sql.exec("INSERT INTO registrations(route,owner,device,token,sequence,expires_at) VALUES (?,?,?,?,?,?)",
    route, owner, device2, newToken, 1, now + 100_000);
  const calls = [];
  shard.provider = { send: async (value) => { calls.push(value); return value === oldToken ? "sent" : "retry"; } };
  await shard.wake(owner, route, now);
  await shard.alarm();
  assert.equal(calls.length, 2);
  assert.equal(pending(sql).length, 1);
  assert.equal(pending(sql)[0].device, device2);
  makePendingDue(sql);
  shard.provider = { send: async (value) => { calls.push(value); return "sent"; } };
  await shard.alarm();
  assert.equal(calls.filter((value) => value === oldToken).length, 1);
  assert.equal(calls.filter((value) => value === newToken).length, 2);
  assert.equal(pending(sql).length, 0);
  db.close();
});

test("lease recovery after object restart rejects an obsolete in-flight response", async () => {
  const { db, sql, shard, storage, env, getAlarm } = makeShard();
  const now = Date.now();
  insert(sql, now);
  shard.provider = { send: async () => "retry" };
  await shard.wake(owner, route, now);
  await shard.alarm();
  makePendingDue(sql, now);
  const oldClaims = await shard.wakes.claim(owner, route, now);
  assert.equal(oldClaims.length, 1);
  assert.ok(getAlarm() !== null && getAlarm() <= now + 60_000);
  // Simulate object eviction after the lease was committed, and passage of its
  // deadline. The persisted pending row is owned by a fresh shard instance.
  const recovered = new PushShard({ storage }, env);
  sql.exec("UPDATE pending_wakes SET lease_until = 0, next_at = ?", now - 1);
  sql.exec("DELETE FROM rate_limits WHERE bucket LIKE 'wake-%'");
  const newClaims = await recovered.wakes.claim(owner, route, Date.now());
  assert.equal(newClaims.length, 1);
  assert.notEqual(newClaims[0].claimId, oldClaims[0].claimId);
  await shard.wakes.finish(owner, route, oldClaims[0], "unregistered");
  assert.equal(row(sql).token, oldToken, "stale response cannot revoke the new lease's token");
  assert.equal(pending(sql)[0].claim_id, newClaims[0].claimId);
  await recovered.wakes.finish(owner, route, newClaims[0], "sent");
  assert.equal(pending(sql).length, 0);
  db.close();
});

test("revoke, expired work, and unknown routes cannot cause a later provider send", async () => {
  const { db, sql, shard } = makeShard();
  const now = Date.now();
  let calls = 0;
  shard.provider = { send: async () => { calls++; return "retry"; } };
  await shard.wake(owner, route, now);
  assert.equal(calls, 0);
  assert.equal(pending(sql).length, 0);
  insert(sql, now);
  await shard.wake(owner, route, now);
  await shard.alarm();
  assert.equal(calls, 1);
  sql.exec("UPDATE registrations SET token=NULL WHERE route=? AND device=?", route, device);
  makePendingDue(sql);
  await shard.alarm();
  assert.equal(calls, 1);
  assert.equal(pending(sql).length, 0);
  sql.exec("UPDATE registrations SET token=? WHERE route=? AND device=?", oldToken, route, device);
  await shard.wake(owner, route, Date.now());
  assert.equal(pending(sql).length, 1);
  sql.exec("UPDATE pending_wakes SET expires_at=?", Date.now() - 1);
  await shard.alarm();
  assert.equal(pending(sql).length, 0);
  db.close();
});

test("new requests do not postpone an already scheduled alarm", async () => {
  const { db, sql, shard, storage, getAlarm } = makeShard();
  const now = Date.now();
  insert(sql, now);
  const existing = now + 25;
  await storage.setAlarm(existing);
  for (let i = 0; i < 8; i++) assert.equal((await shard.wake(owner, route, now)).status, 202);
  assert.equal(getAlarm(), existing);
  assert.equal(pending(sql)[0].generation, 8);
  db.close();
});

test("capacity stays bounded while existing pending work can still coalesce", async () => {
  const { db, sql, shard, storage } = makeShard();
  const now = Date.now();
  insert(sql, now);
  storage.transactionSync(() => {
    for (let i = 0; i < 25_000; i++) {
      sql.exec(`INSERT INTO pending_wakes(route,owner,device,generation,attempts,next_at,expires_at,claim_id,lease_until)
        VALUES (?,?,?,1,0,?,?,NULL,0)`, i.toString(16).padStart(64, "0"), owner, device, now, now + 60_000);
    }
  });
  // The public response deliberately does not reveal if the route exists or if
  // the queue was full. The caller must have its own retry policy.
  assert.equal((await shard.wake(owner, route, now)).status, 202);
  assert.equal(shard.count("SELECT count(*) AS n FROM pending_wakes"), 25_000);
  assert.equal(shard.count("SELECT count(*) AS n FROM pending_wakes WHERE route = ?", route), 0);
  const existingRoute = "0".repeat(64);
  sql.exec("UPDATE registrations SET route = ? WHERE route = ?", existingRoute, route);
  assert.equal((await shard.wake(owner, existingRoute, now)).status, 202);
  assert.equal(shard.count("SELECT count(*) AS n FROM pending_wakes"), 25_000);
  assert.equal(Number(sql.exec("SELECT generation FROM pending_wakes WHERE route = ?", existingRoute).toArray()[0].generation), 2);
  db.close();
});

test("the kill switch suppresses queued alarm sends without a hot retry loop", async () => {
  const { db, sql, shard, storage, env, getAlarm } = makeShard();
  const now = Date.now();
  insert(sql, now);
  let calls = 0;
  shard.provider = { send: async () => { calls++; return "sent"; } };
  await shard.wake(owner, route, now);
  assert.equal(pending(sql).length, 1);
  env.PUSH_ENABLED = "false";
  await storage.deleteAlarm(); // workerd clears the dispatched alarm before alarm()
  await shard.alarm();
  assert.equal(calls, 0);
  assert.equal(pending(sql).length, 1);
  assert.ok(getAlarm() >= now + 50 * 60_000, "disabled queue waits for expiry rather than spinning");
  env.PUSH_ENABLED = "true";
  env.FCM_PRIVATE_KEY = "";
  await storage.deleteAlarm();
  await shard.alarm();
  assert.equal(calls, 0, "missing provider credentials also fail closed");
  env.FCM_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----test-only";
  await storage.deleteAlarm();
  await shard.alarm();
  assert.equal(calls, 1);
  assert.equal(pending(sql).length, 0);
  db.close();
});
