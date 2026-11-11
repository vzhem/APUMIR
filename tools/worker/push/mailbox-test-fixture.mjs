// Test-only helpers: the fake envelope is merely structurally valid and is
// deliberately NOT produced by the application's authenticated encryption.
import { createPrivateKey, createPublicKey, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { mailboxAccessPayload } from "./mailbox-protocol.mjs";
import { registrationPayload } from "./protocol.mjs";

export const randomHex = (size) => randomBytes(size).toString("hex");
export function identity(seed = null) {
  const keys = seed === null ? generateKeyPairSync("ed25519") : (() => {
    const privateKey = createPrivateKey({
      key: Buffer.concat([Buffer.from("302e020100300506032b657004220420", "hex"), Buffer.alloc(32, seed)]),
      format: "der", type: "pkcs8",
    });
    return { privateKey, publicKey: createPublicKey(privateKey) };
  })();
  return { ...keys, owner: keys.publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("hex") };
}

export function fakeEnvelope(fill = 1, cipherBytes = 32) {
  const key = Buffer.alloc(204, fill);
  key[0] = 1; key[1] = 0; key[2] = 1;
  return ["APUSEAL1", key.toString("base64"), Buffer.alloc(12, fill).toString("base64"),
    Buffer.alloc(cipherBytes, fill).toString("base64")].join("|");
}

export async function registration(keys, changes = {}) {
  const value = {
    action: "register", owner: keys.owner, capability: randomHex(32), device: randomHex(16), sequence: 1,
    issuedAt: Math.floor(Date.now() / 1000), token: "fcm-test-token-" + randomHex(16), signature: "00".repeat(64), ...changes,
  };
  value.signature = sign(null, await registrationPayload(value), keys.privateKey).toString("hex");
  return value;
}

export function access(keys, action, changes = {}) {
  const value = {
    owner: keys.owner, capability: randomHex(32), device: randomHex(16), issuedAt: Math.floor(Date.now() / 1000),
    ...(action === "pull" ? { after: "-" } : { items: [{ id: randomHex(16), receipt: randomHex(16) }] }),
    signature: "00".repeat(64), ...changes,
  };
  value.signature = sign(null, mailboxAccessPayload(value, action), keys.privateKey).toString("hex");
  return value;
}
