"use strict";
// No URL history, bodies, headers or account data are logged, sent or persisted.
// The native bridge accepts fixed aggregate health fields only, with no actions.
let blocked = 0;
let probeBlocked = false;
let capabilities = null;
let nonce = null;
function health() {
  return browser.runtime.sendNativeMessage("max_privacy_health", {
    version: "0.3.1", ready: true, probeBlocked, blocked, capabilities
  }).catch(() => null);
}
browser.webRequest.onBeforeRequest.addListener(details => {
  if (nonce && details.url === "https://guard-selftest.invalid/probe?nonce=" + nonce) {
    probeBlocked = true;
  } else if (!details.url.startsWith("https://guard-selftest.invalid/")) {
    blocked = Math.min(blocked + 1, 1000000000);
  }
  health();
  return {cancel: true};
}, {urls: ["*://apptracer.ru/*", "*://*.apptracer.ru/*", "*://guard-selftest.invalid/*"]}, ["blocking"]);

// Registration happens before the initial acknowledgement. Retry only local IPC
// until the host has installed its delegate; never contact an external service.
let attempts = 0;
const timer = setInterval(async () => {
  const reply = await health();
  if (reply?.ok || ++attempts >= 20) clearInterval(timer);
}, 250);
health();

browser.runtime.onMessage.addListener((message, sender) => {
  if (sender.frameId !== 0 || !sender.url?.startsWith("https://web.max.ru/")
      || message?.type !== "privacy-proof" || !Array.isArray(message.capabilities)
      || message.capabilities.length !== 6 || !message.capabilities.every(x => typeof x === "boolean")
      || typeof message.nonce !== "string" || !/^[a-f0-9-]{36}$/.test(message.nonce)) return;
  capabilities = message.capabilities;
  nonce = message.nonce; // Random per page, never an account/device identifier; memory only.
  probeBlocked = false;
  health();
  return Promise.resolve(true);
});
