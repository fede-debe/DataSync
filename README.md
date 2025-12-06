# DataSync

DataSync is a Kotlin-first library for Android that simplifies building robust, offline-first data loading mechanisms. It provides a structured and opinionated way to handle data fetching, caching, and error handling, ensuring a consistent user experience even in unreliable network conditions.

## Core Features

*   **Offline-First Data Loading:** Built around the concept of a single source of truth, DataSync prioritizes displaying cached data while seamlessly fetching updates from the network.
*   **Declarative API:** A simple, declarative API for observing data streams and triggering refreshes.
*   **Structured Error Handling:** Provides a clear and consistent way to handle network and other errors.
*   **Coroutines-Based:** Built on top of Kotlin Coroutines and Flow for efficient and scalable asynchronous operations.

## Key Components

*   **`DataSession`:** Represents a live, offline-first data stream. It provides a `StateFlow` of `Resource` objects that your UI can observe.
*   **`Resource`:** A sealed class that represents the state of your data: `Success`, `Loading`, or `Failure`.
*   **`OfflineFirstLoader`:** A utility for creating `DataSession` instances. It manages the underlying data fetching, caching, and error handling logic.

## Getting Started

1.  **Add the dependency to your `build.gradle.kts` file:**

    ```kotlin
    implementation("com.example.datasync:data-sync:1.0.0")
    ```

2.  **Define your data sources and repository:**

    Implement a repository that fetches data from a remote source and caches it locally (e.g., in a Room database).

3.  **Create a `DataSession` in your ViewModel:**

    ```kotlin
    val dataSession = OfflineFirstLoader().load(
        scope = viewModelScope,
        observe = repository.getData(), // Flow<Data?>
        fetch = { repository.fetchData() }, // suspend () -> Result<Data, Error>
        onFailure = { error ->
            // Handle failure (e.g., show a toast or log the error)
        }
    )
    ```

4.  **Observe the `DataSession` in your UI:**

    ```kotlin
    val state by dataSession.state.collectAsStateWithLifecycle()

    when (state) {
        is Resource.Success -> {}// Display data
        is Resource.Loading -> {}// Display loading indicator
        is Resource.Failure -> {}// Display error message
    }
    ```

## AI Agent Guidelines

This project includes an `AGENTS.md` file that provides detailed guidelines for using AI assistance in development. This file defines the canonical way to model results and errors, perform network calls, and implement offline-first data flows. When working with this library, AI agents should always read and follow the guidelines in `data-sync/AGENTS.md`.
