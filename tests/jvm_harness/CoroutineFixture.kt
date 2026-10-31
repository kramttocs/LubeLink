package kotlinx.coroutines

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Test-only synchronous coroutine bridge. The real Android app uses kotlinx.coroutines;
 * the network calls being exercised are blocking and do not suspend during these tests. */
object Dispatchers { val IO = Any() }
suspend fun <T> withContext(@Suppress("UNUSED_PARAMETER") context: Any,
                            block: suspend () -> T): T = block()
fun <T> runBlocking(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { outcome = result }
    })
    return (outcome ?: error("Unexpected asynchronous test suspension")).getOrThrow()
}
