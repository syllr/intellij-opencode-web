# Plan: 增强 getFullEnvironment() - 运行时检测路径 + 解析 shell 配置

## TL;DR

在 IDEA 插件的 `getFullEnvironment()` 方法中，通过三种机制构建完整 PATH：
1. **默认路径** - macOS 常用路径，无条件添加
2. **可检测路径** - 常见工具路径，检测存在后才添加
3. **Shell 配置解析** - 解析 `~/.bashrc`/`~/.zshrc`（默认）及其他 shell 配置中的 PATH 修改

---

## PATH 分类

### A. 默认路径（无条件添加）

即使目录不存在也添加，确保基础 PATH 完整：

| 路径 | 说明 |
|------|------|
| `/usr/bin` | 系统命令 |
| `/bin` | 系统命令 |
| `/usr/sbin` | 系统命令 |
| `/sbin` | 系统命令 |
| `/usr/local/bin` | npm 全局包、Homebrew 默认 |
| `/opt/homebrew/bin` | Homebrew (Apple Silicon) |
| `/opt/homebrew/sbin` | Homebrew sbin |
| `/opt/local/bin` | MacPorts |
| `/opt/local/sbin` | MacPorts sbin |

### B. 可检测路径（存在才添加）

常见工具路径，检测目录存在后才添加到 PATH：

| 路径 | 包含的工具 |
|------|----------|
| `~/.cargo/bin` | rust-analyzer, cargo |
| `~/.pyenv/bin` | pyenv |
| `~/.rbenv/bin` | rbenv |
| `~/.nvm/versions/node/*/bin` | nvm (需要通配符展开) |
| `~/.yarn/bin` | Yarn 1.x |
| `~/.config/yarn/global/node_modules/.bin` | Yarn global |
| `~/.g/bin` | g (Go 版本管理) |
| `~/.g/go/bin` | Go |
| `~/go/bin` | GOPATH/bin |
| `~/.local/bin` | uv, uvx |
| `~/.bun/bin` | Bun |
| `~/.deno/bin` | Deno |
| `~/.codeium/windsurf/bin` | Windsurf |
| npm global | 通过 `npm root -g` 获取 |
| bun global | 通过 `bun pm global bin` 获取 |

### C. Shell 配置解析

**解析逻辑：**
1. **默认**：始终解析 `~/.bashrc` 和 `~/.zshrc`
2. **根据 $SHELL**：如果不是 bash 或 zsh，再解析对应 shell 的配置（如 `~/.config/fish/config.fish`）

**解析内容：**
- `export PATH=$PATH:xxx` - 路径追加
- `export PATH=xxx:$PATH` - 路径前置
- `source xxx` / `. xxx` - 递归解析引用的配置文件
- `eval "$(xxx)"` - 执行并从输出提取 PATH

---

## Implementation

### 方法 1: getFullEnvironment()

```kotlin
private fun getFullEnvironment(): Map<String, String> {
    val env = System.getenv().toMutableMap()
    val home = env["HOME"] ?: System.getProperty("user.home")
    val currentPath = env["PATH"] ?: ""

    val allPaths = mutableListOf<String>()

    // 1. 添加默认路径
    val defaultPaths = listOf(
        "/usr/bin", "/bin", "/usr/sbin", "/sbin",
        "/usr/local/bin",
        "/opt/homebrew/bin", "/opt/homebrew/sbin",
        "/opt/local/bin", "/opt/local/sbin"
    )
    allPaths.addAll(defaultPaths)

    // 2. 检测可添加的工具路径
    val detectedPaths = detectToolPaths(home)
    allPaths.addAll(detectedPaths)

    // 3. 解析 shell 配置文件
    val shellPaths = parseShellConfigPaths(home)
    allPaths.addAll(shellPaths)

    // 4. 去重并过滤已经在 currentPath 中的路径
    val newPaths = allPaths.distinct()
        .filter { !currentPath.contains(it) && java.io.File(it).exists() }

    // 5. 构建最终 PATH
    val finalPath = if (newPaths.isNotEmpty()) {
        "$currentPath:${newPaths.joinToString(":")}"
    } else {
        currentPath
    }

    env["PATH"] = finalPath

    // 调试日志
    thisLogger().info("[getFullEnvironment] Detected paths: $detectedPaths")
    thisLogger().info("[getFullEnvironment] Shell config paths: $shellPaths")
    thisLogger().info("[getFullEnvironment] Final PATH: $finalPath")

    return env
}
```

### 方法 2: detectToolPaths(home: String)

```kotlin
private fun detectToolPaths(home: String): List<String> {
    val paths = mutableListOf<String>()

    // 检测 ~/.cargo/bin
    val cargoBin = "$home/.cargo/bin"
    if (java.io.File(cargoBin).exists()) paths.add(cargoBin)

    // 检测 ~/.g/bin
    val gBin = "$home/.g/bin"
    if (java.io.File(gBin).exists()) paths.add(gBin)

    // 检测 Go: ~/.g/go/bin 或 ~/go/bin
    for (goPath in listOf("$home/.g/go", "$home/go")) {
        val goBin = "$goPath/bin"
        if (java.io.File(goBin).exists()) {
            paths.add(goBin)
            break
        }
    }

    // 检测 ~/.local/bin
    val localBin = "$home/.local/bin"
    if (java.io.File(localBin).exists()) paths.add(localBin)

    // 检测 ~/.bun/bin
    val bunBin = "$home/.bun/bin"
    if (java.io.File(bunBin).exists()) paths.add(bunBin)

    // 检测 ~/.deno/bin
    val denoBin = "$home/.deno/bin"
    if (java.io.File(denoBin).exists()) paths.add(denoBin)

    // 检测 nvm 管理的 Node 版本
    val nvmVersionsDir = "$home/.nvm/versions"
    if (java.io.File(nvmVersionsDir).exists()) {
        java.io.File(nvmVersionsDir).listFiles()?.forEach { versionDir ->
            val nodeBin = "${versionDir.absolutePath}/bin"
            if (java.io.File(nodeBin).exists()) {
                paths.add(nodeBin)
            }
        }
    }

    // 检测 npm 全局 bin
    try {
        val process = Runtime.getRuntime().exec(arrayOf("npm", "root", "-g"))
        val output = process.inputStream.bufferedReader().readText().trim()
        val npmBin = "$output/bin"
        if (java.io.File(npmBin).exists()) paths.add(npmBin)
    } catch (e: Exception) {
        // npm not found or error
    }

    // 检测 bun 全局 bin
    try {
        val process = Runtime.getRuntime().exec(arrayOf("bun", "pm", "global", "bin"))
        val output = process.inputStream.bufferedReader().readText().trim()
        if (java.io.File(output).exists()) paths.add(output)
    } catch (e: Exception) {
        // bun not found or error
    }

    // 检测 pyenv
    val pyenvBin = "$home/.pyenv/bin"
    if (java.io.File(pyenvBin).exists()) paths.add(pyenvBin)

    // 检测 rbenv
    val rbenvBin = "$home/.rbenv/bin"
    if (java.io.File(rbenvBin).exists()) paths.add(rbenvBin)

    // 检测 Yarn
    val yarnBin = "$home/.yarn/bin"
    if (java.io.File(yarnBin).exists()) paths.add(yarnBin)

    // 检测 Windsurf
    val windsurfBin = "$home/.codeium/windsurf/bin"
    if (java.io.File(windsurfBin).exists()) paths.add(windsurfBin)

    return paths
}
```

### 方法 3: parseShellConfigPaths(home: String)

```kotlin
private fun parseShellConfigPaths(home: String): List<String> {
    val paths = mutableSetOf<String>()

    // 获取用户使用的 shell
    val shell = System.getenv("SHELL") ?: "/bin/bash"
    val shellName = java.io.File(shell).name  // "bash", "zsh", "fish" 等

    // 需要解析的配置文件列表
    val configFiles = mutableListOf<String>()

    // 默认：bash 和 zsh 配置
    configFiles.add("$home/.bashrc")
    configFiles.add("$home/.zshrc")

    // 如果不是 bash/zsh，添加对应 shell 的配置
    when (shellName) {
        "fish" -> configFiles.add("$home/.config/fish/config.fish")
        "csh", "tcsh" -> configFiles.add("$home/.cshrc")
        "ksh" -> {
            configFiles.add("$home/.kshrc")
            configFiles.add("$home/.profile")
        }
        // 其他 shell 可以继续扩展
    }

    // 解析所有配置文件
    val visitedFiles = mutableSetOf<String>()
    for (configFile in configFiles) {
        parseConfigFile(configFile, home, paths, visitedFiles, maxDepth = 5)
    }

    return paths.toList()
}

private fun parseConfigFile(
    filePath: String,
    home: String,
    paths: MutableSet<String>,
    visitedFiles: MutableSet<String>,
    depth: Int
) {
    if (depth <= 0 || !visitedFiles.add(filePath)) return

    val file = java.io.File(filePath)
    if (!file.exists()) return

    try {
        val content = file.readText()

        // 解析 export PATH=$PATH:xxx 格式
        val exportPathPattern = """export\s+PATH=["']?\$?PATH:?([^"'\s]+)["']?""".toRegex()
        exportPathPattern.findAll(content).forEach { match ->
            val pathValue = match.groupValues[1]
            parsePathString(pathValue, home, paths)
        }

        // 解析 eval "$(xxx)" 格式
        val evalPattern = """eval\s+\$\("([^"]+)"\)""".toRegex()
        evalPattern.findAll(content).forEach { match ->
            val cmd = match.groupValues[1]
            try {
                val process = Runtime.getRuntime().exec(arrayOf("bash", "-c", cmd))
                val output = process.inputStream.bufferedReader().readText()
                parsePathString(output, home, paths)
            } catch (e: Exception) {
                // 忽略执行失败的命令
            }
        }

        // 解析 source xxx 或 . xxx 格式
        val sourcePattern = """(?:source|\.)\s+["']?([^"'\s]+)["']?""".toRegex()
        sourcePattern.findAll(content).forEach { match ->
            var sourcedFile = match.groupValues[1].replace("~", home)
            // 递归解析被 source 的文件
            parseConfigFile(sourcedFile, home, paths, visitedFiles, depth - 1)
        }

    } catch (e: Exception) {
        // 忽略读取失败的文件
    }
}

private fun parsePathString(pathStr: String, home: String, paths: MutableSet<String>) {
    // 处理多个路径用 : 分隔的情况
    pathStr.split(":").forEach { path ->
        var expanded = path
            .replace("~", home)
            .replace("\$HOME", home)
            .replace("\${HOME}", home)
            .trim()

        if (expanded.isNotBlank() && !expanded.startsWith("$")) {
            // 对于包含通配符的路径，尝试展开
            if (expanded.contains("*")) {
                try {
                    val parent = java.io.File(expanded.substringBefore("/*"))
                    if (parent.exists() && parent.isDirectory) {
                        parent.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                            val binDir = "${subDir.absolutePath}/bin"
                            if (java.io.File(binDir).exists()) {
                                paths.add(binDir)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 通配符展开失败
                }
            } else {
                if (java.io.File(expanded).exists()) {
                    paths.add(expanded)
                }
            }
        }
    }
}
```

---

## Files to Modify

- `src/main/kotlin/com/github/xausky/opencodewebui/toolWindow/MyToolWindowFactory.kt`
  - 重写 `getFullEnvironment()` 方法
  - 添加 `detectToolPaths()` 私有方法
  - 添加 `parseShellConfigPaths()` 私有方法
  - 添加 `parseConfigFile()` 私有方法
  - 添加 `parsePathString()` 私有方法

---

## Verification

```bash
# 检查调试日志
grep -E "Detected paths|Shell config paths|Final PATH" ~/Library/Logs/JetBrains/GoLand2026.1/idea.log

# 检查 OpenCode 是否正常启动
grep "waitForServerHealthy" ~/Library/Logs/JetBrains/GoLand2026.1/idea.log
```

---

## Open Questions

无

