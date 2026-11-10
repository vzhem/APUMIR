// FCM HTTP v1 using a server-side service account. Never embed this credential in
// the APK, a response, or a log. The caller treats all outcomes as wake signals,
// not acknowledgements of message receipt.
const encoder = new TextEncoder();
const OAUTH_ENDPOINT = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

function base64url(bytes) {
  let text = "";
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function pemBytes(pem) {
  const body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "").replace(/\s/g, "");
  const binary = atob(body);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

async function boundedJson(response) {
  if (!response.body) throw new Error("empty provider response");
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > 16_384) { await reader.cancel(); throw new Error("provider response too large"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const body = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.length; }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(body));
}

export function fcmConfigured(env) {
  return typeof env.FCM_PROJECT_ID === "string" && /^[a-z0-9][a-z0-9-]{4,62}$/.test(env.FCM_PROJECT_ID) &&
    typeof env.FCM_CLIENT_EMAIL === "string" && /^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+$/.test(env.FCM_CLIENT_EMAIL) &&
    typeof env.FCM_PRIVATE_KEY === "string" && env.FCM_PRIVATE_KEY.includes("-----BEGIN PRIVATE KEY-----");
}

export class FcmV1 {
  constructor(env, sendRequest = (...args) => fetch(...args)) {
    this.env = env;
    this.sendRequest = sendRequest;
    this.cached = null;
    this.pending = null;
  }

  async accessToken() {
    const now = Math.floor(Date.now() / 1000);
    if (this.cached && this.cached.until > now) return this.cached.token;
    if (this.pending) return this.pending;
    this.pending = this.fetchAccessToken(now);
    try { return await this.pending; } finally { this.pending = null; }
  }

  async fetchAccessToken(now) {
    if (!fcmConfigured(this.env)) throw new Error("FCM not configured");
    const header = base64url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
    const claims = base64url(encoder.encode(JSON.stringify({
      iss: this.env.FCM_CLIENT_EMAIL, scope: SCOPE, aud: OAUTH_ENDPOINT, iat: now, exp: now + 3600,
    })));
    const unsigned = header + "." + claims;
    const key = await crypto.subtle.importKey(
      "pkcs8", pemBytes(this.env.FCM_PRIVATE_KEY),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
    );
    const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
    const assertion = unsigned + "." + base64url(new Uint8Array(signature));
    const form = "grant_type=" + encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") +
      "&assertion=" + encodeURIComponent(assertion);
    const response = await this.sendRequest(OAUTH_ENDPOINT, {
      method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
      body: form, signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error("FCM OAuth unavailable");
    const payload = await boundedJson(response);
    if (typeof payload.access_token !== "string" || payload.access_token.length > 4096 || !payload.access_token) {
      throw new Error("FCM OAuth invalid response");
    }
    const lifetime = Number.isFinite(payload.expires_in) ? Math.min(Math.max(payload.expires_in, 0), 3600) : 0;
    // Never cache beyond the actual grant lifetime, even for unusually short grants.
    this.cached = { token: payload.access_token, until: now + Math.max(0, lifetime - 60) };
    return payload.access_token;
  }

  /** Returns sent, unregistered, or retry. Neither sent nor accepted is delivery. */
  async send(token, route) {
    try {
      const access = await this.accessToken();
      const endpoint = "https://fcm.googleapis.com/v1/projects/" + this.env.FCM_PROJECT_ID + "/messages:send";
      const response = await this.sendRequest(endpoint, {
        method: "POST",
        headers: { authorization: "Bearer " + access, "content-type": "application/json" },
        body: JSON.stringify({ message: {
          token,
          // Only an opaque route is shared with the provider; no peer id, chat id, text or capability.
          data: { v: "1", route },
          android: { priority: "HIGH", ttl: "3600s", collapse_key: "apu-wake", restricted_package_name: "com.vladimir.messenger" },
        } }),
        signal: AbortSignal.timeout(10_000),
      });
      if (response.ok) return "sent";
      if (response.status === 401) this.cached = null;
      // 404 alone may indicate a wrong endpoint/project. Only the explicit FCM
      // UNREGISTERED error is proof that this particular token can be retired.
      if (response.status === 404) {
        const body = await boundedJson(response);
        if (Array.isArray(body?.error?.details) && body.error.details.some((detail) =>
          detail?.["@type"] === "type.googleapis.com/google.firebase.fcm.v1.FcmError" && detail.errorCode === "UNREGISTERED")) {
          return "unregistered";
        }
      }
      return "retry";
    } catch (_) {
      // A transient provider failure is retained by the bounded wake queue;
      // credentials, tokens and provider response bodies must never be logged.
      return "retry";
    }
  }
}
