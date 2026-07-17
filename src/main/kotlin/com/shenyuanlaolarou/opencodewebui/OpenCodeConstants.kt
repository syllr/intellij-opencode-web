package com.shenyuanlaolarou.opencodewebui

const val OPENCODE_PORT = 12396
const val OPENCODE_HOST = "127.0.0.1"

// 健康检查相关（HealthMonitor.kt 在 Part D 整删,仅保留 OpenCodeApi 依赖的轮询常量）
const val HEALTH_CHECK_POLL_INTERVAL_MS = 500L
const val HEALTH_CHECK_INITIAL_DELAY_MS = 1000L

// 健康目录验证超时:GET /global/health 提取 directory 字段的最大等待时间
// 用于多 IDE 同端口碰撞检测（M2-T1 health gate）
const val HEALTH_VERIFY_TIMEOUT_MS = 500L

const val LRU_MAX_ENTRIES = 1000L

// 服务器启动
const val SERVER_START_TIMEOUT_MS = 30000L

// HTTP 超时
const val HTTP_TIMEOUT_MS = 8000

// SSE 连接
const val SSE_CONNECT_TIMEOUT_SECONDS = 5L
const val SSE_WATCHDOG_INTERVAL_MS = 5_000L
const val SSE_IDLE_TIMEOUT_MS = 30_000L
