package dev.cyh.aamp.library

/** Immutable Long to Int map over sorted primitive arrays; lookups are binary searches without boxing. */
class LongIntMap private constructor(private val keys: LongArray, private val values: IntArray) {
    /** The value for [key], or -1 when absent. */
    operator fun get(key: Long): Int {
        val at = keys.binarySearch(key)
        return if (at >= 0) values[at] else -1
    }

    companion object {
        /** Maps `keys[i]` to `i`. Keys must be distinct. */
        fun indexOf(keys: LongArray): LongIntMap {
            val order = keys.indices.sortedBy { keys[it] }
            return LongIntMap(
                LongArray(order.size) { keys[order[it]] },
                IntArray(order.size) { order[it] },
            )
        }
    }
}
