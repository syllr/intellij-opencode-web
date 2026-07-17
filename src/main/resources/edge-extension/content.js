console.log("[opencode-web-ext] FILE LOADED", new Date().toISOString(), "origin=", location.origin, "href=", location.href);
(function () {
  "use strict";
  console.log("[opencode-web-ext] IIFE START readyState=", document.readyState, "location.pathname=", location.pathname);
  const KEY = "opencode.global.dat:server";

  function getCurrentProjectPath() {
    try {
      const path = location.pathname;
      const segments = path.split("/").filter(Boolean);
      if (segments.length === 0 || segments[0] === "server") return null;
      const b64 = segments[0].replace(/-/g, "+").replace(/_/g, "/");
      const padding = "=".repeat((4 - (b64.length % 4)) % 4);
      const decoded = atob(b64 + padding);
      if (decoded.charAt(0) === "/") return decoded;
      return null;
    } catch (_) {
      return null;
    }
  }

  const originalGetItem = localStorage.getItem.bind(localStorage);
  const originalSetItem = localStorage.setItem.bind(localStorage);

  const PROJECT_PATH = getCurrentProjectPath();
  console.log("[opencode-web-ext] PROJECT_PATH=", PROJECT_PATH);
  if (!PROJECT_PATH) {
    console.log("[opencode-web-ext] non-project URL, skip bootstrap");
    return;
  }

  // 原因:web app 用 localStorage[KEY] 属性访问做存在性检查,绕过 getItem patch
  // 我们直接种数据,无论 web app 用什么方式读都能命中
  function buildObjWithProject() {
    let obj;
    try {
      const raw = originalGetItem(KEY);
      obj = raw ? JSON.parse(raw) : null;
    } catch (_) {
      obj = null;
    }
    if (!obj || typeof obj !== "object") {
      obj = { list: [], projects: {}, lastProject: {}, recentlyClosed: {} };
    }
    obj.projects = obj.projects && typeof obj.projects === "object" ? obj.projects : {};
    obj.lastProject = obj.lastProject && typeof obj.lastProject === "object" ? obj.lastProject : {};
    obj.recentlyClosed = obj.recentlyClosed && typeof obj.recentlyClosed === "object" ? obj.recentlyClosed : {};

    const list = Array.isArray(obj.projects.local) ? obj.projects.local : [];
    if (!list.some(function (p) { return p && p.worktree === PROJECT_PATH; })) {
      list.unshift({ worktree: PROJECT_PATH, expanded: true });
    }
    obj.projects.local = list;
    obj.lastProject.local = PROJECT_PATH;
    return obj;
  }

  const bootstrapObj = buildObjWithProject();
  try {
    originalSetItem(KEY, JSON.stringify(bootstrapObj));
    console.log("[opencode-web-ext] BOOTSTRAP written, projects.local len=", bootstrapObj.projects.local.length, "lastProject=", bootstrapObj.lastProject.local);
  } catch (e) {
    console.error("[opencode-web-ext] bootstrap setItem FAILED:", e.message);
  }

  const verify = localStorage[KEY];
  console.log("[opencode-web-ext] verify property access localStorage[KEY] len=", (verify || "").length, "first 100:", (verify || "").substring(0, 100));

  let lastProjectPath = PROJECT_PATH;
  localStorage.getItem = function (key) {
    if (key !== KEY) return originalGetItem(key);
    const current = getCurrentProjectPath();
    if (!current) return originalGetItem(key);

    if (lastProjectPath && lastProjectPath !== current) {
      console.log("[opencode-web-ext] project changed:", lastProjectPath, "->", current, "— reloading");
      setTimeout(function () { location.reload(); }, 50);
      return originalGetItem(key);
    }
    lastProjectPath = current;

    const obj = buildObjWithProject.call(null);
    obj.projects.local = (obj && obj.projects && obj.projects.local) || [];
    const modified = JSON.stringify(obj);
    try { originalSetItem(KEY, modified); } catch (_) {}
    return modified;
  };

  console.log("[opencode-web-ext] BOOTSTRAP + PATCH installed at", new Date().toISOString());
})();
