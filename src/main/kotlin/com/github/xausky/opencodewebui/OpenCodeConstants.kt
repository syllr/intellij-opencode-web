package com.github.xausky.opencodewebui

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
