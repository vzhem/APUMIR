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

function apiPayload() {
  return JSON.parse(execFileSync("python3", ["-c", python, script], {
    encoding: "utf8", maxBuffer: 1024 * 1024, env: { PATH: process.env.PATH || "/usr/bin:/bin" },
  }));
}

function apiModules(payload) {
  return payload.modules.map((item) => {
    assert.equal(item.content_type, "application/javascript+module");
    return { type: "ESModule", path: item.name, contents: Buffer.from(item.content_base64, "base64") };
  });
}

async function assertUnavailable(worker) {
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
}

test("exact API modules run disabled in workerd without a storage binding", async () => {
  const payload = apiPayload();
  assert.equal(payload.main_module, "worker.mjs");
  assert.equal(payload.modules[0].name, payload.main_module);
  assert.equal(Object.hasOwn(payload, "migrations"), false);
  assert.equal(Object.hasOwn(payload, "exports"), false);
  assert.deepEqual(payload.bindings, [
    { type: "plain_text", name: "PUSH_ENABLED", text: "false" },
    { type: "plain_text", name: "MAILBOX_ENABLED", text: "false" },
  ]);
  const vars = Object.fromEntries(payload.bindings.map((item) => [item.name, item.text]));
  let outbound = 0;
  const worker = new Miniflare({
    modules: apiModules(payload),
    compatibilityDate: payload.compatibility_date,
    bindings: vars,
    outboundService: async () => { outbound++; throw new Error("Unexpected outbound request"); },
  });
  try {
    assert.equal(Object.hasOwn(await worker.getBindings(), "PUSH_SHARDS"), false);
    await assertUnavailable(worker);
    assert.equal(outbound, 0);
  } finally { await worker.dispose(); }
});

test("missing shard binding still blocks synthetically enabled push", async () => {
  const payload = apiPayload();
  let outbound = 0;
  // Fabricated values satisfy only the shallow configuration checks; none are
  // valid credentials, and this deliberately differs from the actual payload.
  const bindings = {
    PUSH_ENABLED: "true", MAILBOX_ENABLED: "true", PUSH_RATE_KEY: "x".repeat(32),
    FCM_PROJECT_ID: "fake-project", FCM_CLIENT_EMAIL: "fake@example.invalid",
    FCM_PRIVATE_KEY: "-----BEGIN PRIVATE KEY----- fake",
  };
  const options = {
    modules: apiModules(payload),
    compatibilityDate: payload.compatibility_date,
    bindings,
    outboundService: async () => { outbound++; throw new Error("Unexpected outbound request"); },
  };
  const worker = new Miniflare(options);
  try {
    assert.equal(Object.hasOwn(await worker.getBindings(), "PUSH_SHARDS"), false);
    await assertUnavailable(worker);
    assert.equal(outbound, 0);
  } finally { await worker.dispose(); }

  // Negative control: with the same fake flags and a local shard binding the
  // health predicate becomes true. It proves the missing binding is the cause.
  const control = new Miniflare({
    ...options, durableObjects: { PUSH_SHARDS: { className: "PushShard", useSQLite: true } },
  });
  try {
    await control.ready;
    const health = await control.dispatchFetch("https://apu.test/health");
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { ok: true, service: "apu-push-wake", experimental: true });
    assert.equal(outbound, 0);
  } finally { await control.dispose(); }
});
