"use strict";
// Isolated extension world: no DOM content, cookies, storage, account, URL path,
// headers or messages are read. Only fixed feature booleans leave this script.
(async () => {
  const video = document.createElement("video");
  const capabilities = [navigator.globalPrivacyControl === true,
    typeof RTCPeerConnection === "undefined",
    typeof navigator.mediaDevices?.getUserMedia === "undefined",
    typeof navigator.geolocation === "undefined",
    typeof PublicKeyCredential === "undefined",
    !!video.canPlayType('video/mp4; codecs="avc1.64001F"')];
  const nonce = crypto.randomUUID();
  await browser.runtime.sendMessage({type:"privacy-proof", capabilities, nonce});
  // An image load belongs to the web document rather than privileged resource://
  // fetch. The background must observe and cancel this exact random control URL.
  const probe = document.createElement("img");
  probe.onload = probe.onerror = () => probe.remove();
  probe.src = "https://guard-selftest.invalid/probe?nonce=" + nonce;
})();
