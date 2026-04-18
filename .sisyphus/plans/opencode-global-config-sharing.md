# OpenCode 全局配置共享方案

## 问题描述

同一 Goland 窗口中：
- 打开项目 A → 设置中文 + 快捷键等配置
- 切换到项目 B → 打开 OpenCode 工具窗口
- **配置丢失，需要重新设置**

**根因**：每个 JCEF Browser 实例的 localStorage 是独立的，即使 `cache_path` 共享。

## 解决方案

在插件层实现配置同步：
1. 配置存储在 IDE 配置目录的文件中（全局共享）
2. JBCefBrowser 加载时，从文件恢复配置到 localStorage
3. localStorage 变化时，同步回文件

## 技术设计

### 1. 配置存储位置

```
{IDE_CONFIG_DIR}/opencode-plugin/
└── global-config.json  # 存储需要全局共享的配置
```

### 2. 需要全局共享的配置项

根据 localStorage 分析，需要同步的配置：
- `opencode.global.dat:command.catalog.v1` - 快捷键配置
- `opencode.global.dat:highlight.v1` - 配色方案
- `opencode.global.dat:opencode-color-scheme` - 颜色方案
- `opencode.global.dat:opencode-theme-id` - 主题 ID
- `opencode.global.dat:model` - 模型配置
- `opencode.global.dat:layout` - 布局配置（终端高度、侧边栏宽度等）

### 3. JavaScript 注入策略

在 `loadEnd` 时注入 JS：
1. 从文件读取全局配置
2. 合并到 localStorage（不覆盖项目特定配置）
3. 监听 `storage` 事件，变化时同步回文件

### 4. 实现位置

修改 `MyToolWindowFactory.kt` 中的 `createMainTab` 方法

---

## TODOs

### 1. 添加全局配置存储服务

- [ ] 创建 `GlobalConfigStorage` 类
- [ ] 位置：`{IDE_CONFIG_DIR}/opencode-plugin/global-config.json`
- [ ] 提供 `load()` 和 `save()` 方法
- [ ] 线程安全（使用 AtomicReference 或类似机制）

### 2. 修改 BrowserPanel 创建逻辑

- [ ] 在 `createMainTab` 方法中注入配置同步 JS
- [ ] 在 `loadEnd` 时执行配置恢复
- [ ] 使用 `executeJavaScript` 注入同步脚本

### 3. 实现配置恢复逻辑（JavaScript）

- [ ] 从插件读取全局配置 JSON
- [ ] 写入 localStorage（使用 `opencode.global.dat:*` key 前缀）
- [ ] 保留项目特定配置（如 `opencode.global.dat:globalSync.project`）

### 4. 实现配置保存逻辑（JavaScript）

- [ ] 监听 `window.addEventListener('storage', ...)` 事件
- [ ] 过滤需要全局同步的配置 key
- [ ] 调用插件方法保存到文件

### 5. 暴露 Kotlin 方法给 JavaScript

- [ ] 使用 `JBCefJSQuery` 或类似机制
- [ ] 提供 `saveGlobalConfig(config: String)` 方法
- [ ] 提供 `loadGlobalConfig(): String` 方法

---

## 参考实现

### GlobalConfigStorage 类结构

```kotlin
class GlobalConfigStorage(private val project: Project) {
    private val configFile: File
    private val configCache = AtomicReference<String?>(null)

    fun loadGlobalConfig(): String? { ... }
    fun saveGlobalConfig(json: String) { ... }
}
```

### JavaScript 注入模板

```javascript
(function() {
    // 1. 从插件读取全局配置
    var globalConfig = /* 从 Kotlin 读取 */;

    // 2. 定义需要全局同步的 key 前缀
    var GLOBAL_KEYS = [
        'opencode.global.dat:command.catalog',
        'opencode.global.dat:highlight',
        'opencode.global.dat:opencode-color-scheme',
        'opencode.global.dat:opencode-theme-id',
        'opencode.global.dat:model',
        'opencode.global.dat:layout'
    ];

    // 3. 恢复全局配置到 localStorage
    if (globalConfig) {
        var parsed = JSON.parse(globalConfig);
        Object.keys(parsed).forEach(function(key) {
            if (GLOBAL_KEYS.some(function(gk) { return key.startsWith(gk); })) {
                localStorage.setItem(key, parsed[key]);
            }
        });
    }

    // 4. 监听变化并同步回插件
    window.addEventListener('storage', function(e) {
        if (GLOBAL_KEYS.some(function(gk) { return e.key.startsWith(gk); })) {
            // 调用 Kotlin 保存方法
        }
    });
})();
```

---

## 测试验证

### 测试场景

1. 项目 A 设置中文 + 快捷键 Command+P
2. 切换到项目 B
3. 验证配置仍然生效

### 验证命令

```bash
# 查看全局配置文件
cat ~/Library/Application\ Support/JetBrains/GoLand*/options/opencode-plugin/global-config.json
```
