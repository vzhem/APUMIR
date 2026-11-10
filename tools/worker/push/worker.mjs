// EXPERIMENTAL: a separate, disabled-by-default FCM wake relay. This is not a
// mailbox and is not wired to the existing application or deployed worker.
// See docs/REMOTE_PUSH_DESIGN.md before enabling it.
import { fcmConfigured, FcmV1 } from "./fcm.mjs";
import { rateIdentity, routeFor, shardFor, validRegistration, validWake, verifyRegistration } from "./protocol.mjs";
import { WakeQueue } from "./wake-queue.mjs";

const MAX_BODY_BYTES = 8192;
const REGISTER_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const TOMBSTONE_MS = 15 * 60 * 1000; // > maximum signed timestamp validity, even with clock skew
const ALARM_INTERVAL_MS = 12 * 60 * 60 * 1000;
const MAX_DEVICES_PER_ROUTE = 4;
const MAX_ROWS_PER_OWNER = 2048;
const MAX_ROWS_PER_SHARD = 25_000;
const MAX_RATE_ROWS = 20_000;

const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
});
const accepted = () => json({ accepted: true }, 202);

function serviceEnabled(env) {
  return env.PUSH_ENABLED === "true" && fcmConfigured(env) &&
    typeof env.PUSH_RATE_KEY === "string" && env.PUSH_RATE_KEY.length >= 32;
}

function configured(env) {
  return serviceEnabled(env) && env.PUSH_SHARDS;
}

/** Read incrementally; Content-Length is not trusted, and the cap is on bytes. */
async function readJson(request) {
  const type = request.headers.get("content-type")?.split(";")[0].trim().toLowerCase();
  if (type !== "application/json") throw new Error("content type");
  const length = request.headers.get("content-length");
  if (length && (!/^\d+$/.test(length) || Number(length) > MAX_BODY_BYTES)) throw new Error("body too large");
  if (!request.body) throw new Error("missing body");
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_BODY_BYTES) { await reader.cancel(); throw new Error("body too large"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.protocol !== "https:") return json({ error: "https required" }, 400);
    const path = url.pathname;
    if (path === "/health" && request.method === "GET") {
      return json({ ok: !!configured(env), service: "apu-push-wake", experimental: true }, configured(env) ? 200 : 503);
    }
    if (!configured(env)) return json({ error: "push unavailable" }, 503);
    if (request.method !== "POST" || !["/v1/registration", "/v1/wake"].includes(path)) {
      return json({ error: "not found" }, 404);
    }
    let body;
    try { body = await readJson(request); } catch (_) { return json({ error: "bad request" }, 400); }
    const now = Math.floor(Date.now() / 1000);
    if (path === "/v1/wake" ? !validWake(body) : !validRegistration(body, now)) {
      return json({ error: "bad request" }, 400);
    }
    // Cloudflare supplies this header; a missing value uses a shared fail-safe
    // bucket. It is HMACed before it leaves this worker and is never persisted.
    const identity = await rateIdentity(env.PUSH_RATE_KEY, request.headers.get("cf-connecting-ip"));
    const shard = await shardFor(body.owner);
    const id = env.PUSH_SHARDS.idFromName("apu-push-shard-v1:" + shard);
    const internal = new Request("https://push-internal.invalid" + path, {
      method: "POST", headers: { "content-type": "application/json", "x-apu-rate-id": identity },
      body: JSON.stringify(body),
    });
    try {
      return await env.PUSH_SHARDS.get(id).fetch(internal);
    } catch (_) {
      // Do not log request bodies, capabilities, tokens or upstream responses.
      console.error("Push shard request failed");
      return json({ error: "push unavailable" }, 503);
    }
  },
};

/** Fixed number of SQLite-backed Durable Objects, sharded by receiver key. */
export class PushShard {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.sql = state.storage.sql;
    this.provider = new FcmV1(env);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS registrations (
      route TEXT NOT NULL, owner TEXT NOT NULL, device TEXT NOT NULL,
      token TEXT, sequence INTEGER NOT NULL, expires_at INTEGER NOT NULL,
      PRIMARY KEY (route, device)
    )`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS push_owner ON registrations(owner)");
    this.sql.exec("CREATE INDEX IF NOT EXISTS push_expiry ON registrations(expires_at)");
    this.sql.exec(`CREATE TABLE IF NOT EXISTS rate_limits (
      bucket TEXT PRIMARY KEY, count INTEGER NOT NULL, expires_at INTEGER NOT NULL
    )`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS push_rate_expiry ON rate_limits(expires_at)");
    this.wakes = new WakeQueue(this);
  }

  rows(query, ...args) { return this.sql.exec(query, ...args).toArray(); }
  count(query, ...args) { return Number(this.rows(query, ...args)[0]?.n || 0); }

  // One synchronous transaction prevents overlapping requests from bypassing
  // quotas or replay protection. Bounded expiry work avoids unbounded scans.
  cleanup(now) {
    this.sql.exec("DELETE FROM registrations WHERE rowid IN (SELECT rowid FROM registrations WHERE expires_at <= ? LIMIT 128)", now);
    this.sql.exec("DELETE FROM rate_limits WHERE rowid IN (SELECT rowid FROM rate_limits WHERE expires_at <= ? LIMIT 128)", now);
    this.wakes.cleanup(now);
  }

  consume(bucket, limit, duration, now) {
    const row = this.rows("SELECT count, expires_at FROM rate_limits WHERE bucket = ?", bucket)[0];
    if (!row || Number(row.expires_at) <= now) {
      if (!row && this.count("SELECT count(*) AS n FROM rate_limits") >= MAX_RATE_ROWS) return false;
      this.sql.exec("INSERT OR REPLACE INTO rate_limits(bucket, count, expires_at) VALUES (?, 1, ?)", bucket, now + duration);
      return true;
    }
    if (Number(row.count) >= limit) return false;
    this.sql.exec("UPDATE rate_limits SET count = count + 1 WHERE bucket = ?", bucket);
    return true;
  }

  async ensureAlarm() {
    await this.wakes.locked(async () => {
      if ((await this.state.storage.getAlarm()) === null) await this.state.storage.setAlarm(Date.now() + ALARM_INTERVAL_MS);
    });
  }

  async alarm() {
    const now = Date.now();
    if (!serviceEnabled(this.env)) {
      // The kill switch also applies to previously queued alarms. Keep bounded
      // expiry housekeeping, but do not send or spin on an already-due wake.
      this.state.storage.transactionSync(() => this.cleanup(now));
      await this.wakes.reschedule({ suspended: true });
      return;
    }
    // Install a crash-recovery alarm before touching pending rows. Each
    // provider attempt releases its lease transactionally when it finishes.
    await this.wakes.mutate(now, (current) => {
      this.cleanup(current);
      this.wakes.cleanupDueOrphans(current);
    });
    await this.wakes.runDue(Date.now());
    await this.wakes.reschedule();
  }

  async fetch(request) {
    if (!serviceEnabled(this.env)) return json({ error: "push unavailable" }, 503);
    const path = new URL(request.url).pathname;
    if (request.method !== "POST" || !["/v1/registration", "/v1/wake"].includes(path)) return json({ error: "not found" }, 404);
    let body;
    try { body = await readJson(request); } catch (_) { return json({ error: "bad request" }, 400); }
    const now = Date.now();
    if (path === "/v1/wake" ? !validWake(body) : !validRegistration(body, Math.floor(now / 1000))) {
      return json({ error: "bad request" }, 400);
    }
    const rateId = request.headers.get("x-apu-rate-id") || "";
    if (!/^[0-9a-f]{64}$/.test(rateId)) return json({ error: "bad request" }, 400);
    const allowed = this.state.storage.transactionSync(() => {
      this.cleanup(now);
      return this.consume("global", 1200, 60_000, now) &&
        this.consume("ip:" + rateId, 240, 60 * 60_000, now);
    });
    await this.ensureAlarm();
    if (!allowed) return json({ error: "rate limited" }, 429);
    const route = await routeFor(body.owner, body.capability);
    if (path === "/v1/registration") return this.register(body, route, now);
    return this.wake(body.owner, route, now);
  }

  async register(body, route, now) {
    if (!(await verifyRegistration(body))) return json({ error: "unauthorised" }, 403);
    const status = this.state.storage.transactionSync(() => {
      const previous = this.rows("SELECT owner, sequence FROM registrations WHERE route = ? AND device = ?", route, body.device)[0];
      if (previous && (previous.owner !== body.owner || Number(previous.sequence) >= body.sequence)) return 409;
      // Replayed signed operations must not exhaust a legitimate owner's quota.
      if (!this.consume("reg-owner:" + body.owner, 4096, 24 * 60 * 60_000, now)) return 429;
      if (!previous) {
        if (this.count("SELECT count(*) AS n FROM registrations WHERE route = ?", route) >= MAX_DEVICES_PER_ROUTE ||
            this.count("SELECT count(*) AS n FROM registrations WHERE owner = ?", body.owner) >= MAX_ROWS_PER_OWNER ||
            this.count("SELECT count(*) AS n FROM registrations") >= MAX_ROWS_PER_SHARD) return 429;
      }
      const expires = now + (body.action === "register" ? REGISTER_TTL_MS : TOMBSTONE_MS);
      const token = body.action === "register" ? body.token : null;
      this.sql.exec(`INSERT OR REPLACE INTO registrations(route, owner, device, token, sequence, expires_at)
        VALUES (?, ?, ?, ?, ?, ?)`, route, body.owner, body.device, token, body.sequence, expires);
      if (body.action === "revoke") this.wakes.discard(route, body.device);
      return 204;
    });
    return status === 204 ? new Response(null, { status: 204, headers: { "cache-control": "no-store" } }) : json({ error: status === 409 ? "stale operation" : "rate limited" }, status);
  }

  async wake(owner, route, now) {
    await this.wakes.enqueue(owner, route, now);
    // Same response whether the route exists, is rate limited, or FCM failed.
    // A signal acceptance is not evidence that a message reached a device.
    return accepted();
  }
}
