// Receives {js: "..."} from WallPanel and runs it in the page's own context
// (content scripts are isolated, so the code is injected as a script element).
const port = browser.runtime.connectNative("wallpanel");
port.onMessage.addListener((message) => {
  if (typeof message.js !== "string") {
    return;
  }
  const script = document.createElement("script");
  script.textContent = message.js;
  (document.head || document.documentElement).appendChild(script);
  script.remove();
});
