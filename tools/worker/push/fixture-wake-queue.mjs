// TEST-ONLY workerd entry point. This file is never imported by worker.mjs or
// bundled by wrangler.toml. It accelerates a retry and observes completion.
// The tested alarm is dispatched by workerd, not by calling alarm() directly.
import worker, { PushShard } from "./worker.mjs";

export class TestPushShard extends PushShard {
  async fetch(request) {
    const path = new URL(request.url).pathname;
    if (path === "/test/pending") {
      const row = this.rows("SELECT count(*) AS count, MAX(generation) AS generation FROM pending_wakes")[0];
      return Response.json({ count: Number(row.count), generation: row.generation, alarm: await this.state.storage.getAlarm() });
    }
    if (path === "/test/prepare-restart") {
      this.state.storage.transactionSync(() => {
        this.sql.exec("UPDATE pending_wakes SET next_at = ?, lease_until = 0", Date.now() - 1);
        this.sql.exec("DELETE FROM rate_limits WHERE bucket LIKE 'wake-%'");
      });
      // Enough time to dispose and restart the runtime; the persisted alarm
      // remains due even if a slow runner starts it after the deadline.
      await this.state.storage.setAlarm(Date.now() + 5_000);
      return Response.json({ count: this.count("SELECT count(*) AS n FROM pending_wakes") });
    }
    if (path === "/test/rearm-persisted-alarm") {
      const saved = await this.state.storage.getAlarm();
      if (saved === null) return new Response(null, { status: 500 });
      // Miniflare preserves the deadline across restart but this version does
      // not automatically dispatch it after a full runtime restart. Re-arm it
      // without altering the queue to exercise the genuine alarm event.
      await this.state.storage.setAlarm(Math.max(saved + 1, Date.now() + 100));
      return Response.json({ saved });
    }
    return super.fetch(request);
  }

  async alarm() {
    await super.alarm();
    // Test-only fake outbound callback after the production alarm has settled.
    await fetch("https://test-alarm.invalid/completed", { method: "POST" });
  }
}

export default worker;
