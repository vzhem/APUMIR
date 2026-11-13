// Run the exact source modules/flags prepared for the proposed API upload in
// local workerd. No credentials or network/provider deployment is involved.
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { Miniflare } from "miniflare";

const script = fileURLToPath(new URL("../../../scripts/ci/push-disabled-deploy.py", import.meta.url));
const python = `
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location("push_disabled_deploy", sys.argv[1])
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)
d.validate_config()
print(json.dumps(d.version_payload(), separators=(",", ":")))
`;

test("exact API modules load in workerd with wake and mailbox disabled", async () => {
  const raw = execFileSync("python3", ["-c", python, script], {
    encoding: "utf8", maxBuffer: 1024 * 1024, env: { PATH: process.env.PATH || "/usr/bin:/bin" },
  });
  const payload = JSON.parse(raw);
  assert.equal(payload.main_module, "worker.mjs");
  assert.equal(payload.modules[0].name, payload.main_module);
  assert.deepEqual(payload.migrations, { new_tag: "v1", new_sqlite_classes: ["PushShard"] });
  const modules = payload.modules.map((item) => {
    assert.equal(item.content_type, "application/javascript+module");
    return { type: "ESModule", path: item.name, contents: Buffer.from(item.content_base64, "base64") };
  });
  const vars = Object.fromEntries(payload.bindings.filter((item) => item.type === "plain_text").map((item) => [item.name, item.text]));
  assert.deepEqual(vars, { PUSH_ENABLED: "false", MAILBOX_ENABLED: "false" });
  const objectBindings = payload.bindings.filter((item) => item.type === "durable_object_namespace");
  assert.deepEqual(objectBindings, [{ type: "durable_object_namespace", name: "PUSH_SHARDS", class_name: "PushShard" }]);
  let outbound = 0;
  const worker = new Miniflare({
    modules,
    compatibilityDate: payload.compatibility_date,
    durableObjects: { PUSH_SHARDS: { className: "PushShard", useSQLite: true } },
    bindings: vars,
    outboundService: async () => { outbound++; throw new Error("Unexpected outbound request"); },
  });
  try {
    await worker.ready;
    const health = await worker.dispatchFetch("https://apu.test/health");
    assert.equal(health.status, 503);
    assert.deepEqual(await health.json(), { ok: false, service: "apu-push-wake", experimental: true });
    for (const path of ["/v1/registration", "/v1/wake", "/v1/mailbox/put", "/v1/mailbox/pull", "/v1/mailbox/ack"]) {
      const response = await worker.dispatchFetch("https://apu.test" + path, {
        method: "POST", headers: { "content-type": "application/json" }, body: "{}",
      });
      assert.equal(response.status, 503, path);
    }
    assert.equal(outbound, 0);
  } finally { await worker.dispose(); }
});
