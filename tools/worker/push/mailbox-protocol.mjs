// Experimental mailbox protocol. Structural checks are NOT proof of encryption
// or of the sender's identity; the receiving client must open the envelope,
// authenticate its original pinned peer and persist before acknowledging it.
import { bytesFromHex, validWake } from "./protocol.mjs";

const encoder = new TextEncoder();
const HEX16 = /^[0-9a-f]{32}$/;
const HEX64 = /^[0-9a-f]{128}$/;
const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;
const MAX_CLOCK_SKEW_SECONDS = 300;
const MAX_ITEMS = 16;
export const MAX_MAILBOX_WIRE_CHARS = 192 * 1024;
export const MAX_MAILBOX_BODY_BYTES = MAX_MAILBOX_WIRE_CHARS + 1024;

function exactFields(value, expected) {
  return value !== null && typeof value === "object" && !Array.isArray(value) &&
    Object.keys(value).length === expected.length && expected.every((key) => Object.hasOwn(value, key));
}

function nonzeroId(value) {
  return typeof value === "string" && HEX16.test(value) && value !== "0".repeat(32);
}

// Limit each field before decoding. Re-encoding rejects noncanonical padding and
// pad bits. A malicious capability holder can still forge arbitrary bytes here;
// only the receiver's authenticated decrypt can establish what they contain.
function base64Bytes(value, minBytes, maxBytes) {
  if (typeof value !== "string" || value.length === 0 || value.length % 4 !== 0 ||
      value.length > Math.ceil(maxBytes / 3) * 4 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) return null;
  try {
    const decoded = atob(value);
    if (decoded.length < minBytes || decoded.length > maxBytes || btoa(decoded) !== value) return null;
    return decoded;
  } catch (_) {
    // Invalid, attacker-controlled Base64 is an ordinary rejection.
    return null;
  }
}

export function validMailboxEnvelope(wire) {
  if (typeof wire !== "string" || wire.length > MAX_MAILBOX_WIRE_CHARS || !wire.startsWith("APUSEAL1|")) return false;
  const fields = wire.slice("APUSEAL1|".length).split("|");
  if (fields.length !== 3) return false;
  const key = base64Bytes(fields[0], 204, 715);
  const iv = base64Bytes(fields[1], 12, 12);
  const ciphertext = base64Bytes(fields[2], 17, 64 * 1024 + 16);
  if (!key || !iv || !ciphertext || key.charCodeAt(0) !== 1) return false;
  const bindingSize = (key.charCodeAt(1) << 8) | key.charCodeAt(2);
  return bindingSize >= 1 && bindingSize <= 512 && key.length === bindingSize + 203;
}

export function validMailboxPut(value) {
  return exactFields(value, ["owner", "capability", "id", "envelope"]) &&
    validWake({ owner: value.owner, capability: value.capability }) && nonzeroId(value.id) &&
    validMailboxEnvelope(value.envelope);
}

/** The signature covers the action, owner, secret route, device, time and cursor/receipts. */
export function validMailboxAccess(value, action, nowSeconds) {
  const fields = action === "pull" ? ["owner", "capability", "device", "issuedAt", "after", "signature"] :
    action === "ack" ? ["owner", "capability", "device", "issuedAt", "items", "signature"] : null;
  if (!fields || !exactFields(value, fields) ||
      !validWake({ owner: value.owner, capability: value.capability }) || !nonzeroId(value.device) ||
      !Number.isSafeInteger(value.issuedAt) || value.issuedAt < 1 || value.issuedAt > MAX_SAFE_INTEGER ||
      !Number.isSafeInteger(nowSeconds) || Math.abs(value.issuedAt - nowSeconds) > MAX_CLOCK_SKEW_SECONDS ||
      typeof value.signature !== "string" || !HEX64.test(value.signature)) return false;
  if (action === "pull") return value.after === "-" || nonzeroId(value.after);
  if (!Array.isArray(value.items) || value.items.length < 1 || value.items.length > MAX_ITEMS) return false;
  let last = "";
  for (const item of value.items) {
    if (!exactFields(item, ["id", "receipt"]) || !nonzeroId(item.id) || !nonzeroId(item.receipt) || item.id <= last) return false;
    last = item.id;
  }
  return true;
}

export function mailboxAccessPayload(value, action) {
  const lines = ["apu-mailbox-access-v1", action, value.owner, value.capability, value.device, String(value.issuedAt)];
  if (action === "pull") lines.push(value.after);
  else if (action === "ack") {
    lines.push(String(value.items.length));
    for (const item of value.items) lines.push(item.id + ":" + item.receipt);
  } else throw new Error("invalid mailbox action");
  return encoder.encode(lines.join("\n") + "\n");
}

export async function verifyMailboxAccess(value, action) {
  try {
    const key = await crypto.subtle.importKey("raw", bytesFromHex(value.owner), "Ed25519", false, ["verify"]);
    return await crypto.subtle.verify("Ed25519", key, bytesFromHex(value.signature), mailboxAccessPayload(value, action));
  } catch (_) {
    // Invalid public keys and signatures are ordinary, secret-free rejections.
    return false;
  }
}
