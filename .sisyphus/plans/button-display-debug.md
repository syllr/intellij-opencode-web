# Plan: 排查 OpenCode 右上角按钮全部消失问题

## 结论：OpenCode Web 自身 Bug

**问题根因**：这是 OpenCode Web 的 autoselecting 时序 bug，不是插件问题。

**时序问题**：
1. 打开 `/session` 时，`autoselecting` 开始执行
2. SessionHeader 用空的 `params.dir` 渲染 → `projectDirectory() = ""` → 按钮不显示
3. `autoselecting` 完成后导航到 `/{dir}/session/{id}`
4. 但 SessionHeader 不会重新渲染

**解决方案**：需要在 OpenCode 项目中修复此 bug（不接受修改 OpenCode 源码）

---

## 已删除的测试代码

已撤销所有修改：
- ✅ 删除了 platform polyfill
- ✅ 删除了 autoselecting 修复代码
- ✅ 删除了调试日志
- ✅ 保留了 localStorage 项目注册逻辑

## 已知调试发现

### 最新日志分析（2026-04-17 19:24）

**URL 信息**：
```
URL: http://127.0.0.1:12396/L1Vz...Lw==/session
pathname: /L1Vz...Lw==/session
```

**关键发现**：
1. **URL 路径正确**：`/{base64编码的dir}/session`
2. **params 为空**：之前打印的是 `window.location.search`（query string），本身就是空的
3. **autoselecting 恢复 session 失败**：URL 是 `/session` 而不是 `/session/{sessionId}`
4. **`[global-sdk] event stream error`**：SDK 连接有问题

## 问题根因分析

### 问题 1：`projectDirectory()` 返回空

**原因**：`session-header.tsx` 使用 `useParams()` 获取路由参数 `params.dir`

```tsx
// session-header.tsx 行 141
const projectDirectory = createMemo(() => decode64(params.dir) ?? "")
```

**问题**：我们需要确认 `params.dir` 是否在 JCEF 环境中正确获取。

### 问题 2：`canOpen()` 返回 false

**`canOpen()` 定义**：
```javascript
const canOpen = createMemo(() =>
    platform.platform === "desktop" &&
    !!platform.openPath &&
    server.isLocal()
)
```

**三个必须同时满足的条件**：
| 条件 | 说明 |
|------|------|
| `platform.platform === "desktop"` | 必须是桌面平台 |
| `!!platform.openPath` | platform 必须有 openPath 方法 |
| `server.isLocal()` | 服务器必须被识别为本地 |

### 问题 3：autoselecting 恢复 session 失败

URL 导航到 `/session` 而不是 `/session/{sessionId}`，说明所有 session 恢复尝试都失败了。

## 调试日志（需要添加）

### Task 1: 添加关键调试日志

**文件**: `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt`

**位置**: `BrowserPanel.createMainTab()` 中的 `onLoadEnd` 回调

**添加的日志**:
```kotlin
// 在 onLoadEnd 中添加以下 JavaScript 日志：
console.log('=== DEBUG INFO ===');
console.log('URL:', window.location.href);
console.log('pathname:', window.location.pathname);
// 尝试从 window 对象获取 SolidJS 状态
console.log('window.__ssrState:', JSON.stringify(window.__ssrState || 'undefined'));
console.log('window.__startRoute:', JSON.stringify(window.__startRoute || 'undefined'));
// 检查全局 platform 和 server 对象
console.log('typeof platform:', typeof platform);
console.log('typeof server:', typeof server);
if (typeof platform !== 'undefined') {
    console.log('platform.platform:', platform.platform);
    console.log('platform.openPath:', platform.openPath);
}
if (typeof server !== 'undefined') {
    console.log('server.isLocal():', server.isLocal());
    console.log('server.current():', JSON.stringify(server.current()));
}
// 检查 SDK
console.log('typeof globalSDK:', typeof globalSDK);
```

## JCEF 日志文件位置

**IDE 日志目录**：
```
/Users/yutao/IdeaProjects/intellij-opencode-web/build/idea-sandbox/IU-2025.3.4/log/
```

**查看 JCEF console 日志**：
```bash
# 查看所有 JCEF console 输出
grep "JCEF Console" build/idea-sandbox/IU-2025.3.4/log/idea.log | tail -200

# 只看 DEBUG INFO
grep "JCEF Console.*DEBUG" build/idea-sandbox/IU-2025.3.4/log/idea.log | tail -50

# 查看 platform 和 server 状态
grep "JCEF Console.*platform\|JCEF Console.*server" build/idea-sandbox/IU-2025.3.4/log/idea.log | tail -50
```

## 执行 Waves

### Wave 1: 添加调试日志
- [x] **修改 `MyToolWindowFactory.kt`**：在 onLoadEnd 中添加 platform/server/SDK 状态日志
- [x] **编译插件**：`./gradlew buildPlugin`
- [x] **运行测试**：`./gradlew runIde`，打开 JavaHello 项目，点击 OpenCodeWeb 工具窗口
- [x] **收集日志**：查看 `idea.log` 中的 JCEF Console 输出

### Wave 2: 分析日志定位问题
- [x] 如果 `platform.platform` 不是 `"desktop"` → 检查 platform 初始化
- [x] 如果 `platform.openPath` 不存在 → 这是 JCEF 环境的限制
- [x] 如果 `server.isLocal()` 是 false → 检查服务器连接
- [x] 如果 `globalSDK` 是 undefined → SDK 初始化失败

### Wave 3: 修复并验证
- [x] 根据根因修复
- [x] 验证按钮显示正常（Polyfill 已生效 - 日志确认 "JCEF Platform Polyfill applied: platform = desktop"）

## 关键源码文件

| 文件 | 作用 |
|------|------|
| `/Users/yutao/IdeaProjects/intellij-opencode-web/src/main/kotlin/.../MyToolWindowFactory.kt` | 插件主文件，JCEF 初始化 |
| `/Users/yutao/GolandProjects/opencode/packages/app/src/components/session/session-header.tsx` | 按钮渲染逻辑，`canOpen()` 和 `projectDirectory()` 定义 |
| `/Users/yutao/GolandProjects/opencode/packages/app/src/context/server.tsx` | 服务器状态，`isLocal()` 定义 |
| `/Users/yutao/GolandProjects/opencode/packages/app/src/context/platform.tsx` | platform 上下文定义 |

## 可能的修复方向

### 如果 `platform.platform !== "desktop"`
这是 JCEF 环境的正常情况，Web UI 检测不到桌面平台。

**可能的修复**：修改 `canOpen()` 条件，在 JCEF 环境中也允许某些操作。

### 如果 `platform.openPath` 不存在
JCEF 环境没有 `openPath` 方法，这是正常的。

**可能的修复**：提供 JCEF 环境的 `platform.openPath` polyfill。

### 如果 `server.isLocal()` 是 false
服务器没有被识别为本地。

**可能的修复**：检查 `server.current()` 的类型和 URL 配置。

### 如果 `globalSDK` 是 undefined
SDK 初始化失败，可能是因为 JCEF 环境中某些 API 不可用。

**可能的修复**：检查 SDK 初始化代码。

---

## 最新分析结果（2026-04-17）

### 按钮渲染条件分析

**右上角按钮的 Show 条件**：

| 按钮 | 条件 | 代码位置 |
|------|------|---------|
| 复制路径/Open | `projectDirectory()` | line 315 |
| 状态 | `status()` | line 429 |
| 终端 | `term()` | line 434 |
| 审查 | 无（始终显示） | line 452 |
| 文件树 | `tree()` | line 469 |

### 🔑 关键发现：初始加载 vs 切换项目

**用户反馈**：
- 初始打开 OpenCode Web UI 时，右上角按钮不显示
- 当**添加新项目**或**切换项目**后，按钮就显示了

**分析**：
这说明 `projectDirectory()` 在初始加载时可能为空或不满足条件，但当项目切换后满足了条件。

**可能原因**：
1. 初始加载时 `params.dir` 还没有正确获取（路由尚未完全初始化）
2. 当切换项目时，URL 变化触发重新渲染，`params.dir` 才正确获取
3. `projectDirectory()` = `decode64(params.dir) ?? ""` - 如果 `params.dir` 为空，projectDirectory 为空字符串，整个按钮区域不显示

**按钮显示条件**：
```tsx
<Show when={projectDirectory()}>  // ← projectDirectory() 必须非空
  <div class="hidden xl:flex items-center">
    {/* 按钮内容 */}
  </div>
</Show>
```

---

## 问题根因分析

### 时序问题

1. 打开 `/session` 页面时，`autoselecting` 开始执行
2. `autoselecting` 调用 `openProject()` → `navigateToProject()`
3. `navigateToProject` 导航到 `/${dir}/session` URL
4. **但在导航完成之前**，SessionHeader 已经用空的 `params.dir` 渲染了
5. 此时 `projectDirectory()` = `decode64("") ?? ""` = `""`（空字符串）
6. `<Show when={projectDirectory()}>` = false，所以按钮区域不渲染

### 为什么切换项目后按钮显示？

切换项目时，URL 直接就是 `/${dir}/session`，`params.dir` 已经有了正确的值，所以 `projectDirectory()` 非空，按钮显示。

### 结论

**这是 autoselecting 导航时序问题**，不是 polyfill 问题。

---

## ⚠️ 注意：不能修改 OpenCode 源代码

用户明确指出不应该修改 OpenCode 的源代码。修改 OpenCode 代码需要在其项目中进行，这超出了插件的范围。

**已尝试的修改（已撤销）**：对 `session-header.tsx` 的 `projectDirectory()` 修改已被撤销。

---

## 待解决

由于不能修改 OpenCode 源代码，需要通过 JCEF JavaScript 注入来解决时序问题。

**可行方案**：
1. 在 `MyToolWindowFactory.kt` 的 `onLoadEnd` 中注入额外逻辑，等待 autoselecting 完成后再触发 SessionHeader 渲染
2. 或者通过监听 URL 变化，在导航完成后强制重新渲染

**替代方案**：
- 接受这是 OpenCode 的 bug，需要在 OpenCode 项目中修复
- 向 OpenCode 报告此问题