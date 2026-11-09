import {
  generateBrowserSigningIdentity,
  shouldClearAccountSession,
  signLinkaDeviceChallenge
} from "./auth-crypto.mjs";

const DATABASE_NAME = "linka-browser-device-v1";
const OBJECT_STORE = "device";
const SESSION_KEY = "linka.account.session.v1";

function openIdentityDatabase() {
  return new Promise((resolve, reject) => {
    if (!("indexedDB" in globalThis)) {
      reject(new Error("This browser does not support secure local device-key storage."));
      return;
    }
    const request = indexedDB.open(DATABASE_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(OBJECT_STORE)) {
        request.result.createObjectStore(OBJECT_STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error("Unable to open browser device storage."));
    request.onblocked = () => reject(new Error("Browser device storage is blocked by another tab."));
  });
}

function databaseRequest(database, mode, operation) {
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(OBJECT_STORE, mode);
    const request = operation(transaction.objectStore(OBJECT_STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error("Unable to read browser device storage."));
    transaction.onabort = () => reject(new Error("Browser device storage transaction was aborted."));
  });
}

async function getOrCreateBrowserIdentity() {
  const database = await openIdentityDatabase();
  try {
    let identity = await databaseRequest(database, "readonly", store => store.get("current"));
    if (identity?.privateKey && identity?.publicKeyBase64 && identity?.deviceId) return identity;

    identity = await generateBrowserSigningIdentity();
    const persisted = {
      deviceId: identity.deviceId,
      privateKey: identity.privateKey,
      publicKeyBase64: identity.publicKeyBase64
    };
    await databaseRequest(database, "readwrite", store => store.put(persisted, "current"));
    return persisted;
  } finally {
    database.close();
  }
}

function normalizeApiOrigin(value) {
  const url = new URL(value.trim());
  const sameOrigin = url.origin === location.origin;
  const localHttp = url.protocol === "http:" &&
    ["localhost", "127.0.0.1", "10.0.2.2"].includes(url.hostname);
  if (
    url.username || url.password || url.search || url.hash ||
    !(url.protocol === "https:" || (localHttp && sameOrigin))
  ) {
    throw new Error("Use the same-origin API or an HTTPS LINKA API address. HTTP is limited to local development.");
  }
  return url.origin;
}

async function apiRequest(origin, path, { method = "GET", body, token } = {}) {
  const response = await fetch(origin + path, {
    method,
    credentials: "same-origin",
    cache: "no-store",
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: "Bearer " + token } : {})
    },
    body: body !== undefined ? JSON.stringify(body) : undefined
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const code = typeof payload.error === "string" ? payload.error : "request_failed";
    const messages = {
      invalid_credentials: "Email or password is incorrect.",
      email_already_registered: "An account already exists for this email.",
      device_already_linked: "This browser identity is already linked to another account.",
      rate_limited: "Too many attempts. Try again later.",
      database_not_configured: "The LINKA account service is not available on this server.",
      account_recovery_unavailable: "Password recovery is not enabled because email delivery is not configured."
    };
    const error = new Error(messages[code] || "LINKA request failed (" + code + ").");
    error.status = response.status;
    error.code = code;
    throw error;
  }
  return payload;
}

function showStatus(message, kind = "info") {
  const element = document.getElementById("accountStatus");
  element.textContent = message;
  element.dataset.kind = kind;
}

function setBusy(busy) {
  for (const id of ["accountRegister", "accountLogin", "accountLogout", "accountRecovery"]) {
    const button = document.getElementById(id);
    if (button) button.disabled = busy;
  }
}

function showProfile(profile) {
  document.getElementById("accountSignedOut").hidden = true;
  document.getElementById("accountSignedIn").hidden = false;
  document.getElementById("accountProfileName").textContent =
    profile.displayName || profile.email || "LINKA account";
  document.getElementById("accountProfileEmail").textContent = profile.email || "";
  document.getElementById("accountProfileDevice").textContent =
    "This browser device · " + String(profile.deviceId || "").slice(0, 8).toUpperCase();
  document.getElementById("accountProfileVerification").textContent =
    profile.emailVerified ? "Email verified" : "Email verification is not enabled yet.";
  document.getElementById("accountProfileExpiry").textContent =
    profile.expiresAt ? "Session expires " + new Date(profile.expiresAt).toLocaleString() : "";
}

function showSignedOut() {
  document.getElementById("accountSignedOut").hidden = false;
  document.getElementById("accountSignedIn").hidden = true;
}

async function createDeviceSession(origin) {
  const identity = await getOrCreateBrowserIdentity();
  const challenge = await apiRequest(origin, "/v1/auth/challenge", {
    method: "POST",
    body: { deviceId: identity.deviceId }
  });
  const signatureBase64 = await signLinkaDeviceChallenge(identity.privateKey, {
    challengeId: challenge.challengeId,
    nonce: challenge.nonce,
    deviceId: identity.deviceId,
    issuedAt: challenge.issuedAt
  });
  const verification = await apiRequest(origin, "/v1/auth/verify", {
    method: "POST",
    body: {
      challengeId: challenge.challengeId,
      deviceId: identity.deviceId,
      publicKeyBase64: identity.publicKeyBase64,
      signatureBase64
    }
  });
  return verification.accessToken;
}

async function authenticate(mode) {
  setBusy(true);
  showStatus("Verifying this browser device and contacting LINKA…");
  try {
    const origin = normalizeApiOrigin(
      document.getElementById("accountApiOrigin").value || location.origin
    );
    document.getElementById("accountApiOrigin").value = origin;
    const deviceToken = await createDeviceSession(origin);
    const email = document.getElementById("accountEmail").value.trim();
    const password = document.getElementById("accountPassword").value;
    const body = {
      email,
      password,
      platform: "web",
      deviceName: "LINKA browser " + navigator.userAgent.slice(0, 70)
    };
    let response;
    if (mode === "register") {
      body.displayName = document.getElementById("accountDisplayName").value.trim();
      response = await apiRequest(origin, "/v1/accounts/register", {
        method: "POST", token: deviceToken, body
      });
    } else {
      response = await apiRequest(origin, "/v1/accounts/login", {
        method: "POST", token: deviceToken, body
      });
    }
    if (typeof response.accessToken !== "string" || response.accessToken.length !== 43) {
      throw new Error("The LINKA server returned an invalid account session.");
    }

    // Tab-scoped token: the browser does not persist a reusable bearer token
    // beyond this tab. The P-256 device key itself remains non-exportable in IndexedDB.
    sessionStorage.setItem(SESSION_KEY, response.accessToken);
    document.getElementById("accountPassword").value = "";
    const profile = await apiRequest(origin, "/v1/me", { token: response.accessToken });
    showProfile(profile);
    showStatus(
      mode === "register"
        ? "LINKA account created and this browser device linked successfully."
        : "Signed in to LINKA on this browser device.",
      "success"
    );
  } catch (error) {
    showStatus(error.message || "Unable to connect to LINKA.", "error");
  } finally {
    setBusy(false);
  }
}

async function restoreAccountSession() {
  const token = sessionStorage.getItem(SESSION_KEY);
  if (!token) return;
  try {
    const origin = normalizeApiOrigin(
      document.getElementById("accountApiOrigin").value || location.origin
    );
    const profile = await apiRequest(origin, "/v1/me", { token });
    showProfile(profile);
    showStatus("Your LINKA account is active in this browser tab.", "success");
  } catch (error) {
    if (shouldClearAccountSession(error.status)) {
      sessionStorage.removeItem(SESSION_KEY);
      showSignedOut();
    }
    showStatus(
      error.message || "The browser session could not be restored.",
      "error"
    );
  }
}

async function logout() {
  const token = sessionStorage.getItem(SESSION_KEY);
  if (!token) {
    showSignedOut();
    return;
  }
  setBusy(true);
  try {
    const origin = normalizeApiOrigin(
      document.getElementById("accountApiOrigin").value || location.origin
    );
    await apiRequest(origin, "/v1/auth/logout", {
      method: "POST", body: {}, token
    });
    sessionStorage.removeItem(SESSION_KEY);
    showSignedOut();
    document.getElementById("accountPassword").value = "";
    showStatus("You are signed out of LINKA in this browser tab.", "success");
  } catch (error) {
    showStatus(error.message || "Unable to sign out.", "error");
  } finally {
    setBusy(false);
  }
}

async function requestRecovery() {
  setBusy(true);
  try {
    const origin = normalizeApiOrigin(
      document.getElementById("accountApiOrigin").value || location.origin
    );
    await apiRequest(origin, "/v1/accounts/recovery", {
      method: "POST",
      body: { email: document.getElementById("accountEmail").value.trim() }
    });
    showStatus("Check your email for next steps.");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(false);
  }
}

function initializeAccountPage() {
  const originInput = document.getElementById("accountApiOrigin");
  if (!originInput) return;
  originInput.value = location.origin;
  document.getElementById("accountRegister").addEventListener("click", () => authenticate("register"));
  document.getElementById("accountLogin").addEventListener("click", () => authenticate("login"));
  document.getElementById("accountLogout").addEventListener("click", logout);
  document.getElementById("accountRecovery").addEventListener("click", requestRecovery);
  restoreAccountSession().catch(error => showStatus(error.message, "error"));
}

initializeAccountPage();
