// Durable, bounded signal attempts. This queue contains opaque route/device
// identifiers, never message text, capabilities, FCM tokens or request bodies.
// A provider acceptance remains only a signal, not a message-delivery receipt.

const PENDING_TTL_MS = 60 * 60_000;
const LEASE_MS = 60_000;
const SAFETY_ALARM_MS = 60_000;
const MAX_PENDING_ROWS = 25_000;
const MAX_ROUTES_PER_ALARM = 4;
const MAX_DEVICES = 4;
const MAX_BACKOFF_MS = 5 * 60_000;

export class WakeQueue {
  constructor(shard) {
    this.shard = shard;
    this.storage = shard.state.storage;
    this.sql = shard.sql;
    this.lock = Promise.resolve();
    this.sql.exec(`CREATE TABLE IF NOT EXISTS pending_wakes (
      route TEXT NOT NULL, owner TEXT NOT NULL, device TEXT NOT NULL,
      generation INTEGER NOT NULL, attempts INTEGER NOT NULL,
      next_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
      claim_id TEXT, lease_until INTEGER NOT NULL,
      PRIMARY KEY (route, device)
    )`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS push_pending_due ON pending_wakes(next_at)");
    this.sql.exec("CREATE INDEX IF NOT EXISTS push_pending_expiry ON pending_wakes(expires_at)");
  }

  rows(query, ...args) { return this.sql.exec(query, ...args).toArray(); }

  // Serialize alarm decisions with pending-row mutations. No network call is
  // performed under this lock or within a SQLite transaction.
  async locked(operation) {
    const before = this.lock;
    let release;
    this.lock = new Promise((resolve) => { release = resolve; });
    await before;
    try { return await operation(); } finally { release(); }
  }

  async safetyAlarm(now) {
    const alarm = await this.storage.getAlarm();
    const deadline = now + SAFETY_ALARM_MS;
    if (alarm === null || alarm > deadline) await this.storage.setAlarm(deadline);
  }

  // First persist a safety alarm, then commit queue changes. If the object
  // crashes in the small interval before a precise reschedule, a recovery
  // alarm is still scheduled (the platform can delay its actual execution).
  async mutate(requestedAt, operation) {
    return this.locked(async () => {
      // Time may have elapsed while another request held the scheduling lock.
      const now = Math.max(requestedAt, Date.now());
      await this.safetyAlarm(now);
      return this.storage.transactionSync(() => operation(now));
    });
  }

  // Called inside the shard's registration transaction when an owner revokes.
  discard(route, device) {
    this.sql.exec("DELETE FROM pending_wakes WHERE route = ? AND device = ?", route, device);
  }

  // Indexed expiry work is bounded even on the request path. Orphan checks
  // run only for due work at alarm time, avoiding a full queue scan on every
  // incoming request. A revoked token is never selected for a new send.
  cleanup(now) {
    this.sql.exec(`DELETE FROM pending_wakes WHERE rowid IN (
      SELECT rowid FROM pending_wakes WHERE expires_at <= ? LIMIT 128
    )`, now);
  }

  cleanupDueOrphans(now) {
    this.sql.exec(`DELETE FROM pending_wakes WHERE rowid IN (
      SELECT p.rowid FROM pending_wakes p
      WHERE p.next_at <= ? AND p.lease_until <= ? AND NOT EXISTS (
        SELECT 1 FROM registrations r WHERE r.route = p.route AND r.owner = p.owner
          AND r.device = p.device AND r.token IS NOT NULL AND r.expires_at > ?
      ) LIMIT 128
    )`, now, now, now);
  }

  async enqueue(owner, route, requestedAt) {
    await this.mutate(requestedAt, (now) => {
      // An expired/in-flight entry must not absorb a genuinely new request.
      this.sql.exec("DELETE FROM pending_wakes WHERE route = ? AND expires_at <= ?", route, now);
      const targets = this.rows(`SELECT device FROM registrations
        WHERE route = ? AND owner = ? AND token IS NOT NULL AND expires_at > ? LIMIT ?`,
      route, owner, now, MAX_DEVICES);
      for (const target of targets) {
        const existing = this.rows("SELECT 1 FROM pending_wakes WHERE route = ? AND device = ?", route, target.device).length;
        if (!existing && this.shard.count("SELECT count(*) AS n FROM pending_wakes") >= MAX_PENDING_ROWS) continue;
        // Multiple wakes coalesce, including one arriving while FCM is in
        // flight. Preserve retry deadlines: a spammer cannot defeat backoff.
        this.sql.exec(`INSERT INTO pending_wakes
          (route, owner, device, generation, attempts, next_at, expires_at, claim_id, lease_until)
          VALUES (?, ?, ?, 1, 0, ?, ?, NULL, 0)
          ON CONFLICT(route, device) DO UPDATE SET
            generation = generation + 1, expires_at = MAX(expires_at, excluded.expires_at)`,
        route, owner, target.device, now, now + PENDING_TTL_MS);
      }
    });
    // Do not wait for OAuth/FCM on the HTTP path. The committed row and alarm
    // survive an interrupted request; only the alarm performs provider I/O.
    await this.reschedule();
  }

  rateRetryAt(bucket, limit, now) {
    const row = this.rows("SELECT count, expires_at FROM rate_limits WHERE bucket = ?", bucket)[0];
    return row && Number(row.expires_at) > now && Number(row.count) >= limit ? Number(row.expires_at) : now;
  }

  async claim(owner, route, requestedAt) {
    const claimId = crypto.randomUUID();
    return this.mutate(requestedAt, (now) => {
      const targets = this.rows(`SELECT p.device, p.generation, p.attempts, r.token, r.sequence
        FROM pending_wakes p JOIN registrations r
          ON r.route = p.route AND r.owner = p.owner AND r.device = p.device
        WHERE p.route = ? AND p.owner = ? AND p.next_at <= ? AND p.lease_until <= ?
          AND p.expires_at > ? AND r.token IS NOT NULL AND r.expires_at > ? LIMIT ?`,
      route, owner, now, now, now, now, MAX_DEVICES);
      if (!targets.length) return [];
      const routeBucket = "wake-route:" + route;
      const ownerBucket = "wake-owner:" + owner;
      let retryAt = Math.max(this.rateRetryAt(routeBucket, 1, now), this.rateRetryAt(ownerBucket, 12, now));
      if (retryAt <= now && (!this.shard.consume(routeBucket, 1, 30_000, now) ||
          !this.shard.consume(ownerBucket, 12, 60_000, now))) {
        // Quota-table exhaustion fails closed, rather than creating unbounded
        // rate state or dropping the request as though it was delivered.
        retryAt = now + SAFETY_ALARM_MS;
      }
      if (retryAt > now) {
        this.sql.exec(`UPDATE pending_wakes SET next_at = MAX(next_at, ?), claim_id = NULL, lease_until = 0
          WHERE route = ? AND owner = ? AND next_at <= ? AND lease_until <= ?`, retryAt, route, owner, now, now);
        return [];
      }
      for (const target of targets) {
        this.sql.exec("UPDATE pending_wakes SET claim_id = ?, lease_until = ? WHERE route = ? AND device = ?",
          claimId, now + LEASE_MS, route, target.device);
        target.claimId = claimId;
      }
      return targets;
    });
  }

  async finish(owner, route, target, outcome) {
    await this.mutate(Date.now(), (now) => {
      const pending = this.rows(`SELECT generation, attempts, claim_id FROM pending_wakes
        WHERE route = ? AND owner = ? AND device = ?`, route, owner, target.device)[0];
      if (!pending || pending.claim_id !== target.claimId) return; // a newer lease/revoke wins
      const current = this.rows(`SELECT token, sequence, expires_at FROM registrations
        WHERE route = ? AND owner = ? AND device = ?`, route, owner, target.device)[0];
      const remove = () => this.discard(route, target.device);
      if (!current || !current.token || Number(current.expires_at) <= now) return remove();
      if (Number(current.sequence) !== Number(target.sequence) || current.token !== target.token) {
        // Token rotation while a request was in flight: the old result is not
        // evidence about the new installation token. Re-arm using current data.
        this.sql.exec(`UPDATE pending_wakes SET attempts = 0, next_at = ?, claim_id = NULL, lease_until = 0
          WHERE route = ? AND device = ?`, now, route, target.device);
        return;
      }
      if (outcome === "unregistered") {
        this.sql.exec(`UPDATE registrations SET token = NULL, expires_at = ?
          WHERE route = ? AND device = ? AND sequence = ? AND token = ?`,
        now + 15 * 60_000, route, target.device, target.sequence, target.token);
        return remove();
      }
      if (outcome === "sent" && Number(pending.generation) === Number(target.generation)) return remove();
      if (outcome === "sent") {
        // Another message arrived during the send. Keep one coalesced wake,
        // subject to the same route/owner budgets as every other attempt.
        this.sql.exec(`UPDATE pending_wakes SET attempts = 0, next_at = ?, claim_id = NULL, lease_until = 0
          WHERE route = ? AND device = ?`, now, route, target.device);
        return;
      }
      const attempts = Math.min(Number(pending.attempts) + 1, 16);
      const delay = Math.min(15_000 * 2 ** Math.min(attempts - 1, 8), MAX_BACKOFF_MS);
      // Stable per-route jitter spreads a widespread provider outage without
      // including a token, address, or capability in stored state or logs.
      const jitter = parseInt(route.slice(0, 4), 16) % 3_000;
      this.sql.exec(`UPDATE pending_wakes SET attempts = ?, next_at = ?, claim_id = NULL, lease_until = 0
        WHERE route = ? AND device = ?`, attempts, now + delay + jitter, route, target.device);
    });
  }

  async runRoute(owner, route, now) {
    const targets = await this.claim(owner, route, now);
    await Promise.all(targets.map(async (target) => {
      let result = "retry";
      try { result = await this.shard.provider.send(target.token, route); } catch (_) {
        // A transient provider exception is retried without logging secrets.
      }
      await this.finish(owner, route, target, result);
    }));
  }

  async runDue(now) {
    const due = this.rows(`SELECT DISTINCT p.owner, p.route FROM pending_wakes p
      JOIN registrations r ON r.route = p.route AND r.owner = p.owner AND r.device = p.device
      WHERE p.next_at <= ? AND p.lease_until <= ? AND p.expires_at > ?
        AND r.token IS NOT NULL AND r.expires_at > ? ORDER BY p.next_at, p.route LIMIT ?`,
    now, now, now, now, MAX_ROUTES_PER_ALARM);
    await Promise.all(due.map(({ owner, route }) => this.runRoute(owner, route, now)));
  }

  async reschedule({ suspended = false } = {}) {
    await this.locked(async () => {
      const now = Date.now();
      const query = suspended ? "SELECT MIN(expires_at) AS due FROM pending_wakes" :
        "SELECT MIN(MIN(expires_at, MAX(next_at, lease_until))) AS due FROM pending_wakes";
      const row = this.rows(query)[0];
      const pendingDue = row?.due == null ? null : Number(row.due);
      const expired = this.shard.count("SELECT count(*) AS n FROM registrations WHERE expires_at <= ?", now) ||
        this.shard.count("SELECT count(*) AS n FROM rate_limits WHERE expires_at <= ?", now);
      const any = this.shard.count("SELECT count(*) AS n FROM registrations") ||
        this.shard.count("SELECT count(*) AS n FROM rate_limits");
      let deadline = expired ? now + 60_000 : any ? now + 12 * 60 * 60_000 : null;
      if (pendingDue !== null) deadline = deadline === null ? pendingDue : Math.min(deadline, pendingDue);
      if (deadline === null) {
        await this.storage.deleteAlarm();
      } else {
        // Never postpone an earlier alarm: a stream of new requests must not
        // keep pushing an already-due send one second into the future.
        const proposed = Math.max(deadline, now + 1_000);
        const existing = await this.storage.getAlarm();
        if (existing === null || existing > proposed) await this.storage.setAlarm(proposed);
      }
    });
  }
}
