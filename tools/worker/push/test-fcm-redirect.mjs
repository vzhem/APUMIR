import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { createServer } from "node:http";
import { FcmV1 } from "./fcm.mjs";

// All credentials and messages below are fabricated. The two loopback servers
// stand in for an expected provider and an unrelated redirect destination.
const rsa = generateKeyPairSync("rsa", { modulusLength: 2048 });
const env = {
  FCM_PROJECT_ID: "test-project",
  FCM_CLIENT_EMAIL: "test@example.invalid",
  FCM_PRIVATE_KEY: rsa.privateKey.export({ format: "pem", type: "pkcs8" }).toString(),
};
const fcmToken = "fabricated-device-token";
const route = "ab".repeat(32);
const redirects = [307, 308, 301, 302, 303];

async function listen(server) {
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  return `http://127.0.0.1:${server.address().port}`;
}

async function close(server) {
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

async function fixture() {
  let hits = 0;
  let oauthCalls = 0;
  let fcmCalls = 0;
  let phase = "oauth";
  let status = 307;
  let sameOrigin = false;
  const sink = createServer((req, res) => {
    hits++;
    req.resume();
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ access_token: "fabricated-access-token", expires_in: 3600, name: "fake" }));
  });
  const sinkUrl = await listen(sink);
  let providerUrl;
  const provider = createServer((req, res) => {
    req.resume();
    if (req.url === "/capture") hits++;
    else if (req.url === "/oauth") oauthCalls++;
    else if (req.url === "/fcm") fcmCalls++;
    else { res.writeHead(404).end(); return; }
    if (req.url === "/" + phase) {
      const target = sameOrigin ? providerUrl : sinkUrl;
      res.writeHead(status, { location: target + "/capture" }).end();
      return;
    }
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ access_token: "fabricated-access-token", expires_in: 3600, name: "fake" }));
  });
  try {
    providerUrl = await listen(provider);
  } catch (error) {
    await close(sink);
    throw error;
  }
  return {
    prepare(nextPhase, nextStatus, nextSameOrigin) {
      phase = nextPhase;
      status = nextStatus;
      sameOrigin = nextSameOrigin;
      hits = 0;
      oauthCalls = 0;
      fcmCalls = 0;
    },
    counts() { return { hits, oauthCalls, fcmCalls }; },
    sendRequest(url, options) {
      if (url === "https://oauth2.googleapis.com/token") return fetch(providerUrl + "/oauth", options);
      if (url === "https://fcm.googleapis.com/v1/projects/test-project/messages:send") {
        return fetch(providerUrl + "/fcm", options);
      }
      throw new Error("Unexpected provider endpoint");
    },
    async close() { await Promise.all([close(provider), close(sink)]); },
  };
}

for (const phase of ["oauth", "fcm"]) {
  test(`${phase} redirects never forward a POST to any other endpoint`, async (t) => {
    const server = await fixture();
    t.after(() => server.close());
    for (const sameOrigin of [false, true]) {
      for (const status of redirects) {
        server.prepare(phase, status, sameOrigin);
        const client = new FcmV1(env, server.sendRequest);
        const result = await client.send(fcmToken, route);
        const counts = server.counts();
        const scenario = `${phase} HTTP ${status}, ${sameOrigin ? "same" : "cross"}-origin`;
        assert.equal(counts.hits, 0, `${scenario} must not contact the redirect destination`);
        assert.equal(result, "retry", `${scenario} must be retried, not accepted`);
        assert.deepEqual(counts, {
          hits: 0,
          oauthCalls: 1,
          fcmCalls: phase === "fcm" ? 1 : 0,
        });
      }
    }
  });
}
