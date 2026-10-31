// Test-only JVM support, independent of production Android disk/image cache.
package android.util
open class LruCache<K,V>(size: Int) {
    private val data = mutableMapOf<K,V>()
    protected open fun sizeOf(key: K, value: V): Int = 1
    fun get(key: K): V? = data[key]
    fun put(key: K, value: V): V? = data.put(key,value)
}
