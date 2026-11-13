// Exercise the actual FCM client in workerd. All providers and credentials are
// synthetic; outboundService intercepts every request before it leaves the test.
import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { Miniflare } from "miniflare";

const rsa = generateKeyPairSync("rsa", { modulusLength: 2048 });
const bindings = {
  FCM_PROJECT_ID: "test-project",
  FCM_CLIENT_EMAIL: "test@example.invalid",
  FCM_PRIVATE_KEY: rsa.privateKey.export({ format: "pem", type: "pkcs8" }).toString(),
};
const entrypoint = `
  import { FcmV1 } from "./fcm.mjs";
  export default {
    async fetch(_request, env) {
      const result = await new FcmV1(env).send("fabricated-device-token", "ab".repeat(32));
      return Response.json({ result });
    },
  };
`;
const oauthUrl = "https://oauth2.googleapis.com/token";
const fcmUrl = "https://fcm.googleapis.com/v1/projects/test-project/messages:send";
const redirectStatuses = [301, 302, 303, 307, 308];

test("workerd sends normally but never follows OAuth or FCM redirects", async (t) => {
  let phase = "none";
  let status = 307;
  let sameOrigin = false;
  let oauthCalls = 0;
  let fcmCalls = 0;
  let redirectCalls = 0;
  const worker = new Miniflare({
    modules: true,
    // The script is inline; this path supplies the directory for its import.
    scriptPath: new URL("./inline-fcm-redirect-test.mjs", import.meta.url).pathname,
    script: entrypoint,
    compatibilityDate: "2025-07-18",
    bindings,
    outboundService: async (request) => {
      if (request.url === "https://untrusted.invalid/capture" ||
          request.url === "https://oauth2.googleapis.com/capture" ||
          request.url === "https://fcm.googleapis.com/capture") {
        redirectCalls++;
        return Response.json({ access_token: "fake-access", expires_in: 3600, name: "fake" });
      }
      if (request.method !== "POST") throw new Error("Unexpected provider method");
      if (request.url === oauthUrl) {
        oauthCalls++;
        if (phase === "oauth") {
          const target = sameOrigin ? "https://oauth2.googleapis.com/capture" : "https://untrusted.invalid/capture";
          return new Response(null, { status, headers: { location: target } });
        }
        return Response.json({ access_token: "fake-access", expires_in: 3600 });
      }
      if (request.url === fcmUrl) {
        fcmCalls++;
        if (phase === "fcm") {
          const target = sameOrigin ? "https://fcm.googleapis.com/capture" : "https://untrusted.invalid/capture";
          return new Response(null, { status, headers: { location: target } });
        }
        return Response.json({ name: "fake" });
      }
      throw new Error("Unexpected outbound endpoint");
    },
  });
  t.after(() => worker.dispose());
  await worker.ready;

  async function run() {
    oauthCalls = 0;
    fcmCalls = 0;
    redirectCalls = 0;
    const response = await worker.dispatchFetch("https://fcm-test.invalid/run");
    assert.equal(response.status, 200);
    const body = await response.json();
    return { result: body.result, oauthCalls, fcmCalls, redirectCalls };
  }

  // A broken redirect mode must not make every send retry before reaching OAuth.
  assert.deepEqual(await run(), { result: "sent", oauthCalls: 1, fcmCalls: 1, redirectCalls: 0 });
  for (phase of ["oauth", "fcm"]) {
    for (sameOrigin of [false, true]) {
      for (status of redirectStatuses) {
        assert.deepEqual(await run(), {
          result: "retry", oauthCalls: 1, fcmCalls: phase === "fcm" ? 1 : 0, redirectCalls: 0,
        }, `${phase} HTTP ${status}, ${sameOrigin ? "same" : "cross"}-origin`);
      }
    }
  }
});
