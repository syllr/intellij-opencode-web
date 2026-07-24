console.log("[opencode-web-ext] FILE LOADED", new Date().toISOString(), "origin=", location.origin);
(function () {
  "use strict";
  console.log("[opencode-web-ext] IIFE START readyState=", document.readyState, "location.pathname=", location.pathname, "location.search=", location.search);
  const KEY = "opencode.global.dat:server";
  const PATH_KEY = "opencode-web-ext:project-path";

  function installKeyBlocker() {
    if (window.__opencodeKeysBlocked) return;
    window.__opencodeKeysBlocked = true;

    function handler(e) {
      if (!e.metaKey) return;
      if (e.key !== "t" && e.key !== "n") return;
      e.preventDefault();
    }

    window.addEventListener("keydown", handler, { capture: true, passive: false });
    console.log("[opencode-web-ext] KEY BLOCKER installed on window (capture), Cmd+T/N blocked");
  }

  installKeyBlocker();

  const originalGetItem = localStorage.getItem.bind(localStorage);
  const originalSetItem = localStorage.setItem.bind(localStorage);

  function getCurrentProjectPath() {
    try {
      const params = new URLSearchParams(location.search);
      const dir = params.get("directory");
      if (dir) {
        const decoded = decodeURIComponent(dir);
        if (decoded.charAt(0) === "/") return decoded;
      }
      const segments = location.pathname.split("/").filter(Boolean);
      if (segments.length > 0 && segments[0] !== "server" && segments[0] !== "new-session") {
        const b64 = segments[0].replace(/-/g, "+").replace(/_/g, "/");
        const padding = "=".repeat((4 - (b64.length % 4)) % 4);
        try {
          const decoded = atob(b64 + padding);
          if (decoded.charAt(0) === "/") return decoded;
        } catch (_) {}
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  function getProjectPathFromStorage() {
    try {
      const stored = originalGetItem(PATH_KEY);
      if (stored && stored.charAt(0) === "/") return stored;
    } catch (_) {}
    return null;
  }

  function setProjectPathInStorage(path) {
    try {
      originalSetItem(PATH_KEY, path);
    } catch (_) {}
  }

  let projectPath = getProjectPathFromStorage();
  if (!projectPath) {
    projectPath = getCurrentProjectPath();
    if (projectPath) {
      setProjectPathInStorage(projectPath);
    } else {
      return;
    }
  }

  const finalProjectPath = projectPath;

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
    if (!list.some(function (p) { return p && p.worktree === finalProjectPath; })) {
      list.unshift({ worktree: finalProjectPath, expanded: true });
    }
    obj.projects.local = list;
    obj.lastProject.local = finalProjectPath;
    return obj;
  }

  const bootstrapObj = buildObjWithProject();
  try {
    originalSetItem(KEY, JSON.stringify(bootstrapObj));
  } catch (e) {
    console.error("[opencode-web-ext] bootstrap setItem FAILED:", e.message);
  }

  let lastProjectPath = finalProjectPath;
  localStorage.getItem = function (key) {
    if (key !== KEY) return originalGetItem(key);
    const stored = getProjectPathFromStorage();
    if (!stored) return originalGetItem(key);

    if (lastProjectPath && lastProjectPath !== stored) {
      return originalGetItem(key);
    }
    lastProjectPath = stored;

    const obj = buildObjWithProject();
    obj.projects.local = obj.projects.local || [];
    const modified = JSON.stringify(obj);
    try { originalSetItem(KEY, modified); } catch (_) {}
    return modified;
  };

  window.addEventListener("load", function () {
    setTimeout(function () {
      const titleEl = document.querySelector("title");
      if (titleEl) titleEl.textContent = finalProjectPath;
    }, 500);
  });
})();
