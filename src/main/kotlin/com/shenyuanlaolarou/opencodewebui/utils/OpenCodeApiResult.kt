package com.shenyuanlaolarou.opencodewebui.utils

// opencode server API 调用的统一结果类型,替代之前各方法独立的 null / Boolean 降级策略。
// 区分:Success(数据)、Failure(HTTP 错误带 code)、Unavailable(连接失败/超时)、Unauthorized(401)。
sealed class OpenCodeApiResult<out T> {
    data class Success<T>(val data: T) : OpenCodeApiResult<T>()
    data class Failure(val code: Int, val message: String) : OpenCodeApiResult<Nothing>()
    data object Unavailable : OpenCodeApiResult<Nothing>()
    data object Unauthorized : OpenCodeApiResult<Nothing>()

    companion object {
        const val CODE_PARSE_ERROR = -2
        const val CODE_UNKNOWN_ERROR = -1
    }
}

fun <T> OpenCodeApiResult<T>.dataOrNull(): T? = (this as? OpenCodeApiResult.Success<T>)?.data
