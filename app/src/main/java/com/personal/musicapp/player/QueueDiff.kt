package com.personal.musicapp.player

/** One in-place change to the ExoPlayer timeline; `Rebuild` when the change is not a single row. */
internal sealed class QueueEdit {
    data class Insert(val index: Int) : QueueEdit()
    data class Remove(val index: Int) : QueueEdit()
    /** `to` is the row's final index. */
    data class Move(val from: Int, val to: Int) : QueueEdit()
    object None : QueueEdit()
    object Rebuild : QueueEdit()
}

/**
 * The single-row edit that turns the queued ids [oldIds] into [newIds], verified by applying
 * it. Applying it in place keeps the current song playing; anything else asks for a rebuild.
 */
internal fun queueEditFrom(oldIds: List<String>, newIds: List<String>): QueueEdit {
    if (oldIds == newIds) return QueueEdit.None
    val first = firstQueueDifference(oldIds, newIds)
    val candidates: List<QueueEdit> = when {
        newIds.size == oldIds.size + 1 -> listOf(QueueEdit.Insert(first))
        newIds.size == oldIds.size - 1 -> listOf(QueueEdit.Remove(first))
        newIds.size == oldIds.size && first < newIds.size -> {
            // A row moved to `first` arrived from further down, or the row now at `last` came from above.
            val last = lastQueueDifference(oldIds, newIds)
            listOfNotNull(
                oldIds.indexOf(newIds[first]).takeIf { it > first }?.let { QueueEdit.Move(it, first) },
                oldIds.indexOf(newIds[last]).takeIf { it in 0 until last }?.let { QueueEdit.Move(it, last) }
            )
        }
        else -> emptyList()
    }
    return candidates.firstOrNull { applied(it, oldIds, newIds) == newIds } ?: QueueEdit.Rebuild
}

private fun applied(edit: QueueEdit, oldIds: List<String>, newIds: List<String>): List<String> {
    val out = oldIds.toMutableList()
    when (edit) {
        is QueueEdit.Insert -> out.add(edit.index, newIds[edit.index])
        is QueueEdit.Remove -> out.removeAt(edit.index)
        is QueueEdit.Move -> out.add(edit.to, out.removeAt(edit.from))
        QueueEdit.None, QueueEdit.Rebuild -> Unit
    }
    return out
}

private fun firstQueueDifference(a: List<String>, b: List<String>): Int {
    val limit = minOf(a.size, b.size)
    for (i in 0 until limit) {
        if (a[i] != b[i]) return i
    }
    return limit
}

private fun lastQueueDifference(a: List<String>, b: List<String>): Int {
    var i = 1
    val limit = minOf(a.size, b.size)
    while (i <= limit && a[a.size - i] == b[b.size - i]) i++
    return b.size - i
}
