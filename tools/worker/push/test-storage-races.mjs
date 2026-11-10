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
  };
  const shard = new PushShard({ storage }, {});
  return { db, sql, shard, getAlarm: () => alarm };
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

test("UNREGISTERED retires only the old token and preserves a replay tombstone", async () => {
  const { db, sql, shard } = makeShard();
  const now = Date.now();
  insert(sql, now);
  shard.provider = { send: async () => "unregistered" };
  const response = await shard.wake(owner, route, now);
  assert.equal(response.status, 202);
  assert.equal(row(sql).token, null);
  assert.equal(row(sql).sequence, 1);
  assert.ok(row(sql).expires_at >= now + 15 * 60_000);
  db.close();
});

test("late UNREGISTERED response cannot delete a token rotated while FCM was in flight", async () => {
  const { db, sql, shard } = makeShard();
  const now = Date.now();
  insert(sql, now);
  let started;
  const entered = new Promise((resolve) => { started = resolve; });
  let complete;
  const pending = new Promise((resolve) => { complete = resolve; });
  shard.provider = { send: async () => { started(); return pending; } };
  const wake = shard.wake(owner, route, now);
  await entered;
  sql.exec("UPDATE registrations SET token=?, sequence=2 WHERE route=? AND device=?", newToken, route, device);
  complete("unregistered");
  assert.equal((await wake).status, 202);
  assert.equal(row(sql).token, newToken);
  assert.equal(row(sql).sequence, 2);
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
