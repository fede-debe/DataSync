package com.example.datasync.engine

import com.example.datasync.domain.NetworkError
import com.example.datasync.domain.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineFirstLoaderTest {

    // 1. Setup Test Coroutines
    // StandardTestDispatcher allows us to manually control time (advanceUntilIdle)
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // 2. System Under Test
    private val loader = OfflineFirstLoader(
        ioDispatcher = testDispatcher,
        defaultDispatcher = testDispatcher
    )

    @Test
    fun `load - initial success - emits loading then data`() = testScope.runTest {
        // Create a child scope for the loader to mimic ViewModel lifecycle
        // This allows us to cancel the loader's internal infinite loops without killing the test
        val loaderScope = CoroutineScope(testDispatcher + Job())

        // GIVEN - A database that starts empty
        val dbFlow = MutableStateFlow<String?>(null)

        // AND - A fetch function that simulates network delay then saves "Hello" to DB
        val fetchLogic: suspend () -> Result<String, NetworkError> = {
            dbFlow.value = "Hello" // Simulate saving to DB
            Result.Success("Hello")
        }

        // WHEN - We start loading
        val session = loader.load(loaderScope, dbFlow, fetchLogic)

        // Capture emissions
        val results = mutableListOf<Resource<String, NetworkError>>()
        // Collect in backgroundScope so it doesn't block but gets cancelled automatically
        // (though we can also cancel manually)
        val job = launch { session.state.toList(results) }

        // Execute all pending coroutines
        advanceUntilIdle()

        // THEN - We expect:
        // 1. Initial Loading (while waiting for fetch/flow combination)
        // 2. Loading with data (DB updated, but fetch not technically "finished" processing in flow)
        // 3. Success with data (Fetch returned Success, status becomes Idle)

        // Note: The specific emission count depends on flow combination timing,
        // but the final state MUST be Success("Hello").

        val lastState = results.last()
        assertTrue("Final state should be Success", lastState is Resource.Success)
        assertEquals("Hello", lastState.data)

        // Cleanup
        job.cancel()
        loaderScope.cancel()
    }

    @Test
    fun `load - initial failure - emits failure but keeps old data if available`() = testScope.runTest {
        val loaderScope = CoroutineScope(testDispatcher + Job())

        // GIVEN - A database that ALREADY has data (Stale data)
        val dbFlow = MutableStateFlow<String?>("Cached Data")

        // AND - A fetch function that fails
        val fetchLogic: suspend () -> Result<String, NetworkError> = {
            Result.Error(NetworkError.NO_INTERNET)
        }

        // WHEN - Start loading
        val session = loader.load(loaderScope, dbFlow, fetchLogic)

        val results = mutableListOf<Resource<String, NetworkError>>()
        val job = launch { session.state.toList(results) }
        advanceUntilIdle()

        // THEN - Verify history
        // Should start with Loading("Cached Data") -> End with Failure("Cached Data", NO_INTERNET)

        val finalState = results.last()
        assertTrue("Final state should be Failure", finalState is Resource.Failure)
        assertEquals("Cached Data", finalState.data) // Data preserved
        assertEquals(NetworkError.NO_INTERNET, (finalState as Resource.Failure).error)

        // Cleanup
        job.cancel()
        loaderScope.cancel()
    }

    @Test
    fun `refresh - updates data and resets state`() = testScope.runTest {
        val loaderScope = CoroutineScope(testDispatcher + Job())

        // GIVEN - Initial load already completed with "Data 1"
        val dbFlow = MutableStateFlow<String?>("Data 1")

        // We need a dynamic fetch that changes behavior based on call count
        var callCount = 0
        val fetchLogic: suspend () -> Result<String, NetworkError> = {
            callCount++
            if (callCount == 2) {
                dbFlow.value = "Data 2" // Second call updates DB
            }
            Result.Success("Ignored")
        }

        val session = loader.load(loaderScope, dbFlow, fetchLogic)

        val results = mutableListOf<Resource<String, NetworkError>>()
        val job = launch { session.state.toList(results) }
        advanceUntilIdle() // Finish initial load

        // Verify initial state
        assertEquals("Data 1", results.last().data)
        assertTrue(results.last() is Resource.Success)

        // WHEN - We force a refresh
        session.refresh()
        advanceUntilIdle()

        // THEN - State transitions
        // We should see a transition back to Loading("Data 1") -> Success("Data 2")

        // Check if we captured the update
        val finalState = results.last()
        assertEquals("Data 2", finalState.data)
        assertTrue(finalState is Resource.Success)

        // Verify we went through a loading state during refresh (optional but good behavior check)
        // We look at the emissions after the initial success
        val refreshEmissions = results.dropWhile { it.data == "Data 1" && it is Resource.Success }
        // There should be at least one Loading state in the sequence before final success
        val hasLoadingState = results.any { it is Resource.Loading }
        assertTrue("Should show loading state during operations", hasLoadingState)

        // Cleanup
        job.cancel()
        loaderScope.cancel()
    }

    @Test
    fun `load - failure with empty cache - emits failure with null data`() = testScope.runTest {
        val loaderScope = CoroutineScope(testDispatcher + Job())

        // GIVEN - Empty DB
        val dbFlow = MutableStateFlow<String?>(null)

        // AND - Network Error
        val fetchLogic: suspend () -> Result<String, NetworkError> = {
            Result.Error(NetworkError.SERVER_ERROR)
        }

        val session = loader.load(loaderScope, dbFlow, fetchLogic)

        val results = mutableListOf<Resource<String, NetworkError>>()
        val job = launch { session.state.toList(results) }
        advanceUntilIdle()

        val finalState = results.last()
        assertTrue(finalState is Resource.Failure)
        assertEquals(null, finalState.data)
        assertEquals(NetworkError.SERVER_ERROR, (finalState as Resource.Failure).error)

        // Cleanup
        job.cancel()
        loaderScope.cancel()
    }

    @Test
    fun `db update - updates UI without triggering fetch`() = testScope.runTest {
        val loaderScope = CoroutineScope(testDispatcher + Job())

        // GIVEN - Initial load complete
        val dbFlow = MutableStateFlow<String?>("Initial")
        var fetchCount = 0

        val fetchLogic: suspend () -> Result<String, NetworkError> = {
            fetchCount++
            Result.Success("ok")
        }

        val session = loader.load(loaderScope, dbFlow, fetchLogic)

        val results = mutableListOf<Resource<String, NetworkError>>()
        val job = launch { session.state.toList(results) }
        advanceUntilIdle()

        assertEquals(1, fetchCount) // Initial load happened
        assertEquals("Initial", results.last().data)

        // WHEN - The database updates independently (e.g. Socket, Background Sync)
        dbFlow.value = "Updated Externally"
        advanceUntilIdle()

        // THEN - UI updates to Success("Updated Externally")
        assertEquals("Updated Externally", results.last().data)
        assertTrue(results.last() is Resource.Success)

        // AND - Fetch was NOT triggered again
        assertEquals(1, fetchCount)

        // Cleanup
        job.cancel()
        loaderScope.cancel()
    }

    @Test
    fun `load - success with empty list - emits success with empty data`() = testScope.runTest {
        val loaderScope = CoroutineScope(testDispatcher + Job())

        // GIVEN - A database that returns an empty list (e.g. no transactions yet)
        val dbFlow = MutableStateFlow<List<String>?>(null)

        // AND - Fetch returns success with empty list
        val fetchLogic: suspend () -> Result<List<String>, NetworkError> = {
            dbFlow.value = emptyList() // DB updates to empty list
            Result.Success(emptyList())
        }

        val session = loader.load(loaderScope, dbFlow, fetchLogic)

        val results = mutableListOf<Resource<List<String>, NetworkError>>()
        val job = launch { session.state.toList(results) }
        advanceUntilIdle()

        // THEN - State should be Success with an empty list (not null, not loading)
        val finalState = results.last()
        assertTrue("Final state should be Success", finalState is Resource.Success)
        assertTrue("Data should be empty list", finalState.data?.isEmpty() == true)

        // Cleanup
        job.cancel()
        loaderScope.cancel()
    }

    @Test
    fun `refresh - rapid triggers - system remains stable`() = testScope.runTest {
        val loaderScope = CoroutineScope(testDispatcher + Job())
        val dbFlow = MutableStateFlow<String?>("Data")
        var fetchCount = 0

        // AND - A fetch that takes some time
        val fetchLogic: suspend () -> Result<String, NetworkError> = {
            fetchCount++
            delay(100) // Simulate network delay
            Result.Success("Data")
        }

        val session = loader.load(loaderScope, dbFlow, fetchLogic)
        val job = launch { session.state.toList(mutableListOf()) }
        advanceUntilIdle() // Let initial load finish

        val initialCount = fetchCount // Should be 1

        // WHEN - Rapidly trigger refresh 5 times
        repeat(5) {
            session.refresh()
        }

        advanceUntilIdle()

        // THEN - The system should handle it gracefully.
        // Note: With Channel.CONFLATED and a fast dispatcher,
        // it might run 1 extra time or all 5 times depending on coroutine scheduling,
        // but it MUST NOT crash and MUST eventually settle.
        assertTrue("Should have fetched at least one more time", fetchCount > initialCount)

        // Verify final state is stable (Success)
        val currentState = session.state.value
        assertTrue(currentState is Resource.Success)

        // Cleanup
        job.cancel()
        loaderScope.cancel()
    }

    @Test
    fun `load - failure triggers onFailure callback`() = testScope.runTest {
        val loaderScope = CoroutineScope(testDispatcher + Job())
        val dbFlow = MutableStateFlow<String?>(null)
        var capturedError: NetworkError? = null

        // GIVEN - A fetch that fails
        val fetchLogic: suspend () -> Result<String, NetworkError> = {
            Result.Error(NetworkError.SERVER_ERROR)
        }

        // WHEN - We load with an onFailure callback
        val session = loader.load(
            scope = loaderScope,
            observe = dbFlow,
            fetch = fetchLogic,
            onFailure = { error ->
                capturedError = error
            }
        )

        // Start collecting state to ensure flow is active (though load starts immediately)
        val job = launch { session.state.toList() }
        advanceUntilIdle()

        // THEN - The callback should have been invoked with the correct error
        assertEquals(NetworkError.SERVER_ERROR, capturedError)

        // Cleanup
        job.cancel()
        loaderScope.cancel()
    }
}