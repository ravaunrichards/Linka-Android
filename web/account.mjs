import {
  generateBrowserSigningIdentity,
  shouldClearAccountSession,
  signBrowserMessagingKey,
  signLinkaDeviceChallenge
} from "./auth-crypto.mjs";
import {
  decryptAttachmentForDevice,
  decryptMessageForDevice,
  encryptAttachmentForDevices,
  encryptMessageForDevices,
  generateBrowserEncryptionIdentity
} from "./messaging-crypto.mjs";

const DATABASE_NAME = "linka-browser-device-v1";
const OBJECT_STORE = "device";
const SESSION_KEY = "linka.account.session.v1";
let currentAccountProfile = null;
let browserMessagingIdentity = null;
let activeConversationMembers = [];
let accountDevices = [];

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
      device_not_found: "That device could not be found in this LINKA account.",
      invalid_device_management: "LINKA could not validate this device-management request.",
      wallet_not_found: "A LINKA wallet has not been provisioned for this account yet.",
      invalid_wallet_cursor: "Wallet activity pagination expired. Refresh the activity list."
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
  for (const id of ["accountRegister", "accountLogin", "accountLogout", "accountRecovery", "accountDevicesRefresh", "walletRefresh", "walletLoadMore", "chatCreateConversation", "chatSendMessage", "chatRefreshConversations"]) {
    const button = document.getElementById(id);
    if (button) button.disabled = busy;
  }
  document.querySelectorAll(".account-device-revoke").forEach(button => {
    button.disabled = busy;
  });
}

function renderAccountDevices(devices) {
  const container = document.getElementById("accountDevicesList");
  const status = document.getElementById("accountDevicesStatus");
  if (!container || !status) return;
  container.replaceChildren();
  accountDevices = Array.isArray(devices) ? devices : [];

  if (accountDevices.length === 0) {
    const empty = document.createElement("p");
    empty.className = "account-device-empty";
    empty.textContent = "No registered devices are currently listed for this account.";
    container.append(empty);
    status.textContent = "Device list loaded · 0 devices";
    return;
  }

  for (const device of accountDevices) {
    const row = document.createElement("article");
    row.className = "account-device-row";
    row.dataset.deviceId = String(device.deviceId || "");
    row.dataset.status = String(device.status || "unknown");

    const details = document.createElement("div");
    details.className = "account-device-details";
    const name = document.createElement("strong");
    name.textContent = String(device.deviceName || device.platform || "LINKA device");
    const identity = document.createElement("span");
    identity.className = "account-device-meta";
    identity.textContent = "ID " + String(device.deviceId || "").slice(0, 8).toUpperCase() +
      (device.current ? " · This device" : "");
    const state = document.createElement("span");
    state.className = "account-device-meta account-device-status";
    state.textContent = "Status: " + String(device.status || "unknown") +
      " · Active sessions: " + Number(device.activeSessionCount || 0);
    const seen = document.createElement("span");
    seen.className = "account-device-meta";
    seen.textContent = "Last seen: " +
      (device.lastSeenAt ? new Date(device.lastSeenAt).toLocaleString() : "Not recorded");
    details.append(name, identity, state, seen);
    row.append(details);

    if (!device.current && device.status === "active" && device.deviceId) {
      const revoke = document.createElement("button");
      revoke.type = "button";
      revoke.className = "button secondary account-device-revoke";
      revoke.dataset.deviceId = device.deviceId;
      revoke.textContent = "Revoke device";
      revoke.addEventListener("click", () => revokeBrowserDevice(device.deviceId));
      row.append(revoke);
    }
    container.append(row);
  }
  status.textContent = "Device list loaded · " + accountDevices.length + " device(s)";
}

async function refreshAccountDevices(origin, token) {
  const session = origin && token ? { origin, token } : activeApiSession();
  const result = await apiRequest(session.origin, "/v1/me/devices", {
    token: session.token
  });
  renderAccountDevices(result.devices || []);
  return accountDevices;
}

async function revokeBrowserDevice(deviceId) {
  const device = accountDevices.find(item => item.deviceId === deviceId);
  if (!device || device.current || device.status !== "active") return;
  const name = device.deviceName || device.platform || "this device";
  if (!window.confirm(
    "Revoke " + name + "? This ends its active LINKA sessions, disables its relay permission and retires its current messaging key."
  )) return;

  setBusy(true);
  try {
    const { token, origin } = activeApiSession();
    await apiRequest(origin, "/v1/me/devices/" + encodeURIComponent(deviceId), {
      method: "DELETE",
      token
    });
    await refreshAccountDevices(origin, token);
    showStatus(
      "Device revoked. Its active sessions and messaging key are disabled. Content already copied by that device cannot be remotely erased.",
      "success"
    );
  } catch (error) {
    showStatus(error.message || "Unable to revoke this LINKA device.", "error");
  } finally {
    setBusy(false);
  }
}

function showProfile(profile, origin, token) {
  currentAccountProfile = profile;
  walletSession = origin && token ? { origin, token } : null;
  if (walletSession) void loadWallet({ reset: true });
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
  walletSession = null;
  walletCursor = null;
  walletLoadSequence += 1;
  walletLoading = false;
  const walletBalances = document.getElementById("walletBalances");
  if (walletBalances) walletBalances.replaceChildren();
  const walletLedger = document.getElementById("walletLedger");
  if (walletLedger) walletLedger.replaceChildren();
  const walletStatus = document.getElementById("walletStatus");
  if (walletStatus) walletStatus.textContent = "Sign in to view your LINKA wallet.";
  const walletMore = document.getElementById("walletLoadMore");
  if (walletMore) walletMore.hidden = true;
  currentAccountProfile = null;
  browserMessagingIdentity = null;
  activeConversationMembers = [];
  accountDevices = [];
  const accountDevicesList = document.getElementById("accountDevicesList");
  if (accountDevicesList) accountDevicesList.replaceChildren();
  const accountDevicesStatus = document.getElementById("accountDevicesStatus");
  if (accountDevicesStatus) accountDevicesStatus.textContent = "Sign in to view devices registered to this account.";
  const chatList = document.getElementById("chatMessages");
  if (chatList) chatList.replaceChildren();
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
    await initializeMessagingPanel(origin, response.accessToken);
    await refreshAccountDevices(origin, response.accessToken);
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
    await initializeMessagingPanel(origin, token);
    await refreshAccountDevices(origin, token);
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

async function getOrCreateBrowserEncryptionIdentity() {
  const database = await openIdentityDatabase();
  try {
    const identity = await databaseRequest(database, "readonly", store => store.get("current"));
    if (identity?.encryptionPrivateKey && identity?.encryptionPublicKeyBase64) {
      return Object.freeze({
        privateKey: identity.encryptionPrivateKey,
        publicKeyBase64: identity.encryptionPublicKeyBase64,
        algorithm: "ECDH-P256"
      });
    }
    const generated = await generateBrowserEncryptionIdentity();
    const updated = {
      ...(identity || {}),
      encryptionPrivateKey: generated.privateKey,
      encryptionPublicKeyBase64: generated.publicKeyBase64
    };
    await databaseRequest(database, "readwrite", store => store.put(updated, "current"));
    return generated;
  } finally {
    database.close();
  }
}

async function registerBrowserEncryptionKey(origin, token) {
  browserMessagingIdentity = await getOrCreateBrowserEncryptionIdentity();
  const signingIdentity = await getOrCreateBrowserIdentity();
  const signatureBase64 = await signBrowserMessagingKey(
    signingIdentity.privateKey,
    signingIdentity.deviceId,
    browserMessagingIdentity.algorithm,
    browserMessagingIdentity.publicKeyBase64
  );
  const registered = await apiRequest(origin, "/v1/me/encryption-key", {
    method: "PUT",
    token,
    body: {
      algorithm: browserMessagingIdentity.algorithm,
      publicKeyBase64: browserMessagingIdentity.publicKeyBase64,
      signatureBase64
    }
  });
  return registered;
}

function activeApiSession() {
  const token = sessionStorage.getItem(SESSION_KEY);
  if (!token || !currentAccountProfile?.accountId || !currentAccountProfile?.deviceId) {
    throw new Error("Sign in to LINKA before using encrypted messaging.");
  }
  const origin = normalizeApiOrigin(
    document.getElementById("accountApiOrigin").value || location.origin
  );
  return { token, origin };
}

function chatStatus(message, kind = "info") {
  const element = document.getElementById("chatStatus");
  if (!element) return;
  element.textContent = message;
  element.dataset.kind = kind;
}

function renderConversationOptions(conversations, preferredId) {
  const select = document.getElementById("chatConversationSelect");
  if (!select) return;
  const previous = preferredId || select.value;
  select.replaceChildren();
  for (const conversation of conversations) {
    const option = document.createElement("option");
    option.value = conversation.conversationId;
    const names = (conversation.members || [])
      .map(member => member.displayName || member.accountId.slice(0, 8))
      .join(", ");
    option.textContent = (conversation.kind === "group" ? "Group · " : "Direct · ") + names;
    select.append(option);
  }
  if (previous && conversations.some(item => item.conversationId === previous)) {
    select.value = previous;
  } else if (conversations.length > 0) {
    select.value = conversations[0].conversationId;
  }
}

async function refreshConversations(preferredId) {
  const { token, origin } = activeApiSession();
  const result = await apiRequest(origin, "/v1/conversations?limit=50", { token });
  renderConversationOptions(result.conversations || [], preferredId);
  if ((result.conversations || []).length === 0) {
    activeConversationMembers = [];
    document.getElementById("chatMessages").replaceChildren();
    chatStatus("No conversations yet. Start a direct or group conversation using account IDs.");
    return;
  }
  await loadSelectedConversation();
}

async function loadSelectedConversation() {
  const conversationId = document.getElementById("chatConversationSelect").value;
  if (!conversationId) return;
  const { token, origin } = activeApiSession();
  const [directory, history] = await Promise.all([
    apiRequest(origin, "/v1/conversations/" + encodeURIComponent(conversationId) + "/encryption-devices", { token }),
    apiRequest(origin, "/v1/conversations/" + encodeURIComponent(conversationId) + "/messages?limit=100", { token })
  ]);
  activeConversationMembers = directory.devices || [];
  const messageList = document.getElementById("chatMessages");
  messageList.replaceChildren();
  const items = [...(history.messages || [])].reverse();
  const readableMessageIds = [];
  for (const message of items) {
    const article = document.createElement("article");
    article.className = "chat-message";
    const heading = document.createElement("strong");
    heading.textContent = message.senderAccountId === currentAccountProfile.accountId
      ? "You"
      : (activeConversationMembers.find(item => item.accountId === message.senderAccountId)?.displayName || "LINKA member");
    const body = document.createElement("p");
    if (message.encryptionVersion === "LINKA-E2EE-2" && message.keyEnvelope && browserMessagingIdentity) {
      try {
        const decrypted = await decryptMessageForDevice({
          ciphertext: message.ciphertext,
          encryptionVersion: message.encryptionVersion,
          conversationId,
          senderAccountId: message.senderAccountId,
          deviceId: currentAccountProfile.deviceId,
          privateKey: browserMessagingIdentity.privateKey,
          keyEnvelope: message.keyEnvelope
        });
        let parsed = null;
        try { parsed = JSON.parse(decrypted); } catch { /* Legacy text body remains supported. */ }
        if (parsed && typeof parsed.text === "string" && Array.isArray(parsed.attachments)) {
          body.textContent = parsed.text;
          for (const attachment of parsed.attachments) {
            if (!attachment || typeof attachment.attachmentId !== "string" ||
                typeof attachment.filename !== "string" ||
                !Number.isSafeInteger(attachment.sizeBytes)) continue;
            const download = document.createElement("button");
            download.type = "button";
            download.className = "chat-attachment";
            download.textContent = "Download " + attachment.filename + " (" +
              attachment.sizeBytes.toLocaleString() + " bytes)";
            download.addEventListener("click", async () => {
              download.disabled = true;
              try {
                await downloadEncryptedAttachment(conversationId, attachment);
              } catch (error) {
                chatStatus(error.message || "Could not decrypt this attachment.", "error");
              } finally {
                download.disabled = false;
              }
            });
            article.append(download);
          }
        } else {
          body.textContent = decrypted;
        }
        if (message.senderAccountId !== currentAccountProfile.accountId && message.messageId) {
          readableMessageIds.push(message.messageId);
        }
      } catch {
        body.textContent = "[Unable to decrypt this message on this device]";
      }
    } else if (message.encryptionVersion === "LINKA-E2EE-2") {
      body.textContent = "[No encrypted key envelope for this device]";
    } else {
      body.textContent = "[Legacy encrypted message cannot be decrypted by this client]";
    }
    const time = document.createElement("time");
    time.dateTime = message.sentAt;
    const receipt = message.receiptSummary;
    const receiptText = receipt
      ? " · Delivered " + receipt.deliveredDevices + "/" + receipt.recipientDevices +
        " · Read " + receipt.readDevices + "/" + receipt.recipientDevices
      : "";
    time.textContent = new Date(message.sentAt).toLocaleString() + receiptText;
    article.append(heading, body, time);
    messageList.append(article);
  }
  const incomingMessageIds = items
    .filter(message => message.senderAccountId !== currentAccountProfile.accountId)
    .map(message => message.messageId)
    .filter(Boolean)
    .slice(0, 100);
  const receiptUrl = "/v1/conversations/" + encodeURIComponent(conversationId) + "/receipts";
  if (incomingMessageIds.length > 0) {
    await apiRequest(origin, receiptUrl, {
      method: "POST",
      token,
      body: { messageIds: incomingMessageIds, state: "delivered" }
    });
  }
  if (readableMessageIds.length > 0) {
    await apiRequest(origin, receiptUrl, {
      method: "POST",
      token,
      body: { messageIds: readableMessageIds.slice(0, 100), state: "read" }
    });
  }
  const missing = activeConversationMembers.filter(item => !item.keyVersion || !item.publicKeyBase64);
  chatStatus(
    missing.length === 0
      ? "End-to-end encryption ready on " + activeConversationMembers.length + " active devices."
      : missing.length + " active device(s) must sign in and register an encryption key before sending.",
    missing.length === 0 ? "success" : "warning"
  );
}

async function createConversationFromForm() {
  const { token, origin } = activeApiSession();
  const raw = document.getElementById("chatParticipantIds").value;
  const participantAccountIds = [...new Set(raw.split(",").map(value => value.trim()).filter(Boolean))];
  if (participantAccountIds.length < 1 || participantAccountIds.length > 49) {
    throw new Error("Enter between 1 and 49 other LINKA account IDs.");
  }
  const body = {
    kind: participantAccountIds.length === 1 ? "direct" : "group",
    participantAccountIds
  };
  const result = await apiRequest(origin, "/v1/conversations", {
    method: "POST",
    token,
    body
  });
  document.getElementById("chatParticipantIds").value = "";
  await refreshConversations(result.conversationId);
  chatStatus("Conversation ready. Every active device must register its ECDH key before messages can be sent.", "success");
}

function safeAttachmentName(value) {
  const cleaned = String(value || "linka-attachment")
    .replace(/[\\/:*?"<>|\\u0000-\\u001f]/g, "_")
    .slice(0, 180);
  return cleaned || "linka-attachment";
}

async function downloadEncryptedAttachment(conversationId, metadata) {
  const { token, origin } = activeApiSession();
  if (!browserMessagingIdentity) throw new Error("This browser device has no messaging decryption key.");
  const response = await apiRequest(
    origin,
    "/v1/conversations/" + encodeURIComponent(conversationId) +
      "/attachments/" + encodeURIComponent(metadata.attachmentId),
    { token }
  );
  const attachment = response.attachment;
  const bytes = await decryptAttachmentForDevice({
    ciphertextBase64: attachment.ciphertextBase64,
    encryptionVersion: attachment.encryptionVersion,
    conversationId,
    deviceId: currentAccountProfile.deviceId,
    privateKey: browserMessagingIdentity.privateKey,
    keyEnvelope: attachment.keyEnvelope
  });
  if (bytes.length !== metadata.sizeBytes ||
      bytes.length !== attachment.plaintextSizeBytes) {
    throw new Error("The decrypted attachment size does not match the encrypted message.");
  }
  const mimeType = typeof metadata.mediaType === "string" &&
    /^[a-z0-9.+-]+\/[a-z0-9.+-]+$/i.test(metadata.mediaType)
      ? metadata.mediaType : "application/octet-stream";
  const objectUrl = URL.createObjectURL(new Blob([bytes], { type: mimeType }));
  try {
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = safeAttachmentName(metadata.filename);
    link.rel = "noopener";
    document.body.append(link);
    link.click();
    link.remove();
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

async function sendEncryptedMessage() {
  const { token, origin } = activeApiSession();
  const conversationId = document.getElementById("chatConversationSelect").value;
  if (!conversationId) throw new Error("Select a conversation first.");
  const input = document.getElementById("chatMessageDraft");
  const plaintext = input.value;
  const attachmentInput = document.getElementById("chatAttachmentInput");
  const file = attachmentInput?.files?.[0] || null;
  if (!plaintext.trim() && !file) throw new Error("Write a message or select an attachment.");
  if (file && (file.size < 1 || file.size > 4 * 1024 * 1024)) {
    throw new Error("Choose a file between 1 byte and 4 MiB.");
  }
  const devicesResponse = await apiRequest(
    origin,
    "/v1/conversations/" + encodeURIComponent(conversationId) + "/encryption-devices",
    { token }
  );
  const devices = devicesResponse.devices || [];
  const missing = devices.filter(device =>
    device.algorithm !== "ECDH-P256" || !device.keyVersion || !device.publicKeyBase64
  );
  if (missing.length > 0) {
    throw new Error("Sending is blocked until all " + missing.length + " unregistered device(s) sign in and register encryption keys.");
  }

  const attachmentMetadata = [];
  const attachmentManifest = [];
  if (file) {
    chatStatus("Encrypting the selected attachment on this device…");
    const encryptedAttachment = await encryptAttachmentForDevices({
      bytes: new Uint8Array(await file.arrayBuffer()),
      conversationId,
      devices
    });
    const uploaded = await apiRequest(
      origin,
      "/v1/conversations/" + encodeURIComponent(conversationId) + "/attachments",
      { method: "POST", token, body: encryptedAttachment }
    );
    const attachmentId = uploaded.attachment.attachmentId;
    attachmentManifest.push({ attachmentId });
    attachmentMetadata.push({
      attachmentId,
      filename: safeAttachmentName(file.name),
      mediaType: file.type || "application/octet-stream",
      sizeBytes: file.size
    });
  }

  const encrypted = await encryptMessageForDevices({
    plaintext: JSON.stringify({ text: plaintext, attachments: attachmentMetadata }),
    conversationId,
    senderAccountId: currentAccountProfile.accountId,
    devices
  });
  await apiRequest(
    origin,
    "/v1/conversations/" + encodeURIComponent(conversationId) + "/messages",
    {
      method: "POST",
      token,
      body: { ...encrypted, attachmentManifest }
    }
  );
  input.value = "";
  if (attachmentInput) attachmentInput.value = "";
  await loadSelectedConversation();
  chatStatus("Encrypted message sent to " + devices.length + " active device(s)" +
    (file ? " with an encrypted attachment." : "."), "success");
}

async function initializeMessagingPanel(origin, token) {
  try {
    await registerBrowserEncryptionKey(origin, token);
    await refreshConversations();
  } catch (error) {
    chatStatus(
      error.message || "Encrypted messaging is not available on this LINKA API.",
      "warning"
    );
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
  document.getElementById("walletRefresh")?.addEventListener("click", () => loadWallet({ reset: true }));
  document.getElementById("walletLoadMore")?.addEventListener("click", () => loadWallet({ reset: false }));
  document.getElementById("accountDevicesRefresh")?.addEventListener("click", async () => {
    setBusy(true);
    try {
      await refreshAccountDevices();
      showStatus("LINKA account devices refreshed.", "success");
    } catch (error) {
      showStatus(error.message || "Could not refresh LINKA devices.", "error");
    } finally {
      setBusy(false);
    }
  });
  document.getElementById("chatCreateConversation")?.addEventListener("click", async () => {
    setBusy(true);
    try { await createConversationFromForm(); }
    catch (error) { chatStatus(error.message || "Could not create this conversation.", "error"); }
    finally { setBusy(false); }
  });
  document.getElementById("chatRefreshConversations")?.addEventListener("click", async () => {
    setBusy(true);
    try { await refreshConversations(); }
    catch (error) { chatStatus(error.message || "Could not refresh conversations.", "error"); }
    finally { setBusy(false); }
  });
  document.getElementById("chatConversationSelect")?.addEventListener("change", async () => {
    try { await loadSelectedConversation(); }
    catch (error) { chatStatus(error.message || "Could not load this conversation.", "error"); }
  });
  document.getElementById("chatSendMessage")?.addEventListener("click", async () => {
    setBusy(true);
    try { await sendEncryptedMessage(); }
    catch (error) { chatStatus(error.message || "Could not send this encrypted message.", "error"); }
    finally { setBusy(false); }
  });
  restoreAccountSession().catch(error => showStatus(error.message, "error"));
}

initializeAccountPage();
