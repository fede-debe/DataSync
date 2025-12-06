package com.example.datasync.engine

import com.example.datasync.domain.Error
import kotlinx.coroutines.flow.StateFlow

sealed interface Resource<out T, out E : Error> {
    val data: T?

    data class Success<out T>(
        override val data: T
    ) : Resource<T, Nothing>

    data class Loading<out T>(
        override val data: T? = null
    ) : Resource<T, Nothing>

    data class Failure<out T, out E : Error>(
        override val data: T? = null,
        val error: E
    ) : Resource<T, E>
}

interface DataSession<out T, out E : Error> {
    val state: StateFlow<Resource<T, E>>
    fun refresh()
}