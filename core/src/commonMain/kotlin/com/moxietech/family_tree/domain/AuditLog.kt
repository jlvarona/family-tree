package com.moxietech.family_tree.domain

import kotlin.time.Instant

/**
 * One immutable audit entry. Written by the service layer in the same
 * transaction as the mutation it describes — never added after the fact,
 * never containing secrets. `before`/`after` are JSON snapshots of the
 * affected entity, used for the activity feed and future revert.
 */
data class AuditLogEntry(
    val id: AuditLogId,
    val treeId: TreeId,
    val actorAccountId: AccountId,
    val entityType: String,
    val entityId: String,
    val action: AuditAction,
    val beforeJson: String?,
    val afterJson: String?,
    val at: Instant,
)

enum class AuditAction {
    CREATE,
    UPDATE,
    DELETE,
}

data class AuditLogId(val value: String)
