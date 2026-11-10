// Experimental, disabled-by-default remote wake protocol. No message content,
// node IDs or FCM registration tokens belong in a wake request.
const encoder = new TextEncoder();
const HEX32 = /^[0-9a-f]{64}$/;
const HEX16 = /^[0-9a-f]{32}$/;
const HEX64 = /^[0-9a-f]{128}$/;
const TOKEN = /^[A-Za-z0-9_:.-]{20,2048}$/;
const REGISTER_FIELDS = ["action", "owner", "capability", "device", "sequence", "issuedAt", "token", "signature"];
const WAKE_FIELDS = ["owner", "capability"];
const CLOCK_SKEW_SECONDS = 5 * 60;

function exactFields(value, expected) {
  return value !== null && typeof value === "object" && !Array.isArray(value) &&
    Object.keys(value).length === expected.length && expected.every((key) => Object.hasOwn(value, key));
}

export function validWake(value) {
  return exactFields(value, WAKE_FIELDS) && typeof value.owner === "string" && typeof value.capability === "string" &&
    HEX32.test(value.owner) && HEX32.test(value.capability) &&
    value.owner !== "0".repeat(64) && value.capability !== "0".repeat(64);
}

export function validRegistration(value, nowSeconds) {
  return exactFields(value, REGISTER_FIELDS) &&
    (value.action === "register" || value.action === "revoke") &&
    typeof value.owner === "string" && typeof value.capability === "string" && typeof value.device === "string" &&
    HEX32.test(value.owner) && HEX32.test(value.capability) && HEX16.test(value.device) &&
    value.owner !== "0".repeat(64) && value.capability !== "0".repeat(64) &&
    Number.isSafeInteger(value.sequence) && value.sequence > 0 &&
    Number.isSafeInteger(value.issuedAt) && Math.abs(value.issuedAt - nowSeconds) <= CLOCK_SKEW_SECONDS &&
    typeof value.token === "string" &&
    (value.action === "register" ? TOKEN.test(value.token) : value.token === "") &&
    typeof value.signature === "string" && HEX64.test(value.signature);
}

export function bytesFromHex(hex) {
  if (typeof hex !== "string" || hex.length % 2 !== 0 || !/^[0-9a-f]*$/.test(hex)) {
    throw new Error("invalid hex");
  }
  return Uint8Array.from(hex.match(/../g) || [], (byte) => parseInt(byte, 16));
}

export function hexFromBytes(bytes) {
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function sha256(bytes) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

function concat(...parts) {
  const length = parts.reduce((sum, part) => sum + part.length, 0);
  const combined = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) { combined.set(part, offset); offset += part.length; }
  return combined;
}

/**
 * Bind a route to the receiver's actual Ed25519 key. Knowing a capability
 * permits wakes, but does not let its sender replace the receiver's token.
 */
export async function routeFor(owner, capability) {
  if (!HEX32.test(owner) || !HEX32.test(capability)) throw new Error("invalid route");
  const material = concat(encoder.encode("apu-push-route-v1\0"), bytesFromHex(owner), bytesFromHex(capability));
  return hexFromBytes(await sha256(material));
}

/** All routes for an owner share a bounded shard and owner-level wake budget. */
export async function shardFor(owner) {
  if (!HEX32.test(owner)) throw new Error("invalid owner");
  const hash = await sha256(concat(encoder.encode("apu-push-shard-v1\0"), bytesFromHex(owner)));
  return hash[0] % 64;
}

/** ASCII canonical payload. The token itself is signed via its SHA-256 digest. */
export async function registrationPayload(value) {
  const tokenHash = hexFromBytes(await sha256(encoder.encode(value.token)));
  return encoder.encode([
    "apu-push-registration-v1", value.action, value.owner, value.capability,
    value.device, String(value.sequence), String(value.issuedAt), tokenHash,
  ].join("\n") + "\n");
}

export async function verifyRegistration(value) {
  try {
    const publicKey = await crypto.subtle.importKey("raw", bytesFromHex(value.owner), "Ed25519", false, ["verify"]);
    return await crypto.subtle.verify("Ed25519", publicKey, bytesFromHex(value.signature), await registrationPayload(value));
  } catch (_) {
    // Malformed, attacker-controlled keys/signatures are an ordinary rejection.
    return false;
  }
}

/** Pseudonymise the Cloudflare-provided source address; do not store raw IPs. */
export async function rateIdentity(secret, address) {
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const source = typeof address === "string" && address.length <= 128 ? address : "unknown";
  const digest = await crypto.subtle.sign("HMAC", key, encoder.encode("apu-push-ip-v1\0" + source));
  return hexFromBytes(digest);
}
