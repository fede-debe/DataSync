# DataSync

DataSync is a Kotlin-first library for Android that simplifies building robust, offline-first data loading mechanisms. It provides a structured and opinionated way to handle data fetching, caching, and UI event handling, ensuring a consistent user experience even in unreliable network conditions.

## Core Features

*   **Offline-First Data Loading:** Built around the concept of a single source of truth, DataSync prioritizes displaying cached data while seamlessly fetching updates from the network.
*   **Declarative API:** A simple, declarative API for observing data streams and triggering refreshes.
*   **Structured Error Handling:** Provides a clear and consistent way to handle network and other errors.
*   **Lifecycle-Aware UI Event Handling:** Includes helpers for observing one-shot events from a ViewModel in a lifecycle-aware manner.
*   **Coroutines-Based:** Built on top of Kotlin Coroutines and Flow for efficient and scalable asynchronous operations.

## Key Components

*   **`DataSession`:** Represents a live, offline-first data stream. It provides a `StateFlow` of `Resource` objects that your UI can observe.
*   **`Resource`:** A sealed class that represents the state of your data: `Success`, `Loading`, or `Failure`.
*   **`OfflineFirstLoader`:** A utility for creating `DataSession` instances. It manages the underlying data fetching, caching, and error handling logic.
*   **`ObserveAsEvents`:** A Composable helper function to safely collect one-shot events (like showing a Toast) from a ViewModel.

## Getting Started

1.  **Add the dependency to your `build.gradle.kts` file:**

    ```kotlin
    implementation("com.example.datasync:data-sync:1.0.5")
    ```

2.  **Define your data sources and repository:**

    Implement a repository that fetches data from a remote source and caches it locally (e.g., in a Room database). See the `AGENTS.md` file for a detailed pattern.

3.  **Create a `DataSession` in your ViewModel:**

    ```kotlin
    class MyViewModel(
        repo: ItemsRepository,
        loader: OfflineFirstLoader
    ) : ViewModel() {

        private val _events = Channel<MyEvent>()
        val events = _events.receiveAsFlow()

        val session: DataSession<List<Item>, NetworkError> = loader.load(
            scope = viewModelScope,
            observe = repo.getItems(),          // Flow<List<Item>?>
            fetch = { repo.fetchItems() },      // suspend -> Result<List<Item>, NetworkError>
            onFailure = { error ->
                // Send a one-shot event to the UI
                _events.send(MyEvent.ShowErrorToast(error))
            }
        )

        val uiState: StateFlow<MyUiState> = session.state.map {}.stateIn()
    }
    ```

4.  **Observe the State and Events in your UI:**

    ```kotlin
    // In your Composable function
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Handle one-shot events like showing a toast
    ObserveAsEvents(flow = viewModel.events) { event ->
        when (event) {
            is MyEvent.ShowErrorToast -> {
                // Note: The `toString(context)` extension function for localization
                // should be defined in your app module.
                Toast.makeText(context, event.error.toString(context), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Render your UI based on the state
    when {
        uiState.isLoading -> { /* Show loading indicator */ }
        uiState.error != null -> { /* Show error message (inline or full-screen) */ }
        else -> { /* Show your data (uiState.items) */ }
    }
    ```
    For a complete guide on localizing errors, see the `AGENTS.md` file.

## AI Agent Guidelines

This project includes an `AGENTS.md` file that provides detailed guidelines for using AI assistance in development. This file defines the canonical way to model results and errors, perform network calls, and implement offline-first data flows. When working with this library, AI agents should always read and follow the guidelines in `data-sync/AGENTS.md`.
