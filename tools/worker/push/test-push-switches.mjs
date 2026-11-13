// Extra invariants for the still-disabled prototype, found while preparing the
// Android vertical slice. Fake provider, in-memory SQLite stand-in: no
// Cloudflare, Google or Android behaviour is exercised, and nothing here shows
// that push works.

import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { PushShard } from "./worker.mjs";
import { CiphertextMailbox } from "./mailbox.mjs";
import { fakeEnvelope, mailboxPut, randomHex } from "./mailbox-test-fixture.mjs";

const owner = "12".repeat(32);
const route = "34".repeat(32);
const capability = "98".repeat(32);
const device = "56".repeat(16);

function make(t, overrides = {}) {
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
      try { const value = fn(); db.exec("COMMIT"); return value; } catch (error) { db.exec("ROLLBACK"); throw error; }
    },
    async getAlarm() { return alarm; },
    async setAlarm(value) { alarm = value; },
    async deleteAlarm() { alarm = null; },
  };
  const env = {
    PUSH_ENABLED: "true", MAILBOX_ENABLED: "true", PUSH_RATE_KEY: "test-rate-key-".repeat(4),
    FCM_PROJECT_ID: "test-project", FCM_CLIENT_EMAIL: "test@example.invalid",
    FCM_PRIVATE_KEY: "-----BEGIN PRIVATE KEY-----test-only", ...overrides,
  };
  const shard = new PushShard({ storage }, env);
  const rows = (query, ...params) => sql.exec(query, ...params).toArray();
  const register = (dev = device) => sql.exec(
    "INSERT INTO registrations(route,owner,device,token,sequence,expires_at) VALUES (?,?,?,?,?,?)",
    route, owner, dev, "fcm-test-token-abcdefghijklmnop", 1, Date.now() + 30 * 24 * 60 * 60_000,
  );
  return { db, sql, storage, env, shard, rows, register, getAlarm: () => alarm };
}

const pullBody = () => ({ owner, capability, device, issuedAt: Math.floor(Date.now() / 1000), after: "-" });

test("a mailbox switch flipped during signature verification cannot return a ciphertext page", async (t) => {
  const { shard, env, rows, register } = make(t);
  register();
  assert.equal((await shard.mailbox.put(mailboxPut(owner, capability, fakeEnvelope(5), Date.now() + 60_000),
    route, Date.now())).status, 201);
  // The outer entry check ran while the switch was still on; the operator
  // turns it off before Ed25519 verification finishes. The storage read must
  // re-check the switch instead of returning a page from its earlier decision.
  const original = CiphertextMailbox.prototype.pull;
  CiphertextMailbox.prototype.pull = function (...args) {
    env.MAILBOX_ENABLED = "false";
    return original.apply(this, args);
  };
  t.after(() => { CiphertextMailbox.prototype.pull = original; });
  assert.notEqual(shard.mailbox.pull(pullBody(), route, Date.now()).status, 200);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 1, "ciphertext is retained, not handed out");
});

test("the master switch stops every remote surface, including ciphertext storage", async (t) => {
  const { shard, env, rows, register } = make(t, { MAILBOX_ENABLED: "true" });
  register();
  const body = mailboxPut(owner, capability, fakeEnvelope(2), Date.now() + 60_000);
  env.PUSH_ENABLED = "false";
  const request = new Request("https://push-internal.invalid/v1/mailbox/put", {
    method: "POST", headers: { "content-type": "application/json", "x-apu-rate-id": "ab".repeat(32) },
    body: JSON.stringify(body),
  });
  assert.notEqual((await shard.fetch(request)).status, 201);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 0);
});

test("bounded housekeeping still removes expired ciphertext while the mailbox switch is off", async (t) => {
  const { shard, sql, env, rows, register } = make(t);
  register();
  sql.exec("INSERT INTO mailbox_messages(route,owner,message_id,digest,envelope,bytes,expires_at) VALUES (?,?,?,?,?,?,?)",
    route, owner, "11".repeat(16), "aa".repeat(32), "cipher", 6, Date.now() - 1);
  sql.exec("INSERT INTO mailbox_targets(route,message_id,device,receipt) VALUES (?,?,?,?)",
    route, "11".repeat(16), device, randomHex(16));
  env.MAILBOX_ENABLED = "false";
  await shard.alarm();
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_messages")[0].n, 0);
  assert.equal(rows("SELECT count(*) AS n FROM mailbox_targets")[0].n, 0);
});
