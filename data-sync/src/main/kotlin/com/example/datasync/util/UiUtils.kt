package com.example.datasync.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Helper function to handle events within each composable screen.
 * @param events: This function observes a generic [T] event flow.
 * @param key1: Optional key when this flow will be recalled, null by default.
 * @param key2: Optional key when this flow will be recalled, null by default.
 * @param onEvent: Lambda that gives us the specific event when we collect it.
 * */
@Composable
fun <T> ObserveAsEvents(
    events: Flow<T>,
    key1: Any? = null,
    key2: Any? = null,
    onEvent: (T) -> Unit
) {
    // listen to flow events emissions
    val lifecycleOwner = LocalLifecycleOwner.current

    // first key was true to fire this effect only once, once this launched effect enters the composition. Now we are using lifecycleOwner.lifecycle directly.
    LaunchedEffect(lifecycleOwner.lifecycle, key1,  key2) {
        // listen to events in a lifecycle-aware manner
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // immediate dispatcher has a characteristic that will serve as kind of work around here
            // that such events collected here can't get lost. there's a specific
            // scenario where event is sent when activity is destroyed state (screen rotation), and in
            // this state there will not be a flow collector and in this scenario could get lost.
            withContext(Dispatchers.Main.immediate) {
                // not collectLatest because we care about every single emission, not just the latest one.
                events.collect(onEvent)
            }
        }
    }
}
