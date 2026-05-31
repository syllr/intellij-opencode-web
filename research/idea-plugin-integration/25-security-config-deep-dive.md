# IntelliJ 插件安全和 OpenCode 配置系统深度分析

**调研时间**: 2026-05-30
**调研目标**: 为 IDEA 插件与 opencode 联动提供安全和配置系统技术指导

---

## 1. IntelliJ 插件安全

### 1.1 凭据安全存储

**推荐做法：使用 PasswordSafe API**

```kotlin
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.PasswordStorage

val attributes = CredentialAttributes(
    serviceName = "MyPlugin:OpenCodeAPI",
    userName = "api_key"
)

// 存储凭据
PasswordStorage.instance.store(attributes, "actual-secret-value")

// 检索凭据
val retrieved = PasswordStorage.instance.getPassword(attributes)
```

**禁止做法**：

- ❌ 硬编码 token
- ❌ 写入日志
- ❌ 存储在配置文件（未加密）
- ❌ 在代码中拼接 token

### 1.2 安全 HTTP 请求

```kotlin
// 使用 IntelliJ 的 HttpRequest API
val request = HttpRequest.newBuilder()
    .url("https://api.example.com/endpoint")
    .method(HttpMethod.GET)
    .addHeader("Authorization", "Bearer $token")
    .connectTimeout(5000)
    .readTimeout(10000)
    .build()

// 配置 SSL
val sslConfig = SslConfiguration.forTrustedHosts(setOf("api.example.com"))
request.setSslConfiguration(sslConfig)
```

### 1.3 常见安全漏洞防护

| 漏洞类型       | 防护措施                     |
| -------------- | ---------------------------- |
| SQL 注入       | 使用参数化查询               |
| Path Traversal | 验证路径，确保不超出基础目录 |
| XXE            | 禁用外部实体解析             |
| 不安全反序列化 | 使用白名单验证               |

### 1.4 安全检查清单

| 检查项      | 说明                                     | 优先级 |
| ----------- | ---------------------------------------- | ------ |
| ✅ 凭据存储 | 所有密码/API Key 必须使用 `PasswordSafe` | P0     |
| ✅ HTTPS    | 所有外部通信必须使用 TLS 1.2+            | P0     |
| ✅ 输入验证 | 所有用户输入必须验证和清理               | P0     |
| ✅ 日志脱敏 | 日志中不能包含 token、密码等敏感信息     | P1     |
| ✅ 依赖更新 | 检查第三方库的安全漏洞                   | P1     |
| ✅ 证书验证 | 生产环境必须验证服务器证书               | P0     |
| ✅ 错误处理 | 不向用户暴露内部错误细节                 | P2     |

---

## 2. OpenCode 配置系统

### 2.1 多层配置合并顺序

```
加载顺序（后者覆盖前者）：
well-known 远程 → 全局配置 → 项目配置 → .opencode 目录 → 环境变量 → Console → MDM
```

### 2.2 核心配置结构

```typescript
export const Info = Schema.Struct({
  shell: Schema.optional(Schema.String),
  logLevel: Schema.optional(Schema.Literals(["DEBUG", "INFO", "WARN", "ERROR"])),
  server: Schema.optional(Server),
  command: Schema.optional(Schema.Record(Schema.String, Schema.Any)),
  skills: Schema.optional(Skills),
  reference: Schema.optional(Reference),
  watcher: Schema.optional(Schema.Struct({ ignore: Schema.Array(Schema.String) })),
  plugin: Schema.optional(Schema.Array(Schema.Any)),
  model: Schema.optional(Schema.String),
  agent: Schema.optional(Schema.Record(Schema.String, Agent)),
  provider: Schema.optional(Schema.Record(Schema.String, Provider)),
  mcp: Schema.optional(Schema.Record(Schema.String, Mcp)),
  permission: Schema.optional(Permission),
  tools: Schema.optional(Schema.Record(Schema.String, Schema.Boolean)),
  instructions: Schema.optional(Schema.Array(Schema.String)),
});
```

### 2.3 权限配置

```typescript
export const Info = Schema.Struct({
  read: Rule,
  edit: Rule,
  glob: Rule,
  grep: Rule,
  bash: Rule,
  task: Rule,
  external_directory: Rule,
  todowrite: Action,
  question: Action,
  webfetch: Action,
  websearch: Action,
  repo_clone: Rule,
  skill: Rule,
});

// 权限规则格式
type Rule = "allow" | "deny" | "ask" | Record<string, "allow" | "deny" | "ask">;
```

### 2.4 Agent 配置

```typescript
export const AgentSchema = Schema.Struct({
  model: ConfigModelID,
  variant: Schema.optional(Schema.String),
  temperature: Schema.optional(Schema.Number),
  prompt: Schema.optional(Schema.String),
  mode: Schema.Literals(["subagent", "primary", "all"]),
  hidden: Schema.optional(Schema.Boolean),
  permission: Schema.optional(Permission.Info),
  steps: Schema.optional(Schema.Number),
});
```

### 2.5 MCP 配置

```typescript
// 本地 MCP 服务器
export const Local = Schema.Struct({
  type: Schema.Literal("local"),
  command: Schema.Array(Schema.String),
  environment: Schema.Record(Schema.String, Schema.String),
  enabled: Schema.optional(Schema.Boolean),
  timeout: Schema.optional(Schema.Number),
});

// 远程 MCP 服务器
export const Remote = Schema.Struct({
  type: Schema.Literal("remote"),
  url: Schema.String,
  enabled: Schema.optional(Schema.Boolean),
  headers: Schema.optional(Schema.Record(Schema.String, Schema.String)),
});
```

---

## 3. IDEA 插件与 OpenCode 集成安全考虑

### 3.1 本地服务连接安全

当前项目连接本地 OpenCode 服务（`127.0.0.1:12396`），安全性风险较低。但如果未来需要连接外部服务：

```kotlin
// 安全的 HTTP 客户端配置
class SecureHttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .sslSocketFactory(createTrustedSocketFactory(), TrustAllCertsManager())
        .hostnameVerifier { _, _ -> true } // 生产环境应验证
        .build()

    private fun createTrustedSocketFactory(): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(null, arrayOf(TrustAllCertsManager()), SecureRandom())
        return sslContext.socketFactory
    }
}
```

### 3.2 Token 安全管理

```kotlin
object SecureTokenManager {
    private val attributes = CredentialAttributes(
        serviceName = "OpenCodeWeb:API",
        userName = "auth_token"
    )

    fun saveToken(token: String) {
        PasswordStorage.instance.store(attributes, token)
    }

    fun getToken(): String? {
        return PasswordStorage.instance.getPassword(attributes)
    }

    fun clearToken() {
        PasswordStorage.instance.store(attributes, null)
    }
}
```

### 3.3 配置安全管理

```kotlin
// 安全的配置存储
class SecureConfigManager {
    private val secureAttributes = CredentialAttributes(
        serviceName = "OpenCodeWeb:Config",
        userName = "api_credentials"
    )

    fun saveConfig(config: OpenCodeConfig) {
        // 敏感信息使用 PasswordSafe
        if (config.apiKey != null) {
            PasswordStorage.instance.store(secureAttributes, config.apiKey!!)
        }

        // 非敏感信息使用普通存储
        val prefs = PropertiesComponent.getInstance()
        prefs.setValue("opencode.host", config.host)
        prefs.setValue("opencode.port", config.port.toString())
    }

    fun loadConfig(): OpenCodeConfig {
        val prefs = PropertiesComponent.getInstance()
        return OpenCodeConfig(
            host = prefs.getValue("opencode.host", "127.0.0.1"),
            port = prefs.getValue("opencode.port", "12396").toInt(),
            apiKey = PasswordStorage.instance.getPassword(secureAttributes)
        )
    }
}
```

---

## 4. 参考资源

| 资源                   | URL                                                                      |
| ---------------------- | ------------------------------------------------------------------------ |
| JetBrains PasswordSafe | https://plugins.jetbrains.com/docs/intellij/credential-store.html        |
| IntelliJ HTTP Client   | https://plugins.jetbrains.com/docs/intellij/http-client.html             |
| OpenCode Config        | /Users/yutao/Projects/opencode/packages/opencode/src/config/config.ts    |
| OpenCode Permission    | /Users/yutao/Projects/opencode/packages/opencode/src/permission/index.ts |

---

## 5. 下一步行动

1. **实现 PasswordSafe 集成** - 安全存储 API 凭据
2. **实现安全 HTTP 客户端** - 使用 TLS 和证书验证
3. **实现配置安全管理** - 分离敏感和非敏感配置
4. **添加安全审计日志** - 记录敏感操作
