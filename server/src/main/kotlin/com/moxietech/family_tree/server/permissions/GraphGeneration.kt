package com.moxietech.family_tree.server.permissions

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * FT-010: a per-tree counter (`tree_graph_generation`, V7) that tells a cached grant
 * scope whether the graph it was built from is still the current one.
 *
 * Writers that can change what a branch covers: person create and delete, union
 * create, update and delete, child-link create, update and delete, and person merges.
 * Each one
 * 1. takes [com.moxietech.family_tree.server.graph.GraphWriteLock] shared as its first
 *    statement (a merge takes it exclusive), and
 * 2. calls [advance] after its graph change, just before its audit entry. The increment
 *    commits or rolls back with the change, so a committed change always comes with a
 *    new generation. The row lock is held only from there to commit, so writers don't
 *    serialize on anything but that last step.
 *
 * A scope rebuild takes `GraphWriteLock` exclusive, then this row `FOR SHARE`
 * ([lockForRebuild]), reads the graph and stores the result with the generation it saw.
 * The exclusive lock waits for in-flight graph writers and holds off new ones, so no
 * graph change can commit between the read and the stamp. It is also what keeps a
 * rebuild (which inserts cache rows that need `FOR KEY SHARE` on people) from
 * deadlocking with a person delete holding a person `FOR UPDATE` while it waits here.
 *
 * Person updates, events, places, sources, citations and media don't change any
 * branch and don't advance it.
 */
internal object GraphGeneration {

    /** Tree creation: the tree's first row (generation 0). */
    fun create(treeId: Uuid) = withJdbc { connection ->
        connection.prepareStatement("insert into tree_graph_generation (tree_id) values (?)").use { st ->
            st.setUuid(1, treeId)
            st.executeUpdate()
        }
    }

    /**
     * Graph writers, after their change: the next generation for [treeId]; holds the row
     * until commit. Every tree has its row (V7 backfill, [create]); a writer has already
     * resolved its tree, so a missing row is a broken invariant, not a client error.
     */
    fun advance(treeId: Uuid): Long = withJdbc { connection ->
        connection.prepareStatement(
            "update tree_graph_generation set generation = generation + 1 where tree_id = ? returning generation",
        ).use { st ->
            st.setUuid(1, treeId)
            st.executeQuery().use { rs ->
                check(rs.next()) { "Tree $treeId has no graph generation row" }
                rs.getLong(1)
            }
        }
    }

    /**
     * Scope rebuilds, after `GraphWriteLock` exclusive: the current generation, locked
     * `FOR SHARE` until commit, so it can't advance while the rebuild reads the graph.
     * Null when the tree doesn't exist (nothing is written, so a missing tree stays a 404).
     */
    fun lockForRebuild(treeId: Uuid): Long? = withJdbc { connection ->
        connection.prepareStatement("select generation from tree_graph_generation where tree_id = ? for share").use { st ->
            st.setUuid(1, treeId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }
}

/**
 * Runs [block] on the JDBC connection of the current Exposed transaction (opening one
 * if there is none), for the reviewed raw SQL that Exposed's DSL doesn't model:
 * recursive CTEs, upserts and row-lock modes. Statements join the caller's
 * transaction, unlike a connection taken from the pool.
 */
internal fun <T> withJdbc(block: (Connection) -> T): T = transaction {
    block(connection.connection as Connection)
}

internal fun PreparedStatement.setUuid(index: Int, value: Uuid) = setObject(index, value.toJavaUuid())
