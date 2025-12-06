package com.example.datasync.engine

import com.example.datasync.domain.Error
import com.example.datasync.domain.Result
import com.example.datasync.util.WhileUiSubscribed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OfflineFirstLoader(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    /**
     * @param onFailure A callback invoked specifically when the [fetch] operation returns [Result.Error].
     * Useful for sending one-shot events (Toast, Snackbar) without manually checking the result.
     */
    fun <T, E : Error> load(
        scope: CoroutineScope,
        observe: Flow<T?>,
        fetch: suspend () -> Result<T, E>,
        onFailure: (suspend (E) -> Unit)? = null // <--- NEW PARAMETER
    ): DataSession<T, E> {

        val refreshTrigger = Channel<Unit>(Channel.CONFLATED)
        val statusFlow = MutableStateFlow<LoadStatus<E>>(LoadStatus.Idle)

        fun triggerLoad() {
            scope.launch(ioDispatcher) {
                statusFlow.value = LoadStatus.Loading

                // We execute the fetch logic
                // We handle the result internally, so the ViewModel doesn't have to
                when (val result = fetch()) {
                    is Result.Success -> {
                        statusFlow.value = LoadStatus.Idle
                    }
                    is Result.Error -> {
                        statusFlow.value = LoadStatus.Failed(result.error)
                        // Trigger the side-effect callback if provided
                        onFailure?.invoke(result.error)
                    }
                }
            }
        }

        refreshTrigger.receiveAsFlow()
            .onStart { emit(Unit) }
            .onEach { triggerLoad() }
            .launchIn(scope)

        val resourceFlow = combine(
            observe.flowOn(ioDispatcher),
            statusFlow
        ) { localData, status ->
            when (status) {
                is LoadStatus.Idle -> {
                    if (localData != null) Resource.Success(localData)
                    else Resource.Loading(null)
                }

                is LoadStatus.Loading -> {
                    Resource.Loading(localData)
                }

                is LoadStatus.Failed -> {
                    Resource.Failure(localData, status.error)
                }
            }
        }
            .flowOn(defaultDispatcher)
            .stateIn(
                scope = scope,
                started = WhileUiSubscribed,
                initialValue = Resource.Loading()
            )

        return object : DataSession<T, E> {
            override val state: StateFlow<Resource<T, E>> = resourceFlow
            override fun refresh() {
                refreshTrigger.trySend(Unit)
            }
        }
    }

    private sealed interface LoadStatus<out E : Error> {
        data object Idle : LoadStatus<Nothing>
        data object Loading : LoadStatus<Nothing>
        data class Failed<out E : Error>(val error: E) : LoadStatus<E>
    }
}

//class OfflineFirstLoader(
//    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
//    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
//) {
//
//    fun <T, E : Error> load(
//        scope: CoroutineScope,
//        observe: Flow<T?>,
//        fetch: suspend () -> Result<T, E>
//    ): DataSession<T, E> {
//
//        val refreshTrigger = Channel<Unit>(Channel.Factory.CONFLATED)
//        val statusFlow = MutableStateFlow<LoadStatus<E>>(LoadStatus.Idle)
//
//        fun triggerLoad() {
//            scope.launch(ioDispatcher) {
//                statusFlow.value = LoadStatus.Loading
//
//                when (val result = fetch()) {
//                    is Result.Success -> {
//                        statusFlow.value = LoadStatus.Idle
//                    }
//                    is Result.Error -> {
//                        statusFlow.value = LoadStatus.Failed(result.error)
//                    }
//                }
//            }
//        }
//
//        refreshTrigger.receiveAsFlow()
//            .onStart { emit(Unit) }
//            .onEach { triggerLoad() }
//            .launchIn(scope)
//
//        val resourceFlow = combine(
//            observe.flowOn(ioDispatcher),
//            statusFlow
//        ) { localData, status ->
//            when (status) {
//                is LoadStatus.Idle -> {
//                    if (localData != null) Resource.Success(localData)
//                    else Resource.Loading(null)
//                }
//
//                is LoadStatus.Loading -> {
//                    Resource.Loading(localData)
//                }
//
//                is LoadStatus.Failed -> {
//                    Resource.Failure(localData, status.error)
//                }
//            }
//        }
//            .flowOn(defaultDispatcher)
//            .stateIn(
//                scope = scope,
//                started = WhileUiSubscribed,
//                initialValue = Resource.Loading()
//            )
//
//        return object : DataSession<T, E> {
//            override val state: StateFlow<Resource<T, E>> = resourceFlow
//            override fun refresh() {
//                refreshTrigger.trySend(Unit)
//            }
//        }
//    }
//
//    private sealed interface LoadStatus<out E : Error> {
//        data object Idle : LoadStatus<Nothing>
//        data object Loading : LoadStatus<Nothing>
//        data class Failed<out E : Error>(val error: E) : LoadStatus<E>
//    }
//}