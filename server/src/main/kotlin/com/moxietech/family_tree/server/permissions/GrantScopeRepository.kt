package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.Grant
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.uuid.Uuid

/**
 * FT-010: resolves a grant's branch against the stored graph with a recursive CTE,
 * and keeps the derived result in `grant_scope` / `grant_scope_state` (V7).
 *
 * Resolution follows `BranchResolution` in :core (the integration tests compare both
 * on the same graph): the anchor; its ancestors (`up`) or descendants (`down`) up to
 * the depth; for a side branch, the ancestors up to the depth and then every
 * descendant of them and of the anchor (`side`, no depth); then, when the branch
 * includes partners, the direct partners of those people and nobody beyond them. The
 * partner step runs once at the end, so no leg ever walks up from a partner. Whole
 * tree is every person of the tree.
 *
 * Tenant isolation (FT-065): the anchor, every join of every recursive step, the
 * partner step and the final select are each constrained to the grant's tree, so a
 * cross-tree link could never be followed even if one existed. A grant can only be
 * resolved for its own tree.
 *
 * Bounded work on untrusted depths and cyclic data: every leg uses `union`, which
 * drops rows already produced. An unlimited leg produces `(person, 0)`, so each person
 * once and the recursion ends when nobody new is reached, with no path enumeration
 * through pedigree collapse or loops. A depth of at least the tree's people count
 * minus one is the same set as unlimited (every reachable person has a simple path no
 * longer than that), so it runs as unlimited. Only a smaller depth N counts
 * generations: at most N recursion steps, each producing at most one row per person.
 *
 * SQL text is built only from the fixed fragments below (direction, partners
 * included); every value is a bound parameter.
 */
internal object GrantScopeRepository {

    /**
     * The grant's resolved person set, computed from the graph now without touching
     * the cache or taking locks. Usable inside any transaction, including a read-only
     * `REPEATABLE READ` snapshot. [grant] must belong to [treeId].
     */
    fun resolve(treeId: Uuid, grant: Grant): Set<Uuid> = withJdbc { connection ->
        val query = scopeQuery(treeId, grant)
        connection.prepare(query.sql, query.params).use { st ->
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(Uuid.parse(rs.getString(1))) } }
        }
    }

    /**
     * The cached scope of [grantId] in [treeId], or null when it is missing or stale:
     * built from another grant version or another graph generation than the current
     * ones. One statement, so the stamp, the current values and the rows come from one
     * snapshot. Read-only.
     */
    fun cached(treeId: Uuid, grantId: Uuid): Set<Uuid>? = withJdbc { connection ->
        connection.prepare(
            """
            select s.person_id
            from grant_scope_state st
            join tree_grant g on g.id = st.grant_id and g.tree_id = st.tree_id
            join tree_graph_generation gen on gen.tree_id = st.tree_id
            left join grant_scope s on s.grant_id = st.grant_id and s.tree_id = st.tree_id
            where st.grant_id = ? and st.tree_id = ?
              and st.grant_version = g.version and st.graph_generation = gen.generation
            """.trimIndent(),
            listOf(grantId, treeId),
        ).use { st ->
            st.executeQuery().use { rs ->
                var fresh = false
                val people = buildSet {
                    while (rs.next()) {
                        fresh = true
                        rs.getString(1)?.let { add(Uuid.parse(it)) }
                    }
                }
                if (fresh) people else null
            }
        }
    }

    /**
     * Replaces the cached scope of [grant] with a fresh resolution, stamped with
     * [generation] and the grant's version. The caller holds the generation row
     * `FOR SHARE` and the grant `FOR NO KEY UPDATE` ([GrantScopeService.refreshScope]).
     */
    fun replace(treeId: Uuid, grant: Grant, generation: Long): Set<Uuid> {
        val grantId = Uuid.parse(grant.id.value)
        withJdbc { connection ->
            connection.prepare("delete from grant_scope where grant_id = ? and tree_id = ?", listOf(grantId, treeId)).use { it.executeUpdate() }
            val query = scopeQuery(treeId, grant)
            connection.prepare(
                "insert into grant_scope (grant_id, tree_id, person_id) select ?, ?, scope.id from (${query.sql}) as scope",
                listOf<Any>(grantId, treeId) + query.params,
            ).use { it.executeUpdate() }
            connection.prepare(
                """
                insert into grant_scope_state (grant_id, tree_id, grant_version, graph_generation, built_at)
                values (?, ?, ?, ?, now())
                on conflict (grant_id) do update
                set grant_version = excluded.grant_version, graph_generation = excluded.graph_generation, built_at = now()
                """.trimIndent(),
                listOf(grantId, treeId, grant.version, generation),
            ).use { it.executeUpdate() }
        }
        return cached(treeId, grantId) ?: error("Scope of grant $grantId was just rebuilt but is not current")
    }

    private class Query(val sql: String, val params: List<Any>)

    /** Appends SQL fragments with their parameters, in text order. */
    private class Builder {
        val sql = StringBuilder()
        val params = mutableListOf<Any>()

        fun add(fragment: String, vararg values: Any) {
            sql.append(fragment.trimIndent()).append('\n')
            params.addAll(values)
        }
    }

    private fun scopeQuery(treeId: Uuid, grant: Grant): Query {
        require(grant.treeId.value == treeId.toString()) { "Grant ${grant.id.value} is not in tree $treeId" }
        val branch = grant.branch
        val q = Builder()
        q.add("with recursive anchor(id) as (select p.id from person p where p.id = ? and p.tree_id = ?),", Uuid.parse(branch.anchorPersonId.value), treeId)

        if (branch.direction == BranchDirection.WHOLE_TREE) {
            q.add("blood(id) as (select p.id from person p where p.tree_id = ? and exists (select 1 from anchor))", treeId)
        } else {
            // Unlimited (null) is Int.MAX_VALUE here, which is always ≥ people − 1.
            val depth = branch.depth ?: Int.MAX_VALUE
            q.add(
                """
                limits(unlimited, depth) as (
                    select ? >= count(*) - 1, ? from person where tree_id = ?
                ),
                """,
                depth, depth, treeId,
            )
            when (branch.direction) {
                BranchDirection.ANCESTORS -> {
                    q.add(UP_LEG, treeId)
                    q.add("blood(id) as (select distinct id from up)")
                }
                BranchDirection.DESCENDANTS -> {
                    q.add(DOWN_LEG, treeId)
                    q.add("blood(id) as (select distinct id from down)")
                }
                BranchDirection.SIDE -> {
                    q.add(UP_LEG, treeId)
                    q.add(SIDE_LEG, treeId)
                    q.add("blood(id) as (select id from side)")
                }
                BranchDirection.WHOLE_TREE -> error("handled above")
            }
        }

        if (branch.includePartners && branch.direction != BranchDirection.WHOLE_TREE) {
            q.add(
                """
                , scope(id) as (
                    select id from blood
                    union
                    select partner.id
                    from blood b
                    join union_record ur on ur.tree_id = ? and (ur.partner1_id = b.id or ur.partner2_id = b.id)
                    cross join lateral (values (ur.partner1_id), (ur.partner2_id)) as partner(id)
                    where partner.id is not null
                )
                """,
                treeId,
            )
        } else {
            q.add(", scope(id) as (select id from blood)")
        }
        q.add("select distinct p.id from person p join scope s on s.id = p.id where p.tree_id = ?", treeId)
        return Query(q.sql.toString(), q.params)
    }

    /** Ancestors of the anchor; generations are counted only when `limits` says the depth matters. */
    private const val UP_LEG = """
        up(id, generation) as (
            select id, 0 from anchor
            union
            select parent.id, case when l.unlimited then 0 else u.generation + 1 end
            from up u
            cross join limits l
            join union_child uc on uc.child_id = u.id and uc.tree_id = ?
            join union_record ur on ur.id = uc.union_id and ur.tree_id = uc.tree_id
            cross join lateral (values (ur.partner1_id), (ur.partner2_id)) as parent(id)
            where parent.id is not null and (l.unlimited or u.generation < l.depth)
        ),
    """

    /** Descendants of the anchor; generations are counted only when `limits` says the depth matters. */
    private const val DOWN_LEG = """
        down(id, generation) as (
            select id, 0 from anchor
            union
            select uc.child_id, case when l.unlimited then 0 else d.generation + 1 end
            from down d
            cross join limits l
            join union_record ur on ur.tree_id = ? and (ur.partner1_id = d.id or ur.partner2_id = d.id)
            join union_child uc on uc.union_id = ur.id and uc.tree_id = ur.tree_id
            where l.unlimited or d.generation < l.depth
        ),
    """

    /** Every descendant of the side's roots (the anchor and its ancestors in `up`), no depth. */
    private const val SIDE_LEG = """
        side(id) as (
            select id from up
            union
            select uc.child_id
            from side s
            join union_record ur on ur.tree_id = ? and (ur.partner1_id = s.id or ur.partner2_id = s.id)
            join union_child uc on uc.union_id = ur.id and uc.tree_id = ur.tree_id
        ),
    """

    private fun Connection.prepare(sql: String, params: List<Any>): PreparedStatement =
        prepareStatement(sql).also { st ->
            params.forEachIndexed { i, value ->
                when (value) {
                    is Uuid -> st.setUuid(i + 1, value)
                    is Int -> st.setInt(i + 1, value)
                    is Long -> st.setLong(i + 1, value)
                    else -> error("Unsupported parameter type ${value::class}")
                }
            }
        }
}
