package com.moxietech.family_tree.server.sources

import com.moxietech.family_tree.domain.Citation
import com.moxietech.family_tree.domain.CitationId
import com.moxietech.family_tree.domain.Confidence
import com.moxietech.family_tree.domain.MediaId
import com.moxietech.family_tree.domain.SourceId
import com.moxietech.family_tree.server.db.CitationTable
import com.moxietech.family_tree.server.db.EventTable
import com.moxietech.family_tree.server.db.PersonTable
import com.moxietech.family_tree.server.db.SourceTable
import com.moxietech.family_tree.server.db.UnionTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

internal object CitationRepository {

    /**
     * FT-001/FT-002 lock contract: `SELECT … FROM source WHERE id = ? AND tree_id = ? FOR KEY SHARE`.
     * False when the source doesn't exist in [treeId] (missing, foreign, or deleted by a
     * delete that committed while this waited). While held, a concurrent source delete's
     * `DELETE` waits for this transaction and then fails on `citation.source_id`.
     */
    fun lockSource(treeId: Uuid, sourceId: Uuid): Boolean = transaction {
        lockKeyShare(SourceTable, SourceTable.id, SourceTable.treeId, treeId, sourceId)
    }

    /**
     * The same lock on the citation's target row (`person`, `union_record` or `event`).
     * A citation has no foreign key to its target, so this lock is what makes a
     * concurrent delete of the target wait and then see the committed citation
     * (`RecordDeletion`). False when the target doesn't exist in [treeId].
     */
    fun lockTarget(treeId: Uuid, target: CitationTargetRef): Boolean = transaction {
        when (target.kind) {
            CitationTargetKind.PERSON -> lockKeyShare(PersonTable, PersonTable.id, PersonTable.treeId, treeId, target.id)
            CitationTargetKind.EVENT -> lockKeyShare(EventTable, EventTable.id, EventTable.treeId, treeId, target.id)
            CitationTargetKind.UNION -> lockKeyShare(UnionTable, UnionTable.id, UnionTable.treeId, treeId, target.id)
        }
    }

    fun insert(
        treeId: Uuid,
        sourceId: Uuid,
        target: CitationTargetRef,
        page: String?,
        confidence: Confidence,
        note: String?,
        mediaId: Uuid? = null,
    ): Citation = transaction {
        val id = Uuid.random()
        CitationTable.insert {
            it[CitationTable.id] = id
            it[CitationTable.treeId] = treeId
            it[CitationTable.sourceId] = sourceId
            it[CitationTable.targetType] = target.kind.name
            it[CitationTable.targetId] = target.id
            it[CitationTable.page] = page
            it[CitationTable.confidence] = confidence.level.toShort()
            it[CitationTable.note] = note
            it[CitationTable.mediaId] = mediaId
        }
        findById(treeId, id) ?: error("Citation $id was just inserted but cannot be read back")
    }

    /** FT-008: the citation in [treeId] whose scan is [mediaId], if any (at most one, V6). */
    fun findByMedia(treeId: Uuid, mediaId: Uuid): Citation? = transaction {
        CitationTable.selectAll().where { (CitationTable.mediaId eq mediaId) and (CitationTable.treeId eq treeId) }
            .firstOrNull()?.toCitation()
    }

    /** FT-008: the typed target of [citation], for resolving who it is about. */
    fun targetOf(citation: Citation): CitationTargetRef {
        val kind = CitationTargetKind.of(citation.target)
        val id = when (val target = citation.target) {
            is com.moxietech.family_tree.domain.CitationTarget.OnPerson -> target.personId.value
            is com.moxietech.family_tree.domain.CitationTarget.OnEvent -> target.eventId.value
            is com.moxietech.family_tree.domain.CitationTarget.OnUnion -> target.unionId.value
        }
        return CitationTargetRef(kind, Uuid.parse(id))
    }

    /** Null when [id] doesn't exist or belongs to another tree. */
    fun findById(treeId: Uuid, id: Uuid): Citation? = transaction {
        CitationTable.selectAll().where { (CitationTable.id eq id) and (CitationTable.treeId eq treeId) }
            .firstOrNull()?.toCitation()
    }

    /**
     * Citations in [treeId] on [target], in a stable order. Only same-tree rows: a
     * citation in another tree naming the same ID is never returned.
     */
    fun listByTarget(treeId: Uuid, target: CitationTargetRef): List<Citation> = transaction {
        CitationTable.selectAll()
            .where {
                (CitationTable.treeId eq treeId) and
                    (CitationTable.targetType eq target.kind.name) and
                    (CitationTable.targetId eq target.id)
            }
            .orderBy(CitationTable.id to SortOrder.ASC)
            .map { it.toCitation() }
    }

    /** FT-007: the citations in [treeId] whose target is person [personId], ordered by ID and locked `FOR UPDATE`. */
    fun lockOnPerson(treeId: Uuid, personId: Uuid): List<Citation> = transaction {
        CitationTable.selectAll()
            .where {
                (CitationTable.treeId eq treeId) and
                    (CitationTable.targetType eq CitationTargetKind.PERSON.name) and
                    (CitationTable.targetId eq personId)
            }
            .orderBy(CitationTable.id to SortOrder.ASC)
            .forUpdate(ForUpdateOption.ForUpdate)
            .map { it.toCitation() }
    }

    /**
     * FT-007: moves citation [id] from person [from] to person [to]; source, page,
     * confidence, note and media are unchanged. The caller must already hold [to]
     * under the FT-002 lock contract ([lockTarget]) in this transaction. Null when the
     * citation no longer targets [from]. Citations have no version column.
     */
    fun retargetPerson(treeId: Uuid, id: Uuid, from: Uuid, to: Uuid): Citation? = transaction {
        val updated = CitationTable.update({
            (CitationTable.id eq id) and (CitationTable.treeId eq treeId) and
                (CitationTable.targetType eq CitationTargetKind.PERSON.name) and (CitationTable.targetId eq from)
        }) {
            it[CitationTable.targetId] = to
        }
        if (updated == 0) null else findById(treeId, id) ?: error("Citation $id was just updated but cannot be read back")
    }

    private fun lockKeyShare(table: Table, idColumn: Column<Uuid>, treeColumn: Column<Uuid>, treeId: Uuid, id: Uuid): Boolean =
        table.selectAll()
            .where { (idColumn eq id) and (treeColumn eq treeId) }
            .forUpdate(ForUpdateOption.PostgreSQL.ForKeyShare())
            .firstOrNull() != null

    private fun ResultRow.toCitation(): Citation {
        val level = this[CitationTable.confidence].toInt()
        return Citation(
            id = CitationId(this[CitationTable.id].toString()),
            sourceId = SourceId(this[CitationTable.sourceId].toString()),
            target = CitationTargetKind.valueOf(this[CitationTable.targetType]).target(this[CitationTable.targetId]),
            page = this[CitationTable.page],
            confidence = Confidence.entries.firstOrNull { it.level == level }
                ?: error("Citation ${this[CitationTable.id]} has confidence $level outside 0..3"),
            note = this[CitationTable.note],
            mediaId = this[CitationTable.mediaId]?.let { MediaId(it.toString()) },
        )
    }
}
