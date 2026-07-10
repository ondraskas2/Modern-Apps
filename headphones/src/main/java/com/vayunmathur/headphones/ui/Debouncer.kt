package com.vayunmathur.headphones.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Coalesces rapid UI events (slider drags, band edits) into a single trailing action so we don't
 * flood the RFCOMM link. Each [run] cancels the previous pending action.
 */
class Debouncer(private val scope: CoroutineScope, private val delayMs: Long) {
    private var job: Job? = null

    fun run(block: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            block()
        }
    }
}

@Composable
fun rememberDebouncer(delayMs: Long = 250): Debouncer {
    val scope = rememberCoroutineScope()
    return remember { Debouncer(scope, delayMs) }
}
