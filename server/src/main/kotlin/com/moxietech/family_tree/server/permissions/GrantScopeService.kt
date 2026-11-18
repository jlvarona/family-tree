package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.domain.Grant
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.permissions.EditAuthority
import com.moxietech.family_tree.permissions.GrantRules
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.graph.GraphWriteLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.uuid.Uuid

/**
 * FT-010: the resolved person sets of grants. It performs no caller authorization
 * itself; FT-012 writes call [scopesForWriter] through [WriteAccess], and FT-013 reads
 * call [scopedGrantsInSnapshot] through [ReadAccess].
 *
 * Three entry points, kept apart on purpose (the third, [scopesForWriter], is described
 * on the function):
 *
 * - **Snapshot reads** ([scopesInSnapshot], [scopeInSnapshot]) run inside the caller's
 *   transaction and never write or lock. Call them in the read-only `REPEATABLE READ`
 *   transaction that also reads the data being authorized, so grant definitions,
 *   people, relationships and the result all come from one snapshot. A cached scope
 *   is used when its stamp (grant version and graph generation, [GraphGeneration])
 *   still matches in that snapshot; otherwise the scope is computed from the same
 *   snapshot ([GrantScopeRepository.resolve]) and the stale cache is left as it is.
 * - **Refresh** ([refreshScope]) is the write-capable path that stores a current
 *   scope, in its own READ COMMITTED transaction:
 *   1. [GraphWriteLock] exclusive for the tree, like a merge: waits for in-flight graph
 *      writers (they hold it shared) and holds off new ones until this commits;
 *   2. the tree's generation row `FOR SHARE` (404 when the tree doesn't exist);
 *   3. the grant `FOR NO KEY UPDATE` (404 if it's gone; serializes rebuilds of the
 *      grant with each other and with grant updates and deletes);
 *   4. if another refresh already stored a current scope meanwhile, that one is used;
 *   5. otherwise the CTE result replaces the cache, stamped with both values.
 *   Don't call it from inside a graph writer's transaction: it would hold
 *   `GraphWriteLock` shared and ask for it exclusive.
 *
 * Every committed write that can change a branch or a grant definition changes the
 * generation or the grant version in its own transaction, so no read that starts after
 * that commit is served the older scope.
 */
object GrantScopeService {

    /**
     * Every grant [accountId] holds in [treeId] with the people it covers, from the
     * caller's current snapshot. Grants union; the role per person is FT-012's concern.
     */
    fun scopesInSnapshot(treeId: Uuid, accountId: Uuid): Map<GrantId, Set<Uuid>> {
        checkSnapshotIsolation()
        return GrantRepository.listForAccount(treeId, accountId).associate { grant ->
            grant.id to (GrantScopeRepository.cached(treeId, Uuid.parse(grant.id.value)) ?: GrantScopeRepository.resolve(treeId, grant))
        }
    }

    /**
     * FT-013: every grant [accountId] holds in [treeId] with its stored role and branch and
     * the people it covers, from the caller's current snapshot (the read-only REPEATABLE
     * READ transaction that also reads the data being projected). A grant without an
     * effective role ([GrantRules.effectiveRole]: a stored ADMIN on a limited branch)
     * carries nothing, so its scope isn't resolved and it isn't returned.
     */
    fun scopedGrantsInSnapshot(treeId: Uuid, accountId: Uuid): List<EditAuthority.ScopedGrant> {
        checkSnapshotIsolation()
        return GrantRepository.listForAccount(treeId, accountId)
            .filter { GrantRules.effectiveRole(it.role, it.branch) != null }
            .map { grant ->
                val scope = GrantScopeRepository.cached(treeId, Uuid.parse(grant.id.value)) ?: GrantScopeRepository.resolve(treeId, grant)
                EditAuthority.ScopedGrant(grant.role, grant.branch, scope.mapTo(mutableSetOf()) { PersonId(it.toString()) })
            }
    }

    /**
     * The people [grantId] covers, from the caller's current snapshot. The grant is read
     * in [treeId] only: another tree's grant is the generic 404.
     */
    fun scopeInSnapshot(treeId: Uuid, grantId: Uuid): Set<Uuid> {
        checkSnapshotIsolation()
        val grant = GrantRepository.findById(treeId, grantId) ?: throw NotFoundException("No grant with id $grantId")
        return GrantScopeRepository.cached(treeId, grantId) ?: GrantScopeRepository.resolve(treeId, grant)
    }

    /**
     * FT-012, the writer entry point: the people each of [grants] (all in [treeId])
     * covers, for a write authorizing itself in its own transaction ([WriteAccess]).
     * Requires a READ COMMITTED transaction that holds [GraphWriteLock] exclusive for
     * [treeId] (both checked) and the grants locked: then no graph writer, merge or grant
     * change of the tree can commit until this transaction does, and each statement sees
     * everything committed before the lock was granted, so the result stays the current
     * scope up to the write's commit, including after the write's own graph change. Uses
     * a current cache like the snapshot reads, otherwise resolves; never writes the cache.
     */
    fun scopesForWriter(treeId: Uuid, grants: List<Grant>): Map<GrantId, Set<Uuid>> {
        checkWriterLocks(treeId)
        return grants.associate { grant ->
            require(grant.treeId.value == treeId.toString()) { "Grant ${grant.id.value} is not in tree $treeId" }
            grant.id to (GrantScopeRepository.cached(treeId, Uuid.parse(grant.id.value)) ?: GrantScopeRepository.resolve(treeId, grant))
        }
    }

    internal fun checkWriterLocks(treeId: Uuid) {
        val isolation = TransactionManager.currentOrNull()?.transactionIsolation
        check(isolation == Connection.TRANSACTION_READ_COMMITTED) {
            "Writer scopes must be read in the write's READ COMMITTED transaction"
        }
        check(GraphWriteLock.isHeldExclusive(treeId)) { "Writer scopes need GraphWriteLock exclusive for tree $treeId" }
    }

    /** Brings the cached scope of [grantId] up to date and returns it; 404 when the grant isn't in [treeId]. */
    suspend fun refreshScope(treeId: Uuid, grantId: Uuid): Set<Uuid> = withContext(DatabaseDispatcher) {
        GrantScopeRepository.cached(treeId, grantId) ?: rebuild(treeId, grantId)
    }

    internal fun checkSnapshotIsolation() {
        val isolation = TransactionManager.currentOrNull()?.transactionIsolation
        check(isolation == Connection.TRANSACTION_REPEATABLE_READ || isolation == Connection.TRANSACTION_SERIALIZABLE) {
            "Grant scopes must be read in a repeatable read or serializable transaction"
        }
    }

    private fun rebuild(treeId: Uuid, grantId: Uuid): Set<Uuid> =
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            GraphWriteLock.exclusive(treeId)
            val generation = GraphGeneration.lockForRebuild(treeId) ?: throw NotFoundException("No grant with id $grantId")
            val grant = GrantRepository.lockForScopeRebuild(treeId, grantId)
                ?: throw NotFoundException("No grant with id $grantId")
            GrantScopeRepository.cached(treeId, grantId) ?: GrantScopeRepository.replace(treeId, grant, generation)
        }
}
