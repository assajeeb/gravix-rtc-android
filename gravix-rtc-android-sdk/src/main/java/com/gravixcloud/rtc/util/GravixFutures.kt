/*
 * Copyright 2026 Gravity Compile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gravixcloud.rtc.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/*
 * GRAVIX: CompletableFuture bridges for the Java API (minSdk 24 has CompletableFuture without desugaring). Hand-rolled
 * instead of kotlinx-coroutines-jdk8's future {}: the SDK pins coroutines 1.6.0, where that builder is a separate
 * artifact, and this covers what the Java surface needs (cancel both ways, completion on a chosen executor).
 * (GravixRegionMeasurementHandle keeps its own Deferred bridge on purpose: its futures share one measurement, so a
 * caller cancelling its future must not cancel it for everyone.)
 */

/**
 * Runs [block] in [scope] and completes the returned future with its result, or exceptionally with its failure, on
 * [completeOn] (so `thenAccept` etc. run there, e.g. the main thread). `future.cancel(...)` cancels the coroutine; a
 * coroutine cancelled from inside (e.g. the scope is closed) cancels the future.
 */
internal fun <T> launchFuture(
    scope: CoroutineScope,
    completeOn: Executor,
    block: suspend CoroutineScope.() -> T,
): CompletableFuture<T> {
    val future = CompletableFuture<T>()
    val job = scope.launch {
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e // completes the future through invokeOnCompletion below
        } catch (e: Throwable) {
            completeOn.execute { future.completeExceptionally(e) }
            return@launch
        }
        completeOn.execute { future.complete(result) }
    }
    // also covers a scope that was already cancelled (the body never runs)
    job.invokeOnCompletion { cause -> if (cause is CancellationException) completeOn.execute { future.cancel(false) } }
    future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
    return future
}
