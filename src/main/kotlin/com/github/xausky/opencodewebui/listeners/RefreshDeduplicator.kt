package com.github.xausky.opencodewebui.listeners

import java.util.concurrent.ConcurrentHashMap

/**
 * 文件刷新去重器。
 * 记录最近刷新的文件路径，在指定时间窗口内不重复刷新同一个文件。
 */
class RefreshDeduplicator {
    private val lastRefreshTimes = ConcurrentHashMap<String, Long>()

    /**
     * 检查指定文件是否应该刷新（在窗口期内已刷新的文件返回 false）。
     * @param filePath 文件绝对路径
     * @param windowMs 去重时间窗口（毫秒）
     * @return true 如果应该刷新，false 如果最近已刷新过
     */
    fun shouldRefresh(filePath: String, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val lastRefresh = lastRefreshTimes[filePath]
        return if (lastRefresh != null && now - lastRefresh < windowMs) {
            false
        } else {
            lastRefreshTimes[filePath] = now
            true
        }
    }

    /** 清空所有去重记录 */
    fun reset() {
        lastRefreshTimes.clear()
    }
}
