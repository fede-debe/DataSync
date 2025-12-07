# Data Sync Module & Offline-First Guidelines

This module provides the **canonical** way to:

- Handle **network calls** and errors.
- Represent **typed results**.
- Implement **offline-first** loading with DB + network.
- Expose data to UI as a `Resource` (`Success`, `Loading`, `Failure`).

**Goal:**  
Ensure consistent, robust, and offline-capable data handling across the whole app.

AI agents: when working with network/data loading logic, **you must follow these rules**.

---

## 1. Core building blocks

### 1.1 Result & Error types

**Location**

- `com.example.datasync.domain.Error` (marker interface)
- `com.example.datasync.domain.DomainError` (`typealias DomainError = Error`)
- `com.example.datasync.domain.Result`
- `com.example.datasync.domain.NetworkError`

**Result**

```kotlin
sealed interface Result<out D, out E : Error> {
    data class Success<out D>(val data: D) : Result<D, Nothing>
    data class Error<out E : DomainError>(val error: E) : Result<Nothing, E>
}

typealias EmptyResult<E> = Result<Unit, E>
```

**NetworkError**

```kotlin
enum class NetworkError : Error {
    REQUEST_TIMEOUT,
    TOO_MANY_REQUESTS,
    NO_INTERNET,
    SERVER_ERROR,
    SERIALIZATION,
    UNKNOWN,
}
```

### 1.2 Utilities

**Location**

* `com.example.datasync.util.safeCall`
* `com.example.datasync.util.map`
* `com.example.datasync.util.onSuccess`
* `com.example.datasync.util.WhileUiSubscribed`

**Rules**

* ❌ Do **not** use `kotlin.Result` or ad-hoc `try/catch` for HTTP calls.
* ✅ Always wrap HTTP calls using `safeCall { ... }`.

```kotlin
suspend inline fun <T> safeCall(
    execute: () -> T
): Result<T, NetworkError>
```

Current behavior:

* Maps `UnresolvedAddressException` → `NetworkError.NO_INTERNET`.
* For any other exception:

    * Cancels cooperatively via `ensureActive()`.
    * Returns `Result.Error(NetworkError.UNKNOWN)`.

**Result helpers**

```kotlin
inline fun <T, E : Error, R> Result<T, E>.map(
    map: (T) -> R
): Result<R, E>

inline fun <T, E : Error> Result<T, E>.onSuccess(
    action: (T) -> Unit
): Result<T, E>
```

Use `map` to transform successful data while preserving the same error type.

---

## 2. Offline-first engine

**Location**

* `com.example.datasync.engine.OfflineFirstLoader`
* `com.example.datasync.engine.DataSession`
* `com.example.datasync.engine.Resource`

### 2.1 Resource

```kotlin
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
```

**Interpretation**

* `Success(data)` – data is current and valid.
* `Loading(data)` – a fetch is ongoing; `data` may be stale or null.
* `Failure(data, error)` – last fetch failed; `data` may still be from cache.

UI must **never** throw away valid `data` just because the state is `Loading` or `Failure`.

### 2.2 DataSession

```kotlin
interface DataSession<out T, out E : Error> {
    val state: StateFlow<Resource<T, E>>
    fun refresh()
}
```

A `DataSession` represents a **live offline-first stream** for one screen or list.

### 2.3 OfflineFirstLoader.load

**Signature**

```kotlin
class OfflineFirstLoader(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    fun <T, E : Error> load(
        scope: CoroutineScope,
        observe: Flow<T?>,
        fetch: suspend () -> Result<T, E>,
        onFailure: (suspend (E) -> Unit)? = null
    ): DataSession<T, E>
}
```

**Behavior**

* Internally manages a `LoadStatus` (Idle / Loading / Failed).
* Starts with an **initial load**.
* On each refresh:

    * Calls `fetch()`.
    * On `Result.Success` → marks status `Idle` (DB should be updated by repository).
    * On `Result.Error` → marks status `Failed(error)` and calls `onFailure(error)` if provided.
* Combines:

    * `observe` (flow of data from DB).
    * `statusFlow` (load status).
* Emits a `StateFlow<Resource<T, E>>` with the following rules:

    * `Idle + localData != null` → `Resource.Success(localData)`
    * `Idle + localData == null` → `Resource.Loading(null)`
    * `Loading + localData` → `Resource.Loading(localData)`
    * `Failed(error) + localData` → `Resource.Failure(localData, error)`

---

## 3. Data layer usage rules

### 3.1 Remote data source

**Responsibility:** HTTP requests only.

**Rules**

* Every API call **must** be wrapped in `safeCall`.
* Deserialize the body **inside** `safeCall`.

**Pattern**

```kotlin
class ItemsRemoteDataSource(
    private val client: HttpClient
) {
    suspend fun getItems(): Result<List<ItemDto>, NetworkError> {
        return safeCall {
            client.get("endpoint").body<List<ItemDto>>() // deserialize here
        }
    }
}
```

Do **not** catch exceptions manually in the remote data source unless you’re mapping them to specific `NetworkError` values.

### 3.2 Repository

**Responsibility:** Orchestrate **Remote + Local** as single source of truth.

**Rules**

* `fetchX()`:

    * Calls remote.
    * On success:

        * Save to DB.
        * Return `Result.Success(domainModel)`.
    * On error:

        * Return `Result.Error(NetworkError)` or domain-specific error.
* `getX()`:

    * Returns `Flow<T?>` from DAO (mapped to domain).

**Pattern**

```kotlin
class ItemsRepository(
    private val remote: ItemsRemoteDataSource,
    private val dao: ItemsDao
) {
    fun getItems(): Flow<List<Item>?> =
        dao.observeAll().map { entities -> entities?.toDomain() }

    suspend fun fetchItems(): Result<List<Item>, NetworkError> {
        return when (val result = remote.getItems()) {
            is Result.Success -> {
                val entities = result.data.toEntity()
                dao.upsertAll(entities)
                Result.Success(result.data.toDomain())
            }
            is Result.Error -> Result.Error(result.error)
        }
    }
}
```

For multiple independent calls, repositories may use `supervisorScope + async`, but **still** return `Result<*, E>`.

---

## 4. ViewModel pattern (offline-first)

**CRITICAL RULE:**
For lists / screens that are offline-first, **do not** manually manage `_isLoading` or call `viewModelScope.launch { fetch() }` to update UI state.

Always use `OfflineFirstLoader`.

### 4.1 Standard ViewModel usage

```kotlin
class MyViewModel(
    private val repo: ItemsRepository,
    private val loader: OfflineFirstLoader
) : ViewModel() {

    private val _events = Channel<MyEvent>()
    val events = _events.receiveAsFlow()

    private val session: DataSession<List<Item>, NetworkError> = loader.load(
        scope = viewModelScope,
        observe = repo.getItems(),          // Flow<List<Item>?>
        fetch = { repo.fetchItems() },      // suspend -> Result<List<Item>, NetworkError>
        onFailure = { error ->
            _events.send(MyEvent.Error(error))
        }
    )

    val uiState: StateFlow<MyUiState> = session.state
        .map { resource ->
            MyUiState(
                items = resource.data.orEmpty(),
                isLoading = resource is Resource.Loading,
                error = (resource as? Resource.Failure)?.error
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = WhileUiSubscribed,
            initialValue = MyUiState()
        )

    fun onRefresh() {
        session.refresh()
    }
}
```

---

## 5. UI / Compose rules

When consuming data from `DataSession`:

### 5.1 State & lifecycle

* Use an `@Immutable data class MyUiState(...)`.
* In composables, use `collectAsStateWithLifecycle()` on `uiState`.

### 5.2 Offline-first UX

* If `Resource.Loading` has non-null data → show data + loading indicator.
* If `Resource.Failure` has non-null data → keep data on screen; show Snackbar/Toast or small inline message.
* Only show a full-screen error when `data == null` and state is `Failure`.

### 5.3 Events

* Use a one-shot event pattern (`Channel` + `receiveAsFlow`) from the ViewModel for transient UI events like showing a Toast.
* In composables, use the `ObserveAsEvents(events) { ... }` helper to consume these events in a lifecycle-aware way.

**Example:** Showing a toast for a failed network refresh.

```kotlin
val context = LocalContext.current
ObserveAsEvents(flow = viewModel.events) { event ->
    when (event) {
        is MyEvent.Error -> { // Assuming ViewModel exposes this event on failure
            Toast.makeText(
                context,
                event.error.toString(context), // See localization pattern below
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
```

### 5.4 Localizing Errors

The `data-sync` library provides the `NetworkError` enum, but it does **not** provide UI-facing error strings. This is because a library module cannot bundle its own Android string resources (`res/values/xml`).

The app module is responsible for mapping each error to a user-friendly, localized string.

**Recommended Pattern:**
Define an extension function in your `app` module to handle the mapping.

**Location:** A UI-level utility file in your app, e.g., `com/example/myapp/ui/utils/ErrorUtils.kt`.

```kotlin
/**
 * Maps a [NetworkError] to a localized string.
 * This function should live in the app module, not the data-sync library.
 */
fun NetworkError.toString(context: Context): String {
    val resId = when (this) {
        NetworkError.NO_INTERNET -> R.string.error_no_internet
        NetworkError.SERVER_ERROR -> R.string.error_server_error
        NetworkError.UNKNOWN -> R.string.error_unknown
        NetworkError.REQUEST_TIMEOUT -> R.string.error_request_timeout
        NetworkError.SERIALIZATION -> R.string.error_serialization
        NetworkError.TOO_MANY_REQUESTS -> R.string.error_too_many_requests
    }
    return context.getString(resId)
}
```

You would then need to define these string resources in your app's `res/values/strings.xml`:

```xml
<resources>
    <string name="error_no_internet">No internet connection.</string>
    <string name="error_server_error">A server error occurred.</string>
    <string name="error_unknown">An unknown error occurred.</string>
    <string name="error_request_timeout">The request timed out.</string>
    <string name="error_serialization">There was an issue processing the data.</string>
    <string name="error_too_many_requests">You have made too many requests.</string>
</resources>
```
This approach keeps UI concerns (like localization) in the UI layer, and data concerns in the data layer. You can extend this pattern for your own custom `DomainError` types as well.

---

## 6. Extending error handling

You can define feature-specific errors by implementing `Error`:

```kotlin
enum class ItemsError : DomainError {
    Network(NetworkError), // wrap network errors if needed
    Validation,
    Unknown,
}
```

Then your repository can map `NetworkError` to `ItemsError` and return `Result<*, ItemsError>` instead of `NetworkError`.

**Rule:**
Mapping from `NetworkError` → feature-specific `DomainError` should live in repositories or a dedicated mapper function, **not** in the UI.

---

## 7. Checklist for agents

Before completing a change that involves data sync / offline-first, check:

* [ ] Remote data source uses `safeCall { ... }` and **no** `kotlin.Result`.
* [ ] Repository `fetchX()`:

    * [ ] Saves successful data to DB.
    * [ ] Returns `Result<Domain, ErrorType>`.
* [ ] Repository `getX()` returns `Flow<T?>` from DAO.
* [ ] ViewModel uses `OfflineFirstLoader.load(...)` for the main list.
