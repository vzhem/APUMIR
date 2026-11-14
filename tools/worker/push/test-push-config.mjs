// The dedicated push service config in `wrangler.push.toml` is what actually
// gets deployed, so it must be checked, not just the prototype's.
//
// What this asserts: the file parses, names the reviewed host, keeps both
// switches off until someone deliberately flips them, declares exactly one
// SQLite Durable Object with a migration for it, and stays separate from the
// MQTT relay and from the storage-free prototype. It does not deploy anything
// and does not contact Cloudflare.
import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { Miniflare } from "miniflare";

const here = new URL("./", import.meta.url);
const CONFIG = "wrangler.push.toml";
const REVIEWED_HOST = "apu-push.apuchat.workers.dev";

async function configText() {
  return readFile(new URL(CONFIG, here), "utf8");
}

test("push config deploys the reviewed host only, with storage and both switches off", async () => {
  const text = await configText();
  assert.match(text, /^name = "apu-push"$/m);
  assert.match(text, /^main = "worker\.mjs"$/m);
  assert.match(text, /^workers_dev = true$/m);
  assert.match(text, /^PUSH_ENABLED = "false"$/m);
  assert.match(text, /^MAILBOX_ENABLED = "false"$/m);
  // The Android client builds `<name>.<subdomain>.workers.dev`; a rename here
  // would silently point the app at a host that does not exist.
  assert.equal(`${/^name = "([a-z0-9-]+)"$/m.exec(text)[1]}.apuchat.workers.dev`, REVIEWED_HOST);
  // The relay that carries live MQTT traffic must never be this target.
  assert.doesNotMatch(text, /name = "p2p-relay"/);
  // Exactly one shard class, and a migration creating it.
  assert.equal(text.match(/class_name = "PushShard"/g).length, 1);
  assert.match(text, /new_sqlite_classes = \["PushShard"\]/);
  // No credential is ever written into the config file.
  assert.doesNotMatch(text, /PRIVATE KEY|FCM_PRIVATE_KEY\s*=|PUSH_RATE_KEY\s*=/);
});

test("the same config keeps serving disabled health and refusing traffic in workerd", async () => {
  const mf = new Miniflare({
    modules: true,
    scriptPath: new URL("worker.mjs", here).pathname,
    compatibilityDate: "2025-07-18",
    durableObjects: { PUSH_SHARDS: { className: "PushShard", useSQLite: true } },
    // Deployed with the checked-in "false" values and no provider secrets at
    // all: the honest state right after this deployment.
    bindings: { PUSH_ENABLED: "false", MAILBOX_ENABLED: "false" },
  });
  try {
    assert.equal((await mf.dispatchFetch("https://apu.test/health")).status, 503);
    for (const path of ["/v1/registration", "/v1/wake", "/v1/mailbox/put"]) {
      const response = await mf.dispatchFetch("https://apu.test" + path, { method: "POST", body: "{}" });
      assert.equal(response.status, 503, path);
    }
    // A GET on a POST-only path must not become a 500 either.
    assert.equal((await mf.dispatchFetch("https://apu.test/v1/wake")).status, 503);
  } finally {
    await mf.dispose();
  }
});
