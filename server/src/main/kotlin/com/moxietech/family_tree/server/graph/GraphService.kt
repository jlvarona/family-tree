package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.trees.TreeService
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.uuid.Uuid

object GraphService {

    /**
     * The whole tree in one response (FT-004), for any active member. Three
     * tree-scoped queries: people, unions, and all child links grouped by union,
     * so the cost doesn't grow with one query per union. People and unions go
     * through [ViewerPolicy] exactly as on the separate list routes.
     *
     * The queries share one read-only REPEATABLE READ snapshot, so a person or
     * union committed between them can't appear half-way: no link to a person
     * missing from `people`, and no union dates shown because a newly added
     * living partner was absent from the visibility set.
     */
    suspend fun wholeTree(treeId: TreeId, callerAccountId: AccountId): TreeGraphDto {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        val policy = ViewerPolicy(tree, callerAccountId)
        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                val treeUuid = Uuid.parse(treeId.value)
                val people = PersonRepository.listByTree(treeUuid)
                val hidden = policy.hiddenIds(people)
                val children = UnionRepository.childrenByUnion(treeUuid)
                TreeGraphDto(
                    treeId = tree.id.value,
                    people = people.map(policy::project),
                    unions = UnionRepository.listByTree(treeUuid).map { union ->
                        policy.project(union, children[union.id].orEmpty(), hidden)
                    },
                )
            }
        }
    }
}
