// Isolated experimental ciphertext mailbox; never stores plaintext, tokens or
// route capabilities intentionally. Inputs are only structurally checked, not
// cryptographically opened here. All quotas/retention are provisional and the
// HTTP interface remains disabled until explicitly configured and reviewed.
import { hexFromBytes, sha256 } from "./protocol.mjs";

export const MAILBOX_LIMITS = Object.freeze({
  ttlMs: 7 * 24 * 60 * 60_000,
  routeRows: 4096, ownerRows: 12_000, shardRows: 50_000,
  routeBytes: 2 * 1024 * 1024, ownerBytes: 16 * 1024 * 1024, shardBytes: 64 * 1024 * 1024,
  pullBatch: 4,
});

class WakeQueueFull extends Error {}

function randomReceipt() {
  const bytes = new Uint8Array(16);
  do { crypto.getRandomValues(bytes); } while (bytes.every((value) => value === 0));
  return hexFromBytes(bytes);
}

export class CiphertextMailbox {
  constructor(shard, limits = MAILBOX_LIMITS) {
    this.shard = shard;
    this.sql = shard.sql;
    this.storage = shard.state.storage;
    this.limits = limits;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS mailbox_messages (
      route TEXT NOT NULL, owner TEXT NOT NULL, message_id TEXT NOT NULL,
      digest TEXT NOT NULL, envelope TEXT, bytes INTEGER NOT NULL, expires_at INTEGER NOT NULL,
      PRIMARY KEY (route, message_id)
    )`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS mailbox_owner ON mailbox_messages(owner)");
    this.sql.exec("CREATE INDEX IF NOT EXISTS mailbox_expiry ON mailbox_messages(expires_at)");
    this.sql.exec(`CREATE TABLE IF NOT EXISTS mailbox_targets (
      route TEXT NOT NULL, message_id TEXT NOT NULL, device TEXT NOT NULL, receipt TEXT NOT NULL,
      PRIMARY KEY (route, message_id, device)
    )`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS mailbox_device ON mailbox_targets(route, device, message_id)");
  }

  rows(query, ...args) { return this.sql.exec(query, ...args).toArray(); }

  // Both deletions run in the caller's transaction, so no orphan can survive a
  // crash between target and message cleanup. Work stays bounded per request.
  cleanup(now) {
    this.sql.exec(`DELETE FROM mailbox_targets WHERE (route, message_id) IN (
      SELECT route, message_id FROM mailbox_messages WHERE expires_at <= ? ORDER BY expires_at, rowid LIMIT 128
    )`, now);
    this.sql.exec(`DELETE FROM mailbox_messages WHERE rowid IN (
      SELECT rowid FROM mailbox_messages WHERE expires_at <= ? ORDER BY expires_at, rowid LIMIT 128
    )`, now);
  }

  nextExpiry() {
    const row = this.rows("SELECT MIN(expires_at) AS due FROM mailbox_messages")[0];
    return row?.due == null ? null : Number(row.due);
  }

  active(owner, route, device, now) {
    return this.rows(`SELECT 1 FROM registrations
      WHERE route = ? AND owner = ? AND device = ? AND token IS NOT NULL AND expires_at > ?`,
    route, owner, device, now).length > 0;
  }

  targets(owner, route, now) {
    return this.rows(`SELECT device FROM registrations
      WHERE route = ? AND owner = ? AND token IS NOT NULL AND expires_at > ? ORDER BY device LIMIT 4`,
    route, owner, now).map((row) => row.device);
  }

  // Tombstones count toward the row limits until their original expiry; this
  // prevents a late retry from recreating an already-acknowledged message.
  withinQuota(owner, route, bytes) {
    const sums = (where, value) => this.rows(`SELECT count(*) AS n, COALESCE(SUM(bytes), 0) AS b
      FROM mailbox_messages ${where}`, ...(value === undefined ? [] : [value]))[0];
    const perRoute = sums("WHERE route = ?", route);
    const perOwner = sums("WHERE owner = ?", owner);
    const perShard = sums("", undefined);
    return Number(perRoute.n) < this.limits.routeRows && Number(perOwner.n) < this.limits.ownerRows &&
      Number(perShard.n) < this.limits.shardRows && Number(perRoute.b) + bytes <= this.limits.routeBytes &&
      Number(perOwner.b) + bytes <= this.limits.ownerBytes && Number(perShard.b) + bytes <= this.limits.shardBytes;
  }

  /** Store and enqueue wakes in one SQLite transaction, without provider I/O. */
  async put(body, route, requestedAt) {
    const digest = hexFromBytes(await sha256(new TextEncoder().encode(body.envelope)));
    let result;
    try {
      result = await this.shard.wakes.mutate(requestedAt, (now) => {
        if (!this.shard.mailboxEnabled()) return { status: 503 };
        let old = this.rows(`SELECT owner, digest, envelope, expires_at FROM mailbox_messages
          WHERE route = ? AND message_id = ?`, route, body.id)[0];
        if (old && Number(old.expires_at) <= now) {
          this.sql.exec("DELETE FROM mailbox_targets WHERE route = ? AND message_id = ?", route, body.id);
          this.sql.exec("DELETE FROM mailbox_messages WHERE route = ? AND message_id = ?", route, body.id);
          old = null;
        }
        const targets = this.targets(body.owner, route, now);
        // Knowing a public owner key and guessing unknown capabilities must not
        // consume that owner's shared budget or create per-route rate rows.
        if (!old && !targets.length) return { status: 404 };
        if (old && (old.owner !== body.owner || old.digest !== digest || old.envelope !== null && old.envelope !== body.envelope)) {
          return { status: 409 };
        }
        if (!this.shard.consume("mailbox-put-route:" + route, 120, 60 * 60_000, now) ||
            !this.shard.consume("mailbox-put-owner:" + body.owner, 1200, 60 * 60_000, now)) return { status: 429 };
        if (old) {
          if (old.envelope === null) return { status: 200, state: "handled", expiresAt: Number(old.expires_at), wakeQueued: false };
          const remaining = this.rows("SELECT device FROM mailbox_targets WHERE route = ? AND message_id = ?", route, body.id)
            .map((row) => row.device);
          if (!remaining.length) return { status: 503 }; // inconsistent row, do not claim success
          const wake = this.shard.wakes.enqueueInTransaction(body.owner, route, now, remaining);
          if (wake.queued !== wake.targets) throw new WakeQueueFull();
          return { status: 200, state: "stored", expiresAt: Number(old.expires_at), wakeQueued: wake.queued > 0 };
        }
        // APUSEAL1 fields are validated ASCII, so characters equal UTF-8 bytes.
        const bytes = body.envelope.length;
        if (!this.withinQuota(body.owner, route, bytes)) return { status: 429 };
        const expiresAt = now + this.limits.ttlMs;
        this.sql.exec(`INSERT INTO mailbox_messages(route, owner, message_id, digest, envelope, bytes, expires_at)
          VALUES (?, ?, ?, ?, ?, ?, ?)`, route, body.owner, body.id, digest, body.envelope, bytes, expiresAt);
        for (const device of targets) {
          this.sql.exec("INSERT INTO mailbox_targets(route, message_id, device, receipt) VALUES (?, ?, ?, ?)",
            route, body.id, device, randomReceipt());
        }
        const wake = this.shard.wakes.enqueueInTransaction(body.owner, route, now, targets);
        if (wake.targets !== targets.length || wake.queued !== wake.targets) throw new WakeQueueFull();
        return { status: 201, state: "stored", expiresAt, wakeQueued: true };
      });
    } catch (error) {
      // Queue exhaustion rolls back the entire transaction, including ciphertext
      // and all targets. Unexpected failures propagate without exposing inputs.
      if (error instanceof WakeQueueFull) return { status: 503 };
      throw error;
    }
    await this.shard.wakes.reschedule();
    return result;
  }

  /** Owner signature is checked by the shard before this transactional read. */
  pull(body, route, now) {
    return this.storage.transactionSync(() => {
      if (!this.active(body.owner, route, body.device, now)) return { status: 403 };
      if (!this.shard.consume("mailbox-access:" + route + ":" + body.device, 600, 60 * 60_000, now) ||
          !this.shard.consume("mailbox-read-owner:" + body.owner, 2400, 60 * 60_000, now)) return { status: 429 };
      let after = 0;
      if (body.after !== "-") {
        const cursor = this.rows(`SELECT rowid AS seq FROM mailbox_messages
          WHERE route = ? AND owner = ? AND message_id = ? AND expires_at > ?`, route, body.owner, body.after, now)[0];
        if (!cursor) return { status: 409 };
        after = Number(cursor.seq);
      }
      const rows = this.rows(`SELECT m.message_id AS id, m.envelope, m.expires_at AS expiresAt, t.receipt
        FROM mailbox_messages m JOIN mailbox_targets t ON t.route = m.route AND t.message_id = m.message_id
        WHERE m.route = ? AND m.owner = ? AND t.device = ? AND m.rowid > ? AND m.expires_at > ? AND m.envelope IS NOT NULL
        ORDER BY m.rowid LIMIT ?`, route, body.owner, body.device, after, now, this.limits.pullBatch);
      const items = rows.map((row) => ({ id: row.id, envelope: row.envelope, expiresAt: Number(row.expiresAt), receipt: row.receipt }));
      return { status: 200, items, next: items.length ? items[items.length - 1].id : null };
    });
  }

  /** Idempotent and incarnation-bound; a stale receipt cannot remove a new row. */
  ack(body, route, now) {
    return this.storage.transactionSync(() => {
      if (!this.active(body.owner, route, body.device, now)) return { status: 403 };
      if (!this.shard.consume("mailbox-access:" + route + ":" + body.device, 600, 60 * 60_000, now) ||
          !this.shard.consume("mailbox-read-owner:" + body.owner, 2400, 60 * 60_000, now)) return { status: 429 };
      for (const item of body.items) {
        this.sql.exec(`DELETE FROM mailbox_targets WHERE route = ? AND device = ? AND message_id = ? AND receipt = ?
          AND EXISTS (SELECT 1 FROM mailbox_messages m WHERE m.route = mailbox_targets.route
            AND m.message_id = mailbox_targets.message_id AND m.owner = ? AND m.expires_at > ?)`,
        route, body.device, item.id, item.receipt, body.owner, now);
        // Retain only the original digest and expiry as an idempotency tombstone.
        // Other registered devices must each acknowledge their own copy first.
        this.sql.exec(`UPDATE mailbox_messages SET envelope = NULL, bytes = 0
          WHERE route = ? AND owner = ? AND message_id = ? AND expires_at > ? AND NOT EXISTS
            (SELECT 1 FROM mailbox_targets t WHERE t.route = mailbox_messages.route AND t.message_id = mailbox_messages.message_id)`,
        route, body.owner, item.id, now);
      }
      return { status: 204 };
    });
  }
}
