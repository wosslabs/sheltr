package com.sheltr.shared.retrieval

/**
 * Levenshtein distance between [a] and [b]: the minimum number of single-character insertions, deletions or
 * substitutions that turn one into the other. Uses one rolling row, so memory is O(b.length).
 */
fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + substitutionCost,
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}
