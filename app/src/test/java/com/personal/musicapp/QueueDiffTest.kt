package com.personal.musicapp

import com.personal.musicapp.player.QueueEdit
import com.personal.musicapp.player.queueEditFrom
import org.junit.Assert.assertEquals
import org.junit.Test

/** The diff behind the real ExoPlayer queue: the car's "next" follows whatever it produces. */
class QueueDiffTest {

    private fun ids(vararg values: String) = values.toList()

    @Test
    fun playNextIsAnInsertRightAfterTheCurrentSong() {
        assertEquals(QueueEdit.Insert(4), queueEditFrom(ids("a", "b", "c", "d", "e"), ids("a", "b", "c", "d", "x", "e")))
        assertEquals(QueueEdit.Insert(0), queueEditFrom(ids("b", "c"), ids("a", "b", "c")))
        assertEquals(QueueEdit.Insert(3), queueEditFrom(ids("a", "b", "c"), ids("a", "b", "c", "d")))
    }

    @Test
    fun removeFromQueueIsARemovalAtTheFirstDifference() {
        assertEquals(QueueEdit.Remove(2), queueEditFrom(ids("a", "b", "c", "d"), ids("a", "b", "d")))
        assertEquals(QueueEdit.Remove(0), queueEditFrom(ids("a", "b"), ids("b")))
        assertEquals(QueueEdit.Remove(1), queueEditFrom(ids("a", "b"), ids("a")))
    }

    @Test
    fun aDragIsASingleMoveInEitherDirection() {
        val before = ids("a", "b", "c", "d", "e")
        assertEquals(QueueEdit.Move(0, 4), queueEditFrom(before, ids("b", "c", "d", "e", "a")))
        assertEquals(QueueEdit.Move(4, 0), queueEditFrom(before, ids("e", "a", "b", "c", "d")))
        assertEquals(QueueEdit.Move(3, 1), queueEditFrom(before, ids("a", "d", "b", "c", "e")))
        assertEquals(QueueEdit.Move(1, 3), queueEditFrom(before, ids("a", "c", "d", "b", "e")))
    }

    @Test
    fun requeueingASongAlreadyInTheQueueIsAMove() {
        val move = queueEditFrom(ids("a", "b", "c", "d", "e"), ids("a", "b", "c", "e", "d"))
        assertEquals(QueueEdit.Move(4, 3), move)
        assertEquals(QueueEdit.Move(2, 4), queueEditFrom(ids("a", "b", "c", "d", "e"), ids("a", "b", "d", "e", "c")))
    }

    @Test
    fun moreThanOneChangedRowAsksForARebuild() {
        assertEquals(QueueEdit.Rebuild, queueEditFrom(ids("a", "b", "c"), ids("c", "b", "a")))
        assertEquals(QueueEdit.Rebuild, queueEditFrom(ids("a", "b", "c", "d"), ids("d", "c", "b", "a")))
        assertEquals(QueueEdit.Rebuild, queueEditFrom(ids("a", "b", "c", "d"), ids("b", "a", "d", "c")))
    }

    @Test
    fun aSingleMoveAcrossTheWholeQueueIsStillOneEdit() {
        assertEquals(QueueEdit.Move(0, 2), queueEditFrom(ids("a", "b", "c"), ids("b", "c", "a")))
        assertEquals(QueueEdit.Move(4, 0), queueEditFrom(ids("a", "b", "c", "d", "e"), ids("e", "a", "b", "c", "d")))
    }

    @Test
    fun unchangedQueuesProduceNoEdit() {
        assertEquals(QueueEdit.None, queueEditFrom(ids("a", "b"), ids("a", "b")))
    }

    @Test
    fun replacingTheWholeQueueIsARebuild() {
        assertEquals(QueueEdit.Rebuild, queueEditFrom(ids("a", "b"), ids("x", "y")))
        assertEquals(QueueEdit.Rebuild, queueEditFrom(ids("a"), ids("x")))
    }

    @Test
    fun enteringOrLeavingAnEmptyQueueIsASingleEdit() {
        assertEquals(QueueEdit.Insert(0), queueEditFrom(emptyList(), ids("x")))
        assertEquals(QueueEdit.Remove(0), queueEditFrom(ids("x"), emptyList()))
    }
}
