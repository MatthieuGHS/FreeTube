package fr.appartfinder.freetube.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxMergeTest {
    private fun op(opId: String, type: OpType, entityId: String) = OutboxOpEntity(opId, type, entityId, "{}", 0)

    @Test
    fun newOpSupersedesPendingOpsOnTheSameEntity() {
        val pending = listOf(
            op("1", OpType.UPSERT_PLAYLIST, "p"),
            op("2", OpType.UPDATE_TRACK, "t"),
            op("3", OpType.UPSERT_PLAYLIST, "p"),
            op("4", OpType.UPSERT_PLAYLIST, "q"),
        )
        val merged = mergeOutbox(pending, op("5", OpType.DELETE_PLAYLIST, "p"))
        // Other entities untouched and in their order; the new op last, with its own new opId.
        assertEquals(listOf("2", "4", "5"), merged.map { it.opId })
    }

    @Test
    fun trackAndPlaylistIdsAreDifferentEntities() {
        val merged = mergeOutbox(listOf(op("1", OpType.UPSERT_PLAYLIST, "same")), op("2", OpType.DELETE_TRACK, "same"))
        assertEquals(listOf("1", "2"), merged.map { it.opId })
    }

    @Test
    fun renameAfterRenameKeepsOnlyTheLatest() {
        val merged = mergeOutbox(listOf(op("1", OpType.UPDATE_TRACK, "t")), op("2", OpType.UPDATE_TRACK, "t"))
        assertEquals(listOf("2"), merged.map { it.opId })
    }
}
