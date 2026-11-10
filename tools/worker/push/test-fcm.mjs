import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { FcmV1, fcmConfigured } from "./fcm.mjs";

const rsa = generateKeyPairSync("rsa", { modulusLength: 2048 });
const env = {
  FCM_PROJECT_ID: "test-project",
  FCM_CLIENT_EMAIL: "test@example.invalid",
  FCM_PRIVATE_KEY: rsa.privateKey.export({ format: "pem", type: "pkcs8" }).toString(),
};
const token = "fcm-token-abcdefghijklmnop";
const route = "ab".repeat(32);

test("provider 404 does not revoke a token unless FCM explicitly says UNREGISTERED", async () => {
  let mode = "plain404";
  let oauthCalls = 0;
  const client = new FcmV1(env, async (url) => {
    if (url.includes("oauth2.googleapis.com")) { oauthCalls++; return Response.json({ access_token: "access", expires_in: 3600 }); }
    if (mode === "plain404") return Response.json({ error: { message: "bad project" } }, { status: 404 });
    if (mode === "wrong-type") return Response.json({ error: { details: [{ "@type": "unrelated", errorCode: "UNREGISTERED" }] } }, { status: 404 });
    if (mode === "unregistered") return Response.json({ error: { details: [{ "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: "UNREGISTERED" }] } }, { status: 404 });
    if (mode === "unauthorised") return new Response("", { status: 401 });
    return new Response("", { status: 500 });
  });
  assert.ok(fcmConfigured(env));
  assert.equal(await client.send(token, route), "retry");
  mode = "wrong-type";
  assert.equal(await client.send(token, route), "retry");
  mode = "unregistered";
  assert.equal(await client.send(token, route), "unregistered");
  assert.equal(oauthCalls, 1);
  mode = "unauthorised";
  assert.equal(await client.send(token, route), "retry");
  mode = "other";
  assert.equal(await client.send(token, route), "retry");
  assert.equal(oauthCalls, 2, "401 invalidates the cached access token");
});

test("parallel sends share one OAuth grant and only send opaque route data", async () => {
  let grants = 0;
  let sends = 0;
  const client = new FcmV1(env, async (url, request) => {
    if (url.includes("oauth2.googleapis.com")) { grants++; return Response.json({ access_token: "access", expires_in: 3600 }); }
    sends++;
    const message = JSON.parse(request.body).message;
    assert.deepEqual(message.data, { v: "1", route });
    assert.equal(message.notification, undefined);
    return Response.json({ name: "fake" });
  });
  assert.deepEqual(await Promise.all(Array.from({ length: 4 }, () => client.send(token, route))), ["sent", "sent", "sent", "sent"]);
  assert.equal(grants, 1);
  assert.equal(sends, 4);
});

test("oversized or unavailable provider responses fail closed without retiring the token", async () => {
  const oversized = new FcmV1(env, async () => new Response("x".repeat(20_000)));
  assert.equal(await oversized.send(token, route), "retry");
  const offline = new FcmV1(env, async () => { throw new Error("network unavailable"); });
  assert.equal(await offline.send(token, route), "retry");
  const invalid = new FcmV1({});
  assert.equal(fcmConfigured({}), false);
  assert.equal(await invalid.send(token, route), "retry");
});
