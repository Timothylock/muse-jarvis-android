package dev.timlock.jarvis.core.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Single state holder; all changes go through [dispatch] -> [reduce]. */
class AppStore(
    private val logger: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun dispatch(input: Input) {
        _state.update { reduce(it, input, clock(), logger) }
    }
}
