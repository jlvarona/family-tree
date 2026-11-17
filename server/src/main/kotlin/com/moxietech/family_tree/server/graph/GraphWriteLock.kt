package com.moxietech.family_tree.server.graph

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * FT-007: a per-tree transaction-level advisory lock that serializes a person merge
 * with the writers that can add a reference to a person or an ancestry edge: union
 * create and update (partners), child-link create, and event create and update
 * (subject). Those writers take it shared as the first statement of their
 * transaction; a merge takes it exclusive before any row lock. Writers don't block
 * each other, and the fixed order (advisory lock, then rows) can't deadlock.
 *
 * Why it's needed beyond the merge's row locks:
 * - A writer that resolved the retired person before the merge locked it would
 *   otherwise fail its insert's foreign-key check after the merge commits (a 500).
 *   With the lock it waits, then resolves the person afresh and gets the usual 404.
 * - The ancestry check reads every union and child link of the tree. A child link or
 *   partner change between two other people could otherwise connect the survivor and
 *   the retired person after the check ran, and the merge would close a loop.
 * - A union or event update holds its own row before it locks a person for its
 *   foreign-key check, the opposite order to the merge.
 * - FT-008: a profile-photo assignment locks the media row and then updates the
 *   person, while a merge locks people and then touches the chosen photo's media row
 *   through the survivor's foreign key. Assignments take it shared first.
 *
 * Citation writers don't take it: they lock their target `FOR KEY SHARE` before
 * inserting (FT-002), which waits on the merge's lock of the person and then finds
 * a retired person gone (404). Deletes and relation-only updates add no references.
 *
 * The key is derived from the tree ID; two trees sharing a key only serialize more.
 */
internal object GraphWriteLock {

    fun shared(treeId: Uuid) = acquire(treeId, "pg_advisory_xact_lock_shared")

    fun exclusive(treeId: Uuid) = acquire(treeId, "pg_advisory_xact_lock")

    private fun acquire(treeId: Uuid, function: String) {
        // The key is a Long computed here, never caller text, so it is inlined safely.
        val key = treeId.toLongs { mostSignificant, leastSignificant -> mostSignificant xor leastSignificant }
        transaction {
            exec("select $function($key)") { rs -> rs.next() }
        }
    }
}
