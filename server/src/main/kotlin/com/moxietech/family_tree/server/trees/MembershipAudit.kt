package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.domain.Membership
import kotlinx.serialization.Serializable

/**
 * Audit shape of a membership row, entity `Membership`, ID `tree:account` (FT-007; the
 * version since V9, FT-011). It carries no profile data: the account's email and name
 * stay out of the audit log.
 */
@Serializable
internal data class MembershipAuditSnapshot(
    val treeId: String,
    val accountId: String,
    val personId: String?,
    val status: String,
    val version: Long,
)

internal fun Membership.auditSnapshot() =
    MembershipAuditSnapshot(treeId.value, accountId.value, personId?.value, status.name, version)

internal fun Membership.auditEntityId() = "${treeId.value}:${accountId.value}"
