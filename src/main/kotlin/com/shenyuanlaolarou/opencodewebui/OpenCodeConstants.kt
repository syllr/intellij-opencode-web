package com.shenyuanlaolarou.opencodewebui

const val OPENCODE_PORT = 12396
const val OPENCODE_HOST = "127.0.0.1"

// 健康检查相关
const val HEALTH_CHECK_INTERVAL_MS = 5000L
const val HEALTH_CHECK_START_DELAY_MS = 10000L
const val HEALTH_CHECK_POLL_INTERVAL_MS = 2000L
const val HEALTH_CHECK_INITIAL_DELAY_MS = 1000L

// 浏览器就绪重试（[O6] 收紧到 8×400ms=3.2s，平衡加载等待与重试开销）
const val BROWSER_READY_MAX_RETRIES = 8
const val BROWSER_READY_RETRY_DELAY_MS = 400L

// 服务器启动
const val SERVER_START_TIMEOUT_MS = 30000L

// HTTP 超时
const val HTTP_TIMEOUT_MS = 8000

// SSE 连接
const val SSE_CONNECT_TIMEOUT_SECONDS = 5L
const val SSE_WATCHDOG_INTERVAL_MS = 5_000L
const val SSE_IDLE_TIMEOUT_MS = 30_000L

// TODO: 临时方案——以下两个常量应改为可配置(设置页面)。当前硬编码仅适用于开发者本人机器。
// 后续需抽离为插件配置,其他开发者 clone 后可能直接失败(im-select 不存在时降级为 noop)。
// 依赖项目:https://github.com/daijro/im-select(macOS 切换输入法的 CLI 工具)
const val IM_SELECT_PATH = "/Users/yutao/Desktop/software/bin/im-select"
const val IM_SELECT_ARG_EN = "com.apple.keylayout.ABC"
