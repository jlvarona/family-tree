package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.db.CitationTable
import com.moxietech.family_tree.server.db.EventTable
import com.moxietech.family_tree.server.db.MembershipTable
import com.moxietech.family_tree.server.db.TreeGrantTable
import com.moxietech.family_tree.server.db.UnionChildTable
import com.moxietech.family_tree.server.db.UnionTable
import com.moxietech.family_tree.server.errors.referenced
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import java.sql.SQLException
import kotlin.uuid.Uuid

/**
 * FT-001 deletes: a record is deleted only when nothing else in its tree refers
 * to it ("Deleting referenced records" in concept-architecture.md). Nothing is
 * cascaded or unlinked.
 *
 * Incoming references, from V1/V2 (every one is same-tree since V2; FT-007's
 * `PersonMergeService` moves exactly the person references below):
 * - person ← union_record.partner1_id / partner2_id, union_child.child_id,
 *   event.person_id, membership.person_id, tree_grant.anchor_person_id (FT-010)
 *   (foreign keys); citation target PERSON. grant_scope.person_id is derived cache,
 *   not a reference: it cascades (V7), so it never makes a person "referenced"
 * - union_record ← union_child.union_id, event.union_id (foreign keys); citation target UNION
 * - event ← citation target EVENT (no foreign key)
 * - place ← event.place_id (foreign key)
 * - source ← citation.source_id (foreign key)
 * - union_child ← nothing
 * audit_log.entity_id is text, not a reference: history stays after a delete.
 *
 * Each delete runs in one transaction:
 * 1. resolve the record in the tree (404), compare versions (409 stale);
 * 2. the checks below, for a clean 409 in the normal case;
 * 3. `DELETE … WHERE id, tree_id, version` (0 rows: 409 stale). A writer that
 *    inserted a foreign-key reference concurrently holds `FOR KEY SHARE` on
 *    the record, so the DELETE waits for it and PostgreSQL then rejects it with
 *    23503; [guarded] maps that, after rollback, to the same 409;
 * 4. citation targets (person, union, event) are checked again after the DELETE
 *    holds the row lock, because a citation has no foreign key (see [isCited]);
 * 5. the audit entry (before = record, after = null).
 */
internal object RecordDeletion {

    val PERSON_FOREIGN_KEYS = setOf(
        "union_record_partner1_id_fkey", "union_record_partner1_same_tree_fkey",
        "union_record_partner2_id_fkey", "union_record_partner2_same_tree_fkey",
        "union_child_child_id_fkey", "union_child_child_same_tree_fkey",
        "event_person_id_fkey", "event_person_same_tree_fkey",
        "membership_person_id_fkey", "membership_person_same_tree_fkey",
        // FT-010: a grant's anchor is a real reference. grant_scope's person key is
        // not listed: it cascades, so the derived cache never blocks a delete.
        "tree_grant_anchor_same_tree_fkey",
    )
    val UNION_FOREIGN_KEYS = setOf(
        "union_child_union_id_fkey", "union_child_union_same_tree_fkey",
        "event_union_id_fkey", "event_union_same_tree_fkey",
    )
    val PLACE_FOREIGN_KEYS = setOf("event_place_id_fkey", "event_place_same_tree_fkey")
    val SOURCE_FOREIGN_KEYS = setOf("citation_source_id_fkey", "citation_source_same_tree_fkey")

    const val PERSON = "PERSON"
    const val UNION = "UNION"
    const val EVENT = "EVENT"

    /**
     * Runs [block] in its own transaction. A foreign-key violation on one of
     * [foreignKeys] (the constraints that reference the deleted table), seen
     * after the transaction rolled back, becomes [referenced]; every other
     * database error propagates unchanged.
     */
    suspend fun <T> guarded(entity: String, id: String, foreignKeys: Set<String>, block: () -> T): T = try {
        withContext(DatabaseDispatcher) { transaction { block() } }
    } catch (e: SQLException) {
        if (e.isForeignKeyViolation(foreignKeys)) throw referenced(entity, id)
        throw e
    }

    fun personIsReferenced(treeId: Uuid, personId: Uuid): Boolean =
        exists(UnionTable) {
            (UnionTable.treeId eq treeId) and ((UnionTable.partner1Id eq personId) or (UnionTable.partner2Id eq personId))
        } ||
            exists(UnionChildTable) { (UnionChildTable.treeId eq treeId) and (UnionChildTable.childId eq personId) } ||
            exists(EventTable) { (EventTable.treeId eq treeId) and (EventTable.personId eq personId) } ||
            exists(MembershipTable) { (MembershipTable.treeId eq treeId) and (MembershipTable.personId eq personId) } ||
            exists(TreeGrantTable) { (TreeGrantTable.treeId eq treeId) and (TreeGrantTable.anchorPersonId eq personId) } ||
            isCited(treeId, PERSON, personId)

    fun unionIsReferenced(treeId: Uuid, unionId: Uuid): Boolean =
        exists(UnionChildTable) { (UnionChildTable.treeId eq treeId) and (UnionChildTable.unionId eq unionId) } ||
            exists(EventTable) { (EventTable.treeId eq treeId) and (EventTable.unionId eq unionId) } ||
            isCited(treeId, UNION, unionId)

    fun placeIsReferenced(treeId: Uuid, placeId: Uuid): Boolean =
        exists(EventTable) { (EventTable.treeId eq treeId) and (EventTable.placeId eq placeId) }

    fun sourceIsReferenced(treeId: Uuid, sourceId: Uuid): Boolean =
        exists(CitationTable) { (CitationTable.treeId eq treeId) and (CitationTable.sourceId eq sourceId) }

    /**
     * Whether a citation in [treeId] targets [targetId]. A citation in another
     * tree naming the same ID doesn't count (and isn't revealed).
     *
     * Lock contract for citation writers (`CitationService` follows it): in the same
     * transaction as the insert, first lock the target row with
     * `SELECT … FROM person|union_record|event WHERE id = ? AND tree_id = ? FOR KEY SHARE`
     * and return 404 if no row comes back. A delete's DELETE statement then
     * waits for that writer's commit, and step 4 above sees its citation; a
     * writer that arrives after the DELETE waits for the delete's commit and
     * finds no row. Moving a citation to another target must lock the new one.
     */
    fun isCited(treeId: Uuid, targetType: String, targetId: Uuid): Boolean =
        exists(CitationTable) {
            (CitationTable.treeId eq treeId) and (CitationTable.targetType eq targetType) and (CitationTable.targetId eq targetId)
        }

    private fun exists(table: Table, where: () -> Op<Boolean>): Boolean = transaction {
        table.selectAll().where { where() }.limit(1).firstOrNull() != null
    }
}

private const val FOREIGN_KEY_VIOLATION = "23503"

internal fun SQLException.isForeignKeyViolation(constraints: Set<String>): Boolean =
    generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<PSQLException>()
        .any { it.sqlState == FOREIGN_KEY_VIOLATION && it.serverErrorMessage?.constraint in constraints }

private const val UNIQUE_KEY_VIOLATION = "23505"

/** PostgreSQL's unique violation on [constraint] (FT-008 media attachments), anywhere in the cause chain. */
internal fun SQLException.isUniqueViolation(constraint: String): Boolean =
    generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<PSQLException>()
        .any { it.sqlState == UNIQUE_KEY_VIOLATION && it.serverErrorMessage?.constraint == constraint }
