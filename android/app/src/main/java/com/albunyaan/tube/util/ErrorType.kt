package com.albunyaan.tube.util

import android.content.Context
import com.albunyaan.tube.R
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class ErrorType {
    NETWORK_ERROR,
    TIMEOUT_ERROR,
    SERVER_ERROR,
    PARSE_ERROR,
    UNKNOWN_ERROR;

    companion object {
        fun fromException(exception: Throwable): ErrorType {
            return when (exception) {
                is UnknownHostException, is IOException -> NETWORK_ERROR
                is SocketTimeoutException -> TIMEOUT_ERROR
                else -> UNKNOWN_ERROR
            }
        }
    }

    fun getErrorMessage(context: Context, exception: Throwable? = null): String {
        return when (this) {
            NETWORK_ERROR -> context.getString(R.string.error_network)
            TIMEOUT_ERROR -> context.getString(R.string.error_timeout)
            SERVER_ERROR -> context.getString(R.string.error_server)
            PARSE_ERROR -> context.getString(R.string.error_parse)
            UNKNOWN_ERROR -> exception?.message ?: context.getString(R.string.error_unknown)
        }
    }

    fun getErrorTitle(context: Context): String {
        return when (this) {
            NETWORK_ERROR -> context.getString(R.string.error_network_title)
            TIMEOUT_ERROR -> context.getString(R.string.error_timeout_title)
            SERVER_ERROR -> context.getString(R.string.error_server_title)
            PARSE_ERROR -> context.getString(R.string.error_parse_title)
            UNKNOWN_ERROR -> context.getString(R.string.error_state_title)
        }
    }
}

