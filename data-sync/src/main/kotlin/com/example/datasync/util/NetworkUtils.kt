package com.example.datasync.util

import com.example.datasync.domain.Error
import com.example.datasync.domain.NetworkError
import com.example.datasync.domain.Result
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.channels.UnresolvedAddressException

suspend inline fun <T> safeCall(
    execute: () -> T
): Result<T, NetworkError> {
    return try {
        Result.Success(execute())
    } catch(e: UnresolvedAddressException) {
        Result.Error(NetworkError.NO_INTERNET)
    } catch(e: Exception) {
        currentCoroutineContext().ensureActive()
        Result.Error(NetworkError.UNKNOWN)
    }
}

inline fun <T, E : Error, R> Result<T, E>.map(map: (T) -> R): Result<R, E> {
    return when (this) {
        is Result.Error -> Result.Error(error)
        is Result.Success -> Result.Success(map(data))
    }
}

inline fun <T, E : Error> Result<T, E>.onSuccess(action: (T) -> Unit): Result<T, E> {
    if (this is Result.Success) action(data)
    return this
}