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
      account_recovery_unavailable: "Password recovery is not enabled because email delivery is not configured.",
      wallet_not_found: "A LINKA wallet has not been provisioned for this account yet.",
      invalid_wallet_cursor: "Wallet activity pagination could not be continued. Refresh the activity list.",
      database_not_configured: "LINKA wallet services are unavailable on this server."
    };
    const error = new Error(messages[code] || "LINKA request failed (" + code + ").");
    error.status = response.status;
    error.code = code;
    throw error;
  }
  return payload;
}

let walletSession = null;
let walletCursor = null;
let walletLoadSequence = 0;
let walletLoading = false;

function setWalletStatus(message, kind = "info") {
  const status = document.getElementById("walletStatus");
  if (!status) return;
  status.textContent = message;
  status.dataset.kind = kind;
}

function renderWalletBalances(wallet) {
  const container = document.getElementById("walletBalances");
  if (!container) return;
  container.replaceChildren();
  const values = [
    ["Purchased", wallet.purchased],
    ["Promotional", wallet.promotional],
    ["Sponsored", wallet.sponsored],
    ["Total", wallet.total, true]
  ];
  for (const [label, amount, isTotal] of values) {
    const card = document.createElement("div");
    card.className = "wallet-balance-card" + (isTotal ? " total" : "");
    const caption = document.createElement("span");
    caption.textContent = label;
    const value = document.createElement("strong");
    value.textContent = Number(amount).toLocaleString();
    card.append(caption, value);
    container.append(card);
  }
}

function renderWalletEvents(events, append = false) {
  const container = document.getElementById("walletLedger");
  if (!container) return;
  if (!append) container.replaceChildren();
  if (events.length === 0 && !append) {
    const empty = document.createElement("p");
    empty.className = "wallet-ledger-empty";
    empty.textContent = "No wallet activity has been recorded for this account.";
    container.append(empty);
    return;
  }
  for (const event of events) {
    const row = document.createElement("div");
    row.className = "wallet-ledger-row";
    const details = document.createElement("div");
    const heading = document.createElement("div");
    heading.className = "wallet-ledger-title";
    heading.textContent = String(event.eventType || "wallet activity")
      .replaceAll("_", " ").toLowerCase();
    const meta = document.createElement("div");
    meta.className = "wallet-ledger-meta";
    const time = event.createdAt ? new Date(event.createdAt).toLocaleString() : "Time unavailable";
    meta.textContent = String(event.creditSource || "credit") + " · " + time;
    details.append(heading, meta);
    const amount = document.createElement("div");
    amount.className = "wallet-ledger-amount";
    const isDebit = ["CALL_USAGE", "MESSAGE_USAGE", "NUMBER_RENTAL", "NUMBER_RENEWAL"]
      .includes(event.eventType);
    amount.textContent = (isDebit ? "−" : "+") +
      Number(event.amountCreditUnits).toLocaleString() + " credits";
    row.append(details, amount);
    container.append(row);
  }
}

async function loadWallet({ reset = true } = {}) {
  if (!walletSession || walletLoading) return;
  const activeSession = walletSession;
  const sequence = ++walletLoadSequence;
  walletLoading = true;
  const refresh = document.getElementById("walletRefresh");
  const more = document.getElementById("walletLoadMore");
  if (refresh) refresh.disabled = true;
  if (more) more.disabled = true;
  if (reset) {
    walletCursor = null;
    setWalletStatus("Refreshing your account balance…");
  } else {
    setWalletStatus("Loading more activity…");
  }

  try {
    if (reset) {
      const snapshot = await apiRequest(activeSession.origin, "/v1/wallet", {
        token: activeSession.token
      });
      if (walletSession !== activeSession || sequence !== walletLoadSequence) return;
      renderWalletBalances(snapshot.wallet);
    }

    const query = new URLSearchParams({ limit: "10" });
    if (!reset && walletCursor) {
      query.set("before", walletCursor.before);
      query.set("beforeEventId", walletCursor.beforeEventId);
    }
    const page = await apiRequest(
      activeSession.origin,
      "/v1/wallet/ledger?" + query.toString(),
      { token: activeSession.token }
    );
    if (walletSession !== activeSession || sequence !== walletLoadSequence) return;
    renderWalletEvents(page.events || [], !reset);
    walletCursor = page.nextCursor || null;
    if (more) more.hidden = walletCursor === null;
    setWalletStatus("Balances and wallet activity loaded from your authenticated LINKA account.", "success");
  } catch (error) {
    if (walletSession !== activeSession || sequence !== walletLoadSequence) return;
    setWalletStatus(error.message || "Unable to load wallet information.", "error");
    if (more) more.hidden = walletCursor === null;
  } finally {
    if (sequence === walletLoadSequence) {
      walletLoading = false;
      if (refresh) refresh.disabled = false;
      if (more) more.disabled = false;
    }
  }
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

function showProfile(profile, origin, token) {
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
  walletSession = { origin, token };
  void loadWallet({ reset: true });
}

function showSignedOut() {
  walletSession = null;
  walletCursor = null;
  walletLoadSequence += 1;
  walletLoading = false;
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
    showProfile(profile, origin, response.accessToken);
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
    showProfile(profile, origin, token);
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
  document.getElementById("walletRefresh").addEventListener("click", () => loadWallet({ reset: true }));
  document.getElementById("walletLoadMore").addEventListener("click", () => loadWallet({ reset: false }));
  restoreAccountSession().catch(error => showStatus(error.message, "error"));
}

initializeAccountPage();
