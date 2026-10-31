package com.kramttocs.lubelink.quality

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Network calls a test answers by hand (OD-42): each call waits until the test answers it, so a test can make one
 * fail, never return, or return after the user has moved on. [hold] waits cancellably and records whether it was
 * cancelled; [holdPastCancel] keeps waiting when cancelled and returns whenever the test answers, standing in for a
 * reply that arrives late anyway, so only the caller's own check that the result is current can stop it being used.
 */
class HeldCalls<T> {
    inner class Call(val argument: String) {
        internal val reply = CompletableDeferred<Result<T>>()
        @Volatile internal var late: Continuation<T>? = null
        @Volatile var cancelled = false
        fun succeed(value: T) = answer(Result.success(value))
        fun fail(error: Exception) = answer(Result.failure(error))
        private fun answer(result: Result<T>) {
            late?.let { waiting -> late = null; result.fold({ waiting.resume(it) }, { waiting.resumeWithException(it) }) }
                ?: reply.complete(result)
        }
    }

    val calls = CopyOnWriteArrayList<Call>()
    val last: Call get() = calls.last()
    operator fun get(index: Int): Call = calls[index]

    suspend fun hold(argument: String): T {
        val call = Call(argument).also { calls += it }
        try { return call.reply.await().getOrThrow() }
        catch (e: CancellationException) { call.cancelled = true; throw e }
    }

    /** Not cancellable: [suspendCoroutine] ignores the caller's cancellation, as a reply already on its way would. */
    suspend fun holdPastCancel(argument: String): T {
        val call = Call(argument).also { calls += it }
        return suspendCoroutine { continuation -> call.late = continuation }
    }
}
