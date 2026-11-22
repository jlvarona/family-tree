package com.moxietech.family_tree.server.collaboration

import com.moxietech.family_tree.proposals.ProposalField
import com.moxietech.family_tree.proposals.ProposalOperation
import com.moxietech.family_tree.proposals.ProposalReason
import com.moxietech.family_tree.proposals.ProposalStatus
import com.moxietech.family_tree.server.permissions.setUuid
import com.moxietech.family_tree.server.permissions.withJdbc
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** FT-014: one stored proposal (V10 `change_proposal`). Server-only: callers see [ChangeProposalService]'s shaped DTO. */
internal data class ChangeProposalRecord(
    val id: Uuid,
    val treeId: Uuid,
    val proposer: Uuid,
    val operation: ProposalOperation,
    val targetId: String?,
    val baseVersion: Long?,
    val payload: JsonObject,
    val subjects: List<Uuid>,
    val reason: ProposalReason,
    val status: ProposalStatus,
    val reviewer: Uuid?,
    val approvedFields: List<ProposalField>?,
    val createdAt: Instant,
    val decidedAt: Instant?,
    val version: Long,
)

/** The audit snapshot of a proposal: everything stored, for the owner's activity feed (FT-016). */
@Serializable
internal data class ChangeProposalAuditDto(
    val id: String,
    val operation: String,
    val targetId: String?,
    val baseVersion: Long?,
    val payload: JsonObject,
    val subjectIds: List<String>,
    val reason: String,
    val status: String,
    val proposerAccountId: String,
    val reviewerAccountId: String?,
    val approvedFields: List<String>?,
    val version: Long,
)

internal fun ChangeProposalRecord.toAuditDto() = ChangeProposalAuditDto(
    id = id.toString(),
    operation = operation.name,
    targetId = targetId,
    baseVersion = baseVersion,
    payload = payload,
    subjectIds = subjects.map { it.toString() },
    reason = reason.name,
    status = status.name,
    proposerAccountId = proposer.toString(),
    reviewerAccountId = reviewer?.toString(),
    approvedFields = approvedFields?.map { it.name },
    version = version,
)

/**
 * FT-014 persistence. Every statement filters by `tree_id`; callers run inside their own
 * transaction (this never opens one).
 */
internal object ChangeProposalRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val fieldList = ListSerializer(String.serializer())

    private const val COLUMNS =
        "id, tree_id, proposer_account_id, operation, target_id, base_version, payload, subject_ids, reason, status, " +
            "reviewer_account_id, approved_fields, created_at, decided_at, version"

    fun insert(
        treeId: Uuid,
        proposer: Uuid,
        operation: ProposalOperation,
        targetId: String?,
        baseVersion: Long?,
        payload: JsonObject,
        subjects: List<Uuid>,
        reason: ProposalReason,
    ): ChangeProposalRecord = withJdbc { c ->
        val id = Uuid.random()
        c.prepareStatement(
            """
            insert into change_proposal (id, tree_id, proposer_account_id, operation, target_id, base_version, payload, subject_ids, reason)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning $COLUMNS
            """.trimIndent(),
        ).use { st ->
            st.setUuid(1, id)
            st.setUuid(2, treeId)
            st.setUuid(3, proposer)
            st.setString(4, operation.name)
            st.setString(5, targetId)
            if (baseVersion == null) st.setNull(6, java.sql.Types.BIGINT) else st.setLong(6, baseVersion)
            st.setString(7, payload.toString())
            st.setArray(8, c.createArrayOf("uuid", subjects.map { it.toJavaUuid() }.toTypedArray()))
            st.setString(9, reason.name)
            st.executeQuery().use { rs -> rs.next(); rs.toRecord() }
        }
    }

    fun find(treeId: Uuid, id: Uuid): ChangeProposalRecord? = one("select $COLUMNS from change_proposal where tree_id = ? and id = ?", treeId, id)

    /** The proposal locked `FOR UPDATE` (a concurrent review or withdrawal waits, then sees the decision). */
    fun lock(treeId: Uuid, id: Uuid): ChangeProposalRecord? =
        one("select $COLUMNS from change_proposal where tree_id = ? and id = ? for update", treeId, id)

    /** Every proposal of the tree, newest first; the service filters what the caller may see. */
    fun listByTree(treeId: Uuid): List<ChangeProposalRecord> = withJdbc { c ->
        c.prepareStatement("select $COLUMNS from change_proposal where tree_id = ? order by created_at desc, id").use { st ->
            st.setUuid(1, treeId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRecord()) } }
        }
    }

    /**
     * Moves a PENDING proposal at [expectedVersion] to [status]; null when it is no longer
     * pending at that version (the caller holds its lock, so that means a stale request).
     */
    fun decide(
        treeId: Uuid,
        id: Uuid,
        expectedVersion: Long,
        status: ProposalStatus,
        reviewer: Uuid?,
        approvedFields: List<ProposalField>?,
    ): ChangeProposalRecord? = withJdbc { c ->
        c.prepareStatement(
            """
            update change_proposal
               set status = ?, reviewer_account_id = ?, approved_fields = ?, decided_at = now(), version = version + 1
             where tree_id = ? and id = ? and version = ? and status = 'PENDING'
            returning $COLUMNS
            """.trimIndent(),
        ).use { st ->
            st.setString(1, status.name)
            if (reviewer == null) st.setNull(2, java.sql.Types.OTHER) else st.setUuid(2, reviewer)
            st.setString(3, approvedFields?.let { fields -> json.encodeToString(fieldList, fields.map { it.name }) })
            st.setUuid(4, treeId)
            st.setUuid(5, id)
            st.setLong(6, expectedVersion)
            st.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }
    }

    private fun one(sql: String, treeId: Uuid, id: Uuid): ChangeProposalRecord? = withJdbc { c ->
        c.prepareStatement(sql).use { st ->
            st.setUuid(1, treeId)
            st.setUuid(2, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }
    }

    private fun ResultSet.toRecord(): ChangeProposalRecord {
        @Suppress("UNCHECKED_CAST")
        val subjects = (getArray("subject_ids").array as Array<java.util.UUID>).map { it.toKotlinUuid() }
        return ChangeProposalRecord(
            id = Uuid.parse(getString("id")),
            treeId = Uuid.parse(getString("tree_id")),
            proposer = Uuid.parse(getString("proposer_account_id")),
            operation = ProposalOperation.valueOf(getString("operation")),
            targetId = getString("target_id"),
            baseVersion = getLong("base_version").takeUnless { wasNull() },
            payload = json.parseToJsonElement(getString("payload")) as JsonObject,
            subjects = subjects,
            reason = ProposalReason.valueOf(getString("reason")),
            status = ProposalStatus.valueOf(getString("status")),
            reviewer = getString("reviewer_account_id")?.let(Uuid::parse),
            approvedFields = getString("approved_fields")?.let { raw ->
                json.decodeFromString(fieldList, raw).map(ProposalField::valueOf)
            },
            createdAt = getTimestamp("created_at").toInstant(),
            decidedAt = getTimestamp("decided_at")?.let(Timestamp::toInstant),
            version = getLong("version"),
        )
    }
}
