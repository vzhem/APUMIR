// Production SQL and transaction boundaries exercised with real Node SQLite.
// These are not Cloudflare deployment or end-to-end Android tests.
import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { PushShard } from "./worker.mjs";
import { CiphertextMailbox, MAILBOX_LIMITS } from "./mailbox.mjs";
import { fakeEnvelope, mailboxPut, randomHex } from "./mailbox-test-fixture.mjs";

const owner = "12".repeat(32), route = "34".repeat(32), capability = "98".repeat(32);
const deviceA = "56".repeat(16), deviceB = "67".repeat(16);
const token = "fcm-test-token-abcdefghijklmnop";

function make(t) {
  const db = new DatabaseSync(":memory:");
  t.after(() => db.close());
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
    PUSH_ENABLED: "true", MAILBOX_ENABLED: "true", PUSH_RATE_KEY: "test-rate-key-".repeat(4),
    FCM_PROJECT_ID: "test-project", FCM_CLIENT_EMAIL: "test@example.invalid",
    FCM_PRIVATE_KEY: "-----BEGIN PRIVATE KEY-----test-only",
  };
  const shard = new PushShard({ storage }, env);
  const rows = (query, ...params) => sql.exec(query, ...params).toArray();
  const register = (dev = deviceA, r = route, o = owner) => sql.exec(
    "INSERT INTO registrations(route,owner,device,token,sequence,expires_at) VALUES (?,?,?,?,?,?)",
    r, o, dev, token, 1, Date.now() + 30 * 24 * 60 * 60_000,
  );
  return { db, sql, storage, shard, env, rows, register, getAlarm: () => alarm };
}

const putBody = (salt = randomHex(16), envelope = fakeEnvelope(), expiresAt = Date.now() + 6 * 24 * 60 * 60_000) => {
  // Vary the fake signature bytes so separate test messages get distinct ids.
  const fields = envelope.split("|");
  const key = Buffer.from(fields[1], "base64");
  Buffer.from(salt, "hex").copy(key, key.length - 16);
  fields[1] = key.toString("base64");
  return mailboxPut(owner, capability, fields.join("|"), expiresAt);
};
// Direct storage tests use a structurally valid placeholder signature; the
// separate HTTP/workerd tests exercise actual Ed25519 verification.
const accessBody = (dev, fields) => ({
  owner, capability, device: dev, issuedAt: Math.floor(Date.now() / 1000), signature: "00".repeat(64), ...fields,
});
const pull = (shard, dev = deviceA, after = "-") => shard.mailbox.pull(accessBody(dev, { after }), route, Date.now());
const ack = (shard, dev, items) => shard.mailbox.ack(
  accessBody(dev, { items: items.slice().sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0) }), route, Date.now(),
);

test("ciphertext and per-device wake commit together, survive object recreation, and retire only after every target", async (t) => {
  const { shard, storage, env, rows, register, getAlarm } = make(t);
  register(); register(deviceB);
  const body = putBody();
  const now = Date.now();
  const accepted = await shard.mailbox.put(body, route, now);
  assert.equal(accepted.status, 201);
  assert.equal(accepted.state, "stored");
  assert.equal(accepted.wakeQueued, true);
  assert.equal(accepted.expiresAt, body.expiresAt, "server must not extend the immutable cutoff");
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 1);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 2);
  assert.equal(rows("SELECT count(*) AS n FROM pending_wakes")[0].n, 2);
  assert.ok(getAlarm() !== null);
  const stored = JSON.stringify(rows("SELECT * FROM mailbox_messages")) + JSON.stringify(rows("SELECT * FROM mailbox_targets"));
  assert.equal(stored.includes(capability), false);
  assert.equal(stored.includes(token), false);

  const restarted = new PushShard({ storage }, env);
  const first = pull(restarted, deviceA).items[0];
  const second = pull(restarted, deviceB).items[0];
  assert.equal(first.envelope, body.envelope);
  assert.equal(second.envelope, body.envelope);
  assert.notEqual(first.receipt, second.receipt);
  assert.equal(ack(restarted, deviceA, [{ id: first.id, receipt: first.receipt }]).status, 204);
  assert.equal(pull(restarted, deviceA).items.length, 0);
  assert.equal(pull(restarted, deviceB).items.length, 1);
  const duplicate = await restarted.mailbox.put(body, route, Date.now());
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.state, "stored");
  assert.equal(duplicate.expiresAt, accepted.expiresAt, "retry must not extend retention");
  assert.equal(ack(restarted, deviceB, [{ id: second.id, receipt: second.receipt }]).status, 204);
  const tombstone = rows("SELECT envelope, bytes FROM mailbox_messages")[0];
  assert.equal(tombstone.envelope, null);
  assert.equal(tombstone.bytes, 0);
  assert.equal((await restarted.mailbox.put(body, route, Date.now())).state, "handled");
  assert.equal((await restarted.mailbox.put({ ...body, envelope: fakeEnvelope(2) }, route, Date.now())).status, 400);
  assert.equal(ack(restarted, deviceB, [{ id: second.id, receipt: second.receipt }]).status, 204);
});

test("concurrent duplicate puts serialize; a changed ciphertext cannot reuse the id", async (t) => {
  const { shard, rows, register } = make(t);
  register();
  const body = putBody();
  const [one, two] = await Promise.all([shard.mailbox.put(body, route, Date.now()), shard.mailbox.put(body, route, Date.now())]);
  assert.deepEqual([one.status, two.status].sort(), [200, 201]);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 1);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 1);
  assert.equal(rows("SELECT generation FROM pending_wakes")[0].generation, 2);
  assert.equal((await shard.mailbox.put({ ...body, envelope: fakeEnvelope(8) }, route, Date.now())).status, 400);
  assert.equal(pull(shard).items[0].envelope, body.envelope);
});

test("queue exhaustion or an exception after inserting rolls back ciphertext, targets and wakes", async (t) => {
  const { shard, rows, register, getAlarm } = make(t);
  register();
  const original = shard.wakes.enqueueInTransaction.bind(shard.wakes);
  shard.wakes.enqueueInTransaction = (...args) => ({ ...original(...args), queued: 0 });
  const body = putBody();
  assert.equal((await shard.mailbox.put(body, route, Date.now())).status, 503);
  for (const table of ["mailbox_messages", "mailbox_targets", "pending_wakes"]) {
    assert.equal(rows(`SELECT count(*) AS n FROM ${table}`)[0].n, 0, table);
  }
  assert.ok(getAlarm() !== null, "a recovery alarm was set before the failed transaction");
  shard.wakes.enqueueInTransaction = (...args) => { original(...args); throw new Error("injected crash"); };
  await assert.rejects(shard.mailbox.put(body, route, Date.now()), /injected crash/);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 0);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 0);
  assert.equal(rows("SELECT count(*) AS n FROM pending_wakes")[0].n, 0);
  shard.wakes.enqueueInTransaction = original;
  assert.equal((await shard.mailbox.put(body, route, Date.now())).status, 201);
});

test("expired requests cannot resurrect after tombstone cleanup, and stale receipts cannot remove a new id", async (t) => {
  const { shard, storage, rows, register } = make(t);
  register();
  const deadline = Date.now() + 5_000;
  const body = putBody(randomHex(16), fakeEnvelope(), deadline);
  assert.equal((await shard.mailbox.put(body, route, Date.now())).status, 201);
  const old = pull(shard).items[0];
  assert.equal(ack(shard, deviceA, [{ id: old.id, receipt: old.receipt }]).status, 204);
  assert.equal(rows("SELECT envelope FROM mailbox_messages")[0].envelope, null);
  const afterExpiry = deadline + 1;
  storage.transactionSync(() => shard.mailbox.cleanup(afterExpiry));
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 0);
  assert.equal((await shard.mailbox.put(body, route, afterExpiry)).status, 400);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 0);
  // Altering only the cutoff while keeping the old id is invalid; a new
  // envelope/cutoff combination gets a distinct id and independent receipt.
  assert.equal((await shard.mailbox.put({ ...body, expiresAt: afterExpiry + 10_000 }, route, afterExpiry)).status, 400);
  const next = mailboxPut(owner, capability, body.envelope, afterExpiry + 10_000);
  assert.notEqual(next.id, body.id);
  assert.equal((await shard.mailbox.put(next, route, afterExpiry)).status, 201);
  const fresh = pull(shard).items[0];
  assert.notEqual(fresh.receipt, old.receipt);
  assert.equal(ack(shard, deviceA, [{ id: old.id, receipt: old.receipt }]).status, 204);
  assert.equal(pull(shard).items[0].receipt, fresh.receipt);
  assert.equal(ack(shard, deviceA, [{ id: fresh.id, receipt: fresh.receipt }]).status, 204);
  assert.equal(pull(shard).items.length, 0);
});

test("cutoff is rechecked inside the transaction and inconsistent stored deadlines fail closed", async (t) => {
  const { shard, sql, rows, register } = make(t);
  register();
  const body = putBody(randomHex(16), fakeEnvelope(), Date.now() + 30_000);
  const mutate = shard.wakes.mutate.bind(shard.wakes);
  shard.wakes.mutate = (at, action) => mutate(at, () => action(body.expiresAt));
  assert.equal((await shard.mailbox.put(body, route, Date.now())).status, 400);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 0);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 0);
  assert.equal(rows("SELECT count(*) AS n FROM pending_wakes")[0].n, 0);
  shard.wakes.mutate = mutate;
  assert.equal((await shard.mailbox.put(body, route, Date.now())).status, 201);
  sql.exec("UPDATE mailbox_messages SET expires_at = ? WHERE route = ? AND message_id = ?", body.expiresAt - 1, route, body.id);
  assert.equal((await shard.mailbox.put(body, route, Date.now())).status, 409);
  assert.equal(rows("SELECT envelope FROM mailbox_messages")[0].envelope, body.envelope);
});

test("cutoff remains enforced if safety-alarm persistence waits past expiry", async (t) => {
  const initial = 1_700_000_000_000;
  t.mock.timers.enable({ apis: ["Date"], now: initial });
  const { shard, rows, register } = make(t);
  register();
  const body = putBody(randomHex(16), fakeEnvelope(), initial + 1_000);
  const safetyAlarm = shard.wakes.safetyAlarm.bind(shard.wakes);
  shard.wakes.safetyAlarm = async (now) => {
    await safetyAlarm(now);
    // Model an arbitrarily delayed getAlarm/setAlarm without a flaky wall-clock sleep.
    t.mock.timers.setTime(body.expiresAt);
  };
  assert.equal((await shard.mailbox.put(body, route, initial)).status, 400);
  for (const table of ["mailbox_messages", "mailbox_targets", "pending_wakes"]) {
    assert.equal(rows(`SELECT count(*) AS n FROM ${table}`)[0].n, 0, table);
  }
});

test("pull and ack recheck signed freshness inside the storage transaction", async (t) => {
  const initial = 1_700_000_000_000;
  t.mock.timers.enable({ apis: ["Date"], now: initial });
  const { shard, storage, rows, register } = make(t);
  register();
  const body = putBody();
  assert.equal((await shard.mailbox.put(body, route, initial)).status, 201);
  const item = pull(shard).items[0];
  const read = accessBody(deviceA, { after: "-" });
  const acknowledge = accessBody(deviceA, { items: [{ id: item.id, receipt: item.receipt }] });
  const rateCount = Number(rows("SELECT count FROM rate_limits WHERE bucket=?", "mailbox-access:" + route + ":" + deviceA)[0].count);
  const transaction = storage.transactionSync.bind(storage);
  storage.transactionSync = (operation) => {
    // Signature was checked while fresh, then storage access waited >5 min.
    t.mock.timers.setTime(initial + 301_000);
    return transaction(operation);
  };
  assert.equal(shard.mailbox.pull(read, route, initial).status, 400);
  assert.equal(shard.mailbox.ack(acknowledge, route, initial).status, 400);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 1);
  assert.equal(rows("SELECT envelope FROM mailbox_messages")[0].envelope, body.envelope);
  assert.equal(Number(rows("SELECT count FROM rate_limits WHERE bucket=?", "mailbox-access:" + route + ":" + deviceA)[0].count), rateCount);
  // A new, freshly signed-shaped request can still finish the same message.
  assert.equal(pull(shard).items[0].id, item.id);
  assert.equal(ack(shard, deviceA, [{ id: item.id, receipt: item.receipt }]).status, 204);
});

test("kill switches and expired registrations are rechecked before pull or ack", async (t) => {
  const initial = 1_700_000_000_000;
  t.mock.timers.enable({ apis: ["Date"], now: initial });
  for (const disabled of ["PUSH_ENABLED", "MAILBOX_ENABLED"]) {
    const { shard, storage, env, rows, register } = make(t);
    register();
    const body = putBody();
    assert.equal((await shard.mailbox.put(body, route, initial)).status, 201);
    const item = pull(shard).items[0];
    const read = accessBody(deviceA, { after: "-" });
    const acknowledge = accessBody(deviceA, { items: [{ id: item.id, receipt: item.receipt }] });
    const transaction = storage.transactionSync.bind(storage);
    storage.transactionSync = (operation) => { env[disabled] = "false"; return transaction(operation); };
    assert.equal(shard.mailbox.pull(read, route, initial).status, 503);
    assert.equal(shard.mailbox.ack(acknowledge, route, initial).status, 503);
    assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 1);
    assert.equal(rows("SELECT envelope FROM mailbox_messages")[0].envelope, body.envelope);
  }
  const { shard, storage, sql, rows, register } = make(t);
  register();
  const body = putBody();
  assert.equal((await shard.mailbox.put(body, route, initial)).status, 201);
  const item = pull(shard).items[0];
  const read = accessBody(deviceA, { after: "-" });
  const acknowledge = accessBody(deviceA, { items: [{ id: item.id, receipt: item.receipt }] });
  sql.exec("UPDATE registrations SET expires_at=?", initial + 1_000);
  const transaction = storage.transactionSync.bind(storage);
  storage.transactionSync = (operation) => { t.mock.timers.setTime(initial + 1_001); return transaction(operation); };
  assert.equal(shard.mailbox.pull(read, route, initial).status, 403);
  assert.equal(shard.mailbox.ack(acknowledge, route, initial).status, 403);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 1);
});

test("cursor allows skipping an unacknowledged item, survives acknowledgements and fails closed after expiry", async (t) => {
  const { shard, sql, register } = make(t);
  register();
  const bodies = Array.from({ length: 7 }, (_, i) => putBody(randomHex(16), fakeEnvelope(i + 1)));
  for (const body of bodies) assert.equal((await shard.mailbox.put(body, route, Date.now())).status, 201);
  const first = pull(shard);
  assert.equal(first.items.length, 4);
  assert.deepEqual(first.items.map((item) => item.id), bodies.slice(0, 4).map((body) => body.id));
  // The first item is intentionally left unacknowledged; it must not block later pages.
  assert.equal(ack(shard, deviceA, first.items.slice(1).map(({ id, receipt }) => ({ id, receipt }))).status, 204);
  const later = pull(shard, deviceA, first.next);
  assert.deepEqual(later.items.map((item) => item.id), bodies.slice(4).map((body) => body.id));
  const nextBody = putBody();
  await shard.mailbox.put(nextBody, route, Date.now());
  assert.equal(pull(shard, deviceA, later.next).items[0].id, nextBody.id);
  sql.exec("UPDATE mailbox_messages SET expires_at = ? WHERE route = ? AND message_id = ?", Date.now() - 1, route, first.next);
  assert.equal(pull(shard, deviceA, first.next).status, 409);
  assert.equal(pull(shard).items[0].id, first.items[0].id);
});

test("bounded quotas count tombstones and release bytes only after all acknowledgements", async (t) => {
  const { shard, storage, sql, rows, register } = make(t);
  register();
  shard.mailbox = new CiphertextMailbox(shard, { ...MAILBOX_LIMITS, routeRows: 2, routeBytes: 2000 });
  const first = putBody(), second = putBody(), third = putBody();
  assert.equal((await shard.mailbox.put(first, route, Date.now())).status, 201);
  assert.equal((await shard.mailbox.put(second, route, Date.now())).status, 201);
  const item = pull(shard).items.find((value) => value.id === first.id);
  assert.equal(ack(shard, deviceA, [{ id: item.id, receipt: item.receipt }]).status, 204);
  assert.equal(rows("SELECT bytes FROM mailbox_messages WHERE message_id=?", first.id)[0].bytes, 0);
  assert.equal((await shard.mailbox.put(third, route, Date.now())).status, 429, "tombstone occupies a row");
  sql.exec("UPDATE mailbox_messages SET expires_at = ? WHERE message_id = ?", Date.now() - 1, first.id);
  storage.transactionSync(() => shard.mailbox.cleanup(Date.now()));
  assert.equal((await shard.mailbox.put(third, route, Date.now())).status, 201);
  const tiny = { ...MAILBOX_LIMITS, routeBytes: fakeEnvelope().length - 1 };
  shard.mailbox = new CiphertextMailbox(shard, tiny);
  assert.equal((await shard.mailbox.put(putBody(), route, Date.now())).status, 429);
});

test("unknown routes cannot consume a public owner's budget; revoked device cannot pull or acknowledge", async (t) => {
  const { shard, sql, rows, register } = make(t);
  register();
  const body = putBody();
  for (let i = 0; i < 8; i++) assert.equal((await shard.mailbox.put(body, randomHex(32), Date.now())).status, 404);
  assert.equal(rows("SELECT count(*) AS n FROM rate_limits WHERE bucket LIKE 'mailbox-put-%'")[0].n, 0);
  assert.equal((await shard.mailbox.put(body, route, Date.now())).status, 201);
  const item = pull(shard).items[0];
  sql.exec("UPDATE registrations SET token = NULL WHERE route = ? AND device = ?", route, deviceA);
  assert.equal(pull(shard).status, 403);
  assert.equal(ack(shard, deviceA, [{ id: item.id, receipt: item.receipt }]).status, 403);
  assert.equal((await shard.mailbox.put(putBody(), route, Date.now())).status, 404);
  sql.exec("UPDATE registrations SET token = ? WHERE route = ? AND device = ?", token, route, deviceA);
  assert.equal(pull(shard).items[0].id, body.id, "refreshing a token does not silently delete old ciphertext");
  register(deviceB);
  assert.equal(pull(shard, deviceB).items.length, 0, "a later device was not a target at submission");
});

test("disabled mailbox refuses a pending put and still cleans expired storage in bounded batches", async (t) => {
  const { shard, env, sql, rows, register, getAlarm } = make(t);
  register();
  const earliestAlarmBase = Date.now();
  const pending = shard.mailbox.put(putBody(), route, earliestAlarmBase);
  env.MAILBOX_ENABLED = "false";
  assert.equal((await pending).status, 503);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 0);
  const now = Date.now();
  for (let i = 1; i <= 130; i++) {
    const id = i.toString(16).padStart(32, "0");
    sql.exec("INSERT INTO mailbox_messages(route,owner,message_id,digest,envelope,bytes,expires_at) VALUES (?,?,?,?,?,?,?)",
      route, owner, id, "aa".repeat(32), "cipher", 6, now - 1);
    sql.exec("INSERT INTO mailbox_targets(route,message_id,device,receipt) VALUES (?,?,?,?)", route, id, deviceA, randomHex(16));
  }
  await shard.alarm();
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 2);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 2);
  // A safety alarm from the pending put may already be a few milliseconds
  // earlier than the cleanup alarm; reschedule intentionally never delays it.
  assert.ok(getAlarm() >= earliestAlarmBase + 60_000 && getAlarm() <= Date.now() + 61_000);
  await shard.alarm();
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 0);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 0);
});
