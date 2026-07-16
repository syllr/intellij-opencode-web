/**
 * OpenCode Web UI sidebar bootstrap.
 *
 * 在 web app mount 之前 (`run_at: document_start`) 把当前 IntelliJ 项目写入 web app
 * 持久化的 client store,web app mount 时从 localStorage 还原,侧栏立即显示当前项目。
 *
 * 数据源:
 *   localStorage key = "opencode.global.dat:server"  (opencode 1.17.18, 源码
 *   packages/app/src/utils/persist.ts:54 GLOBAL_STORAGE + packages/app/src/context/server.tsx:262 Persist.global("server", ...))
 *
 *   value = JSON: {list, projects: {<scope>: [{worktree, expanded}, ...]}, lastProject: {<scope>: <path>}, recentlyClosed: {<scope>: []}}
 *
 * 注入方式:
 *   - Edge --app=<url> --load-extension=<extDir>
 *   - plugin (EdgeBootstrapExtension.kt) 启动时把本文件复制到 $TMPDIR/opencode-web-ext-<hash>/,把占位符 __PROJECT_BASE_PATH__ 替换成 project.basePath
 *   - ext content script 在 document_start 跑,localStorage 写入早于 web app main.tsx 的 <script type="module"> (async module script 在 document_start 之后)
 *
 * 不依赖 web app 任何内部 API,只操作 localStorage。如果 opencode 后续改了 localStorage key 或 schema,这里需要相应更新(目前 key 是 "opencode.global.dat:server" 经过 web 端 localStorageWithPrefix("opencode.global.dat") 加 base "opencode.global.dat:" + key "server")。
 *
 * Scope "local" 来自 ServerScope.fromServerKey:plugin 启的 server URL "http://localhost:12396" 在 web app 端被识别为 canonical local server,scope="local"。
 */
(function () {
  "use strict";
  const KEY = "opencode.global.dat:server";
  const PROJECT_PATH = "__PROJECT_BASE_PATH__";

  if (!PROJECT_PATH || PROJECT_PATH === "__PROJECT_BASE_PATH__") {
    return;
  }

  let obj;
  try {
    const raw = localStorage.getItem(KEY);
    obj = raw ? JSON.parse(raw) : null;
  } catch (_) {
    obj = null;
  }
  if (!obj || typeof obj !== "object") {
    obj = { list: [], projects: {}, lastProject: {}, recentlyClosed: {} };
  }

  obj.projects = obj.projects && typeof obj.projects === "object" ? obj.projects : {};
  obj.lastProject = obj.lastProject && typeof obj.lastProject === "object" ? obj.lastProject : {};
  obj.recentlyClosed =
    obj.recentlyClosed && typeof obj.recentlyClosed === "object" ? obj.recentlyClosed : {};

  const list = Array.isArray(obj.projects.local) ? obj.projects.local : [];
  if (!list.some(function (p) { return p && p.worktree === PROJECT_PATH; })) {
    list.unshift({ worktree: PROJECT_PATH, expanded: true });
    obj.projects.local = list;
  }
  obj.lastProject.local = PROJECT_PATH;

  try {
    localStorage.setItem(KEY, JSON.stringify(obj));
  } catch (_) {
    // quota exceeded 等场景静默失败:web app 仍能正常 mount,只是侧栏不会显示当前项目
  }
})();
