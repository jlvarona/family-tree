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
 * a retired person gone (404).
 *
 * FT-010 adds two uses:
 * - Grant creates and updates take it shared (the anchor is a person reference), so a
 *   merge sees every grant anchored at the retired person and no new one appears.
 * - A grant-scope rebuild (`GrantScopeService`) takes it exclusive, so it reads the
 *   graph with no graph writer in flight. For that, every writer that advances the
 *   tree's graph generation takes it shared too, including person create and delete,
 *   union delete and child-link update and delete, which add no references. Shared
 *   holders still don't block each other.
 *
 * FT-012: every person, union, child-link, event and citation write by a member other
 * than the tree owner takes it **exclusive** first (`WriteAccess.begin`), then decides
 * what the caller may change from the branches in the same transaction. Every writer
 * that can change a branch, an account link or a grant definition holds it shared (or
 * exclusive), so none of them commits between that decision and the write's commit.
 * The owner keeps the shared (or no) lock each write took before, since the owner's
 * authority doesn't depend on branches.
 *
 * The key is derived from the tree ID; two trees sharing a key only serialize more.
 */
internal object GraphWriteLock {

    fun shared(treeId: Uuid) = acquire(treeId, "pg_advisory_xact_lock_shared")

    fun exclusive(treeId: Uuid) = acquire(treeId, "pg_advisory_xact_lock")

    /**
     * FT-012: whether the current transaction holds the exclusive lock for [treeId]
     * (`pg_locks` shows a bigint advisory key as its high half in `classid`, its low half
     * in `objid`, and `objsubid` 1). Used to check a precondition, never to decide access.
     */
    fun isHeldExclusive(treeId: Uuid): Boolean {
        val key = key(treeId)
        val high = key ushr 32
        val low = key and 0xFFFFFFFFL
        return transaction {
            exec(
                """
                select exists (
                    select 1 from pg_locks
                    where locktype = 'advisory' and pid = pg_backend_pid() and mode = 'ExclusiveLock' and granted
                      and objsubid = 1 and classid::bigint = $high and objid::bigint = $low
                )
                """.trimIndent(),
            ) { rs -> rs.next() && rs.getBoolean(1) } ?: false
        }
    }

    private fun acquire(treeId: Uuid, function: String) {
        // The key is a Long computed here, never caller text, so it is inlined safely.
        val key = key(treeId)
        transaction {
            exec("select $function($key)") { rs -> rs.next() }
        }
    }

    private fun key(treeId: Uuid): Long = treeId.toLongs { mostSignificant, leastSignificant -> mostSignificant xor leastSignificant }
}
