package com.moxietech.family_tree.server.collaboration

import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.server.db.AuditLogTable
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Appends one immutable audit entry. Callers invoke this from inside the
 * same Exposed `transaction { }` block as the business mutation it
 * describes (AGENTS.md "Every write is audited") — this function never
 * opens its own transaction, so it always rides along with the caller's.
 */
object AuditLogRepository {

    fun append(
        treeId: Uuid,
        actorAccountId: Uuid,
        entityType: String,
        entityId: String,
        action: AuditAction,
        beforeJson: String?,
        afterJson: String?,
    ) {
        AuditLogTable.insert {
            it[AuditLogTable.id] = Uuid.random()
            it[AuditLogTable.treeId] = treeId
            it[AuditLogTable.actorAccountId] = actorAccountId
            it[AuditLogTable.entityType] = entityType
            it[AuditLogTable.entityId] = entityId
            it[AuditLogTable.action] = action.name
            it[AuditLogTable.beforeJson] = beforeJson
            it[AuditLogTable.afterJson] = afterJson
            it[AuditLogTable.at] = Instant.now()
        }
    }
}
