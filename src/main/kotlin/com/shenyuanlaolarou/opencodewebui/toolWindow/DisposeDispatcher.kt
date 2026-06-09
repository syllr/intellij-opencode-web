package com.shenyuanlaolarou.opencodewebui.toolWindow

import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApi
import com.shenyuanlaolarou.opencodewebui.utils.OpenCodeApiResult
import com.intellij.openapi.diagnostic.thisLogger

// 可注入的 dispose sender,默认走 OpenCodeApi.disposeServer()。
// 测试可临时替换为计数 lambda,验证 "acquireHandle 拿不到时不发 dispose" 的契约。
@Volatile
internal var disposeSender: () -> OpenCodeApiResult<Unit> = { OpenCodeApi.disposeServer() }

/**
 * 在后台线程发起 POST /global/dispose,记录结果但不阻塞当前调用者。
 * 与关闭策略解耦:dispose 成功/失败都不影响主线程等待 process 退出;
 * process 退出由 SIGTERM 兜底保证。
 */
internal fun OpenCodeServerManager.startDisposeThread() {
    Thread({ requestGracefulDispose() }, "opencode-dispose").apply {
        isDaemon = true
        start()
    }
}

internal fun OpenCodeServerManager.requestGracefulDispose() {
    when (val result = disposeSender()) {
        is OpenCodeApiResult.Success -> {
            thisLogger().debug("[OpenCodeServerManager] /global/dispose succeeded, server will exit shortly")
        }
        is OpenCodeApiResult.Failure -> {
            if (result.code == 404) {
                // 老版 opencode 可能没这个端点;降级为 debug 避免每次 shutdown 都刷警告
                thisLogger().debug("[OpenCodeServerManager] /global/dispose not available (404, old opencode?)")
            } else {
                thisLogger().warn("[OpenCodeServerManager] /global/dispose failed: code=${result.code}")
            }
        }
        is OpenCodeApiResult.Unavailable -> {
            // 2s 客户端超时或连接拒绝。server 仍可能在清理中,主线程继续等 process 退出。
            thisLogger().debug("[OpenCodeServerManager] /global/dispose unavailable (timeout/connection refused), process exit wait continues")
        }
        is OpenCodeApiResult.Unauthorized -> {
            thisLogger().warn("[OpenCodeServerManager] /global/dispose 401 unauthorized")
        }
    }
}
