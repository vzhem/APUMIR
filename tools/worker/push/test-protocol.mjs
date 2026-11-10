import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { bytesFromHex, hexFromBytes, registrationPayload, routeFor, shardFor, validRegistration, validWake, verifyRegistration } from "./protocol.mjs";

function identity() {
  const keys = generateKeyPairSync("ed25519");
  return { ...keys, owner: keys.publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("hex") };
}

async function signed(keys, changes = {}) {
  const value = {
    action: "register", owner: keys.owner, capability: randomBytes(32).toString("hex"),
    device: randomBytes(16).toString("hex"), sequence: 1, issuedAt: Math.floor(Date.now() / 1000),
    token: "fcm-test-token-abcdefghijklmnop", signature: "00".repeat(64), ...changes,
  };
  value.signature = sign(null, await registrationPayload(value), keys.privateKey).toString("hex");
  return value;
}

test("route has a shared deterministic vector and is bound to owner and capability", async () => {
  const owner = "00".repeat(32);
  const cap = "01".repeat(32);
  assert.equal(await routeFor(owner, cap), "4fa6003fb424a98ab244c523a6ee4bb54e040dde3fd1717e19357327fa35c49c");
  // Same signed-binding/X25519/HKDF vector as rust-core's capability tests.
  assert.equal(await routeFor(
    "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394",
    "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a",
  ), "4237b0e96e22eadbb5093d4cada086a38530389a898b64aa8fc14e8afd780419");
  assert.notEqual(await routeFor("02".repeat(32), cap), await routeFor(owner, cap));
  assert.notEqual(await routeFor(owner, "02".repeat(32)), await routeFor(owner, cap));
  assert.equal(await shardFor(owner), await shardFor(owner));
  assert.ok((await shardFor(owner)) < 64);
});

test("registration signature binds receiver, capability, device, sequence, time and token", async () => {
  const keys = identity();
  const value = await signed(keys);
  assert.ok(validRegistration(value, value.issuedAt));
  assert.ok(await verifyRegistration(value));
  for (const [field, replacement] of Object.entries({
    action: "revoke", owner: "aa".repeat(32), capability: "bb".repeat(32),
    device: "cc".repeat(16), sequence: 2, issuedAt: value.issuedAt + 1,
    token: "fcm-tampered-token-abcdefghijk",
  })) {
    assert.equal(await verifyRegistration({ ...value, [field]: replacement }), false, field);
  }
  const impostor = identity();
  assert.equal(await verifyRegistration({ ...value, signature: (await signed(impostor)).signature }), false);
});

test("only bounded canonical fields, fresh time, and strictly typed lowercase values are accepted", async () => {
  const value = await signed(identity());
  assert.ok(validRegistration(value, value.issuedAt));
  assert.ok(validWake({ owner: value.owner, capability: value.capability }));
  for (const changed of [
    { owner: value.owner.toUpperCase() }, { owner: 123 }, { owner: "0".repeat(64) },
    { capability: [] }, { capability: "0".repeat(64) },
    { device: "00" }, { sequence: 0 }, { sequence: 1.5 }, { sequence: Number.MAX_SAFE_INTEGER + 1 },
    { issuedAt: value.issuedAt - 301 }, { issuedAt: value.issuedAt + 301 },
    { token: "short" }, { token: "x".repeat(2049) }, { token: "x".repeat(20) + "\n" },
    { signature: "00" }, { action: "revoke", token: "not empty" }, { extra: "no message text" },
  ]) assert.equal(validRegistration({ ...value, ...changed }, value.issuedAt), false, JSON.stringify(changed));
  assert.equal(validWake({ owner: value.owner, capability: value.capability, text: "secret" }), false);
  assert.equal(validWake(null), false);
  assert.throws(() => bytesFromHex("Ff"));
  assert.equal(hexFromBytes(bytesFromHex("00abff")), "00abff");
});
