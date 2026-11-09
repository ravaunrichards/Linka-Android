import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { chromium, firefox } from "playwright";

const browserName = (process.env.PLAYWRIGHT_BROWSER ?? "chromium").trim().toLowerCase();
const browserType = browserName === "chromium"
  ? chromium
  : browserName === "firefox"
    ? firefox
    : null;
if (!browserType) {
  throw new Error('PLAYWRIGHT_BROWSER must be either "chromium" or "firefox".');
}
const apiOrigin = new URL(process.env.LINKA_API_URL ?? "http://127.0.0.1:8080").origin;
const SESSION_KEY = "linka.account.session.v1";
const browserErrors = [];

async function waitForStatus(page, expected) {
  const status = page.locator("#accountStatus");
  await status.waitFor({ state: "visible", timeout: 15_000 });
  await page.waitForFunction(
    text => document.getElementById("accountStatus")?.textContent?.includes(text),
    expected,
    { timeout: 15_000 }
  );
  return status.innerText();
}

async function inspectStoredIdentity(page) {
  return page.evaluate(() => new Promise((resolve, reject) => {
    const request = indexedDB.open("linka-browser-device-v1", 1);
    request.onerror = () => reject(new Error("Could not open LINKA browser device storage."));
    request.onsuccess = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains("device")) {
        database.close();
        reject(new Error("LINKA browser device store was not created."));
        return;
      }
      const transaction = database.transaction("device", "readonly");
      const read = transaction.objectStore("device").get("current");
      read.onerror = () => {
        database.close();
        reject(new Error("Could not read the LINKA browser identity."));
      };
      read.onsuccess = () => {
        const identity = read.result;
        database.close();
        if (!identity?.privateKey || !identity?.deviceId) {
          reject(new Error("LINKA did not persist a browser signing identity."));
          return;
        }
        resolve({
          deviceId: identity.deviceId,
          privateKeyType: identity.privateKey.type,
          privateKeyExtractable: identity.privateKey.extractable
        });
      };
    };
  }));
}

async function readOwnProfile(page) {
  return page.evaluate(async sessionKey => {
    const token = sessionStorage.getItem(sessionKey);
    if (!token) return { token: null, status: null, profile: null };
    const response = await fetch("/v1/me", {
      headers: { Authorization: "Bearer " + token },
      cache: "no-store"
    });
    const profile = await response.json().catch(() => ({}));
    return { token, status: response.status, profile };
  }, SESSION_KEY);
}

async function openAccountPage(context) {
  const page = await context.newPage();
  page.on("pageerror", error => browserErrors.push(error.message));
  await page.goto(apiOrigin + "/#account", { waitUntil: "domcontentloaded" });
  await page.locator("#accountEmail").waitFor({ state: "visible", timeout: 15_000 });
  await page.waitForFunction(
    expected => document.getElementById("accountApiOrigin")?.value === expected,
    apiOrigin,
    { timeout: 15_000 }
  );
  assert.equal(
    await page.locator("#accountApiOrigin").inputValue(),
    apiOrigin,
    "browser account page should default to its same-origin API"
  );
  return page;
}

async function main() {
  const browser = await browserType.launch({
    headless: true,
    ...(browserName === "chromium" ? { args: ["--no-sandbox"] } : {})
  });
  const contexts = [];
  try {
    const firstContext = await browser.newContext({
      viewport: { width: 1280, height: 900 }
    });
    contexts.push(firstContext);
    const firstPage = await openAccountPage(firstContext);

    assert.equal(
      await firstPage.evaluate(() => globalThis.isSecureContext && !!crypto.subtle && "indexedDB" in globalThis),
      true,
      "browser must provide secure WebCrypto and IndexedDB"
    );

    const unique = randomUUID();
    const email = browserName + "-e2e+" + unique + "@example.test";
    const password = "LINKA-" + browserName + "-E2E-" + unique;
    const displayName = "LINKA " + browserName + " E2E";

    await firstPage.locator("#accountEmail").fill(email);
    await firstPage.locator("#accountDisplayName").fill(displayName);
    await firstPage.locator("#accountPassword").fill(password);
    await firstPage.locator("#accountRegister").click();

    await firstPage.locator("#accountSignedIn").waitFor({ state: "visible", timeout: 20_000 });
    await waitForStatus(firstPage, "account created and this browser device linked successfully");
    assert.equal(await firstPage.locator("#accountProfileEmail").innerText(), email);
    assert.equal(await firstPage.locator("#accountProfileName").innerText(), displayName);

    const firstSession = await readOwnProfile(firstPage);
    assert.equal(firstSession.status, 200, "new browser account token should authenticate");
    assert.match(firstSession.token ?? "", /^[A-Za-z0-9_-]{43}$/);
    const firstIdentity = await inspectStoredIdentity(firstPage);
    assert.equal(firstIdentity.privateKeyType, "private");
    assert.equal(firstIdentity.privateKeyExtractable, false);

    await firstPage.reload({ waitUntil: "domcontentloaded" });
    await firstPage.locator("#accountSignedIn").waitFor({ state: "visible", timeout: 15_000 });
    await waitForStatus(firstPage, "account is active in this browser tab");
    const restoredSession = await readOwnProfile(firstPage);
    assert.equal(restoredSession.status, 200, "tab session should restore after a page reload");
    assert.equal(restoredSession.token, firstSession.token, "page reload should preserve the tab-scoped account token");
    const restoredIdentity = await inspectStoredIdentity(firstPage);
    assert.equal(restoredIdentity.deviceId, firstIdentity.deviceId, "IndexedDB device identity should persist across reload");

    // A separate browser context has isolated sessionStorage and IndexedDB,
    // so it represents a distinct browser device for the second-device flow.
    const secondContext = await browser.newContext({
      viewport: { width: 1280, height: 900 }
    });
    contexts.push(secondContext);
    const secondPage = await openAccountPage(secondContext);
    assert.equal(await secondPage.evaluate(key => sessionStorage.getItem(key), SESSION_KEY), null);

    await secondPage.locator("#accountEmail").fill(email);
    await secondPage.locator("#accountPassword").fill(password);
    await secondPage.locator("#accountLogin").click();
    await secondPage.locator("#accountSignedIn").waitFor({ state: "visible", timeout: 20_000 });
    await waitForStatus(secondPage, "Signed in to LINKA on this browser device");
    assert.equal(await secondPage.locator("#accountProfileEmail").innerText(), email);

    const secondSession = await readOwnProfile(secondPage);
    assert.equal(secondSession.status, 200);
    assert.notEqual(secondSession.token, firstSession.token, "each browser device must receive its own account token");
    const secondIdentity = await inspectStoredIdentity(secondPage);
    assert.notEqual(secondIdentity.deviceId, firstIdentity.deviceId, "separate browser contexts must use distinct device identities");
    assert.equal(secondIdentity.privateKeyExtractable, false);

    // A third isolated browser context is a distinct account/device recipient.
    const thirdContext = await browser.newContext({
      viewport: { width: 1280, height: 900 }
    });
    contexts.push(thirdContext);
    const thirdPage = await openAccountPage(thirdContext);
    const recipientUnique = randomUUID();
    const recipientEmail = browserName + "-recipient+" + recipientUnique + "@example.test";
    const recipientPassword = "LINKA-recipient-" + browserName + "-" + recipientUnique;
    const recipientName = "LINKA " + browserName + " recipient";
    await thirdPage.locator("#accountEmail").fill(recipientEmail);
    await thirdPage.locator("#accountDisplayName").fill(recipientName);
    await thirdPage.locator("#accountPassword").fill(recipientPassword);
    await thirdPage.locator("#accountRegister").click();
    await thirdPage.locator("#accountSignedIn").waitFor({ state: "visible", timeout: 20_000 });
    await waitForStatus(thirdPage, "account created and this browser device linked successfully");
    const recipientSession = await readOwnProfile(thirdPage);
    assert.equal(recipientSession.status, 200);
    assert.notEqual(recipientSession.profile.accountId, firstSession.profile.accountId);

    const secretMessage = "LINKA E2EE browser integration " + randomUUID();
    await firstPage.locator("#chatParticipantIds").fill(recipientSession.profile.accountId);
    await firstPage.locator("#chatCreateConversation").click();
    await firstPage.waitForFunction(
      () => document.getElementById("chatStatus")?.textContent?.includes("Conversation ready"),
      null,
      { timeout: 15_000 }
    );
    const conversationId = await firstPage.locator("#chatConversationSelect").inputValue();
    assert.match(conversationId, /^[0-9a-f-]{36}$/i);
    await firstPage.locator("#chatMessageDraft").fill(secretMessage);
    await firstPage.locator("#chatSendMessage").click();
    await firstPage.waitForFunction(
      text => [...document.querySelectorAll("#chatMessages .chat-message p")]
        .some(element => element.textContent === text),
      secretMessage,
      { timeout: 20_000 }
    );

    // The same account's second browser device and the recipient account each
    // receive a different wrapped content-key envelope and decrypt locally.
    await secondPage.locator("#chatRefreshConversations").click();
    await secondPage.waitForFunction(
      text => [...document.querySelectorAll("#chatMessages .chat-message p")]
        .some(element => element.textContent === text),
      secretMessage,
      { timeout: 20_000 }
    );
    await thirdPage.locator("#chatRefreshConversations").click();
    await thirdPage.waitForFunction(
      text => [...document.querySelectorAll("#chatMessages .chat-message p")]
        .some(element => element.textContent === text),
      secretMessage,
      { timeout: 20_000 }
    );

    const storedMessage = await firstPage.evaluate(async ({ sessionKey, conversationId }) => {
      const token = sessionStorage.getItem(sessionKey);
      const response = await fetch("/v1/conversations/" + encodeURIComponent(conversationId) + "/messages", {
        headers: { Authorization: "Bearer " + token },
        cache: "no-store"
      });
      const payload = await response.json();
      return { status: response.status, message: payload.messages?.[0] };
    }, { sessionKey: SESSION_KEY, conversationId });
    assert.equal(storedMessage.status, 200);
    assert.equal(storedMessage.message.encryptionVersion, "LINKA-E2EE-2");
    assert.equal(storedMessage.message.ciphertext.includes(secretMessage), false);
    assert.equal(storedMessage.message.keyEnvelope.recipientDeviceId, firstIdentity.deviceId);

    const secondOwnDevice = await inspectStoredIdentity(secondPage);
    const recipientDevice = await inspectStoredIdentity(thirdPage);
    assert.notEqual(secondOwnDevice.deviceId, firstIdentity.deviceId);
    assert.notEqual(recipientDevice.deviceId, firstIdentity.deviceId);
    assert.notEqual(recipientDevice.deviceId, secondOwnDevice.deviceId);

    await secondPage.locator("#accountLogout").click();
    await secondPage.locator("#accountSignedOut").waitFor({ state: "visible", timeout: 15_000 });
    await waitForStatus(secondPage, "You are signed out of LINKA in this browser tab");
    assert.equal(await secondPage.evaluate(key => sessionStorage.getItem(key), SESSION_KEY), null);
    assert.equal((await readOwnProfile(secondPage)).status, null);

    const firstStillActive = await readOwnProfile(firstPage);
    assert.equal(firstStillActive.status, 200, "logging out on the second device must not revoke the first device");
    assert.equal(firstStillActive.profile.accountId, firstSession.profile.accountId);

    await firstPage.locator("#accountLogout").click();
    await firstPage.locator("#accountSignedOut").waitFor({ state: "visible", timeout: 15_000 });
    await waitForStatus(firstPage, "You are signed out of LINKA in this browser tab");
    assert.equal(await firstPage.evaluate(key => sessionStorage.getItem(key), SESSION_KEY), null);

    await firstPage.locator("#accountRecovery").click();
    await waitForStatus(firstPage, "Password recovery is not enabled because email delivery is not configured");

    assert.deepEqual(browserErrors, [], "browser account page should not raise uncaught JavaScript errors");
    console.log("PASS: " + browserName.toUpperCase() + " browser UI registration, non-exportable IndexedDB identity, tab session restore, second-context login, independent device logout, recovery limitation and clean page execution.");
  } finally {
    await Promise.all(contexts.map(context => context.close()));
    await browser.close();
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
