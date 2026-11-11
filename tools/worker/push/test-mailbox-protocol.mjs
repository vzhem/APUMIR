import test from "node:test";
import assert from "node:assert/strict";
import { mailboxAccessPayload, validMailboxAccess, validMailboxEnvelope, validMailboxPut, verifyMailboxAccess } from "./mailbox-protocol.mjs";
import { access, fakeEnvelope, identity, randomHex } from "./mailbox-test-fixture.mjs";

const capability = "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a";
const vectorKey = identity(2);
const common = { capability, device: "56".repeat(16), issuedAt: 1_700_000_000 };

test("receiver-access Ed25519 vectors bind route, action, cursor and each receipt", async () => {
  const pull = access(vectorKey, "pull", common);
  assert.equal(vectorKey.owner, "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394");
  assert.equal(pull.signature, "8283a87b9ae172188e4c7dd879bb456d0474eba22acf0f8f3b83d56b5f87f22533506e7a5eb3256263b65d3581c6c1d3f2502f4416347aab80212ba486b2d00f");
  assert.ok(validMailboxAccess(pull, "pull", common.issuedAt));
  assert.ok(await verifyMailboxAccess(pull, "pull"));
  const resumed = access(vectorKey, "pull", { ...common, after: "33".repeat(16) });
  assert.equal(resumed.signature, "5df1c4eaa4fb6b02de113ddfcdaba5f7cd5841538e23c160986babaf8c0e86239aa9d5d2f108edf4d16fd7e751f811d285613e419d5afcebeed6ec0c56dd3c0f");
  assert.ok(validMailboxAccess(resumed, "pull", common.issuedAt));
  assert.ok(await verifyMailboxAccess(resumed, "pull"));
  assert.equal(await verifyMailboxAccess({ ...pull, after: "11".repeat(16) }, "pull"), false);
  assert.equal(await verifyMailboxAccess({ ...pull, device: "77".repeat(16) }, "pull"), false);
  assert.equal(await verifyMailboxAccess({ ...pull, capability: "99".repeat(32) }, "pull"), false);
  assert.equal(await verifyMailboxAccess({ ...pull, issuedAt: pull.issuedAt + 1 }, "pull"), false);
  const items = [{ id: "11".repeat(16), receipt: "22".repeat(16) }, { id: "33".repeat(16), receipt: "44".repeat(16) }];
  const ack = access(vectorKey, "ack", { ...common, items });
  assert.equal(ack.signature, "9e5945387e6560401b96bf3ddedf5914f34f7665e0cd517b797498785535d9dae0972f64a26c661bf5578137616ca994408ae6fd23982422e1bf1a400e96e40e");
  assert.ok(validMailboxAccess(ack, "ack", common.issuedAt));
  assert.ok(await verifyMailboxAccess(ack, "ack"));
  assert.equal(await verifyMailboxAccess({ ...ack, items: [{ ...items[0], receipt: "55".repeat(16) }, items[1]] }, "ack"), false);
  assert.notEqual(Buffer.from(mailboxAccessPayload(pull, "pull")).toString(), Buffer.from(mailboxAccessPayload(ack, "ack")).toString());
});

test("pull and acknowledgement reject extra, stale, unordered, duplicate and noncanonical fields", () => {
  const pull = access(vectorKey, "pull", common);
  const ack = access(vectorKey, "ack", { ...common, items: [
    { id: "11".repeat(16), receipt: "22".repeat(16) }, { id: "33".repeat(16), receipt: "44".repeat(16) },
  ] });
  for (const changed of [
    { ...pull, after: "00".repeat(16) }, { ...pull, after: "ab".repeat(16).toUpperCase() },
    { ...pull, device: "00".repeat(16) }, { ...pull, issuedAt: common.issuedAt - 301 },
    { ...pull, issuedAt: common.issuedAt + 301 }, { ...pull, issuedAt: 1.5 },
    { ...pull, signature: "FF".repeat(64) }, { ...pull, extra: "plaintext" },
  ]) assert.equal(validMailboxAccess(changed, "pull", common.issuedAt), false);
  for (const items of [[], [...ack.items].reverse(), [ack.items[0], ack.items[0]],
    [{ ...ack.items[0], extra: 1 }], [{ ...ack.items[0], receipt: "00".repeat(16) }],
    Array.from({ length: 17 }, (_, i) => ({ id: i.toString(16).padStart(32, "0"), receipt: "22".repeat(16) }))]) {
    assert.equal(validMailboxAccess({ ...ack, items }, "ack", common.issuedAt), false);
  }
  assert.equal(validMailboxAccess(ack, "pull", common.issuedAt), false);
  assert.equal(validMailboxAccess(pull, "other", common.issuedAt), false);
});

test("only a bounded canonical APUSEAL1 structure is accepted; structural checks are not authentication", () => {
  const wire = fakeEnvelope();
  assert.ok(validMailboxEnvelope(wire));
  assert.ok(validMailboxEnvelope(fakeEnvelope(3, 64 * 1024 + 16)));
  assert.equal(validMailboxEnvelope(fakeEnvelope(3, 64 * 1024 + 17)), false);
  assert.equal(validMailboxEnvelope(fakeEnvelope(3, 16)), false);
  assert.equal(validMailboxEnvelope("ordinary plaintext"), false);
  assert.equal(validMailboxEnvelope(wire + "|other"), false);
  const parts = wire.split("|");
  assert.equal(validMailboxEnvelope([parts[0], parts[1], Buffer.alloc(11).toString("base64"), parts[3]].join("|")), false);
  const badKey = Buffer.from(parts[1], "base64");
  badKey[0] = 2;
  assert.equal(validMailboxEnvelope([parts[0], badKey.toString("base64"), parts[2], parts[3]].join("|")), false);
  badKey[0] = 1; badKey[2] = 2;
  assert.equal(validMailboxEnvelope([parts[0], badKey.toString("base64"), parts[2], parts[3]].join("|")), false);
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  const last = parts[3].at(-2);
  const noncanonical = parts[3].slice(0, -2) + alphabet[alphabet.indexOf(last) | 1] + "=";
  assert.equal(Buffer.from(noncanonical, "base64").equals(Buffer.from(parts[3], "base64")), true);
  assert.equal(validMailboxEnvelope([parts[0], parts[1], parts[2], noncanonical].join("|")), false, "noncanonical Base64 pad bits");
  assert.equal(validMailboxEnvelope(wire.replace("APUSEAL1|", "APUSEAL1|\n")), false);
  const put = { owner: vectorKey.owner, capability, id: randomHex(16), envelope: wire };
  assert.ok(validMailboxPut(put));
  for (const changed of [{ id: "0".repeat(32) }, { capability: "0".repeat(64) },
    { envelope: "plaintext" }, { extra: "plaintext" }]) {
    assert.equal(validMailboxPut({ ...put, ...changed }), false);
  }
});
