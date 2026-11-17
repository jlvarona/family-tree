package com.moxietech.family_tree.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Exposed table definitions mirroring the Flyway migrations in db/migration exactly.
 * Schema changes only through a new Flyway migration (AGENTS.md) — these
 * objects describe the existing schema to Exposed, they never create it.
 */

object AccountTable : Table("account") {
    val id = uuid("id")
    /** Verified OIDC `iss` + `sub` (V3); null only on legacy accounts awaiting resolution. */
    val oidcIssuer = text("oidc_issuer").nullable()
    val oidcSubject = text("oidc_subject").nullable()
    /** Profile data from the latest login; not unique and never used to find an account. */
    val email = text("email")
    val displayName = text("display_name")
    val locale = text("locale")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object TreeTable : Table("tree") {
    val id = uuid("id")
    val name = text("name")
    val ownerAccountId = uuid("owner_account_id") references AccountTable.id
    val version = long("version")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PersonTable : Table("person") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val givenNames = text("given_names")
    val surnamePaternal = text("surname_paternal").nullable()
    val surnameMaternal = text("surname_maternal").nullable()
    val sex = text("sex")
    val isLiving = bool("is_living")
    val profileMediaId = uuid("profile_media_id").nullable()
    val bio = text("bio").nullable()
    val createdByAccountId = (uuid("created_by_account_id") references AccountTable.id).nullable()
    val version = long("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object PlaceTable : Table("place") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val name = text("name")
    val hierarchy = text("hierarchy").nullable()
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()
    /** Optimistic-concurrency version (V4); existing rows start at 1. */
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

object UnionTable : Table("union_record") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val partner1Id = uuid("partner1_id") references PersonTable.id
    val partner2Id = (uuid("partner2_id") references PersonTable.id).nullable()
    val type = text("type")
    val startDateText = text("start_date_text").nullable()
    val startDateEarliest = gedcomDate("start_date_earliest").nullable()
    val startDateLatest = gedcomDate("start_date_latest").nullable()
    val endDateText = text("end_date_text").nullable()
    val endDateEarliest = gedcomDate("end_date_earliest").nullable()
    val endDateLatest = gedcomDate("end_date_latest").nullable()
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

object UnionChildTable : Table("union_child") {
    val treeId = uuid("tree_id") references TreeTable.id
    val unionId = uuid("union_id") references UnionTable.id
    val childId = uuid("child_id") references PersonTable.id
    val relation = text("relation")
    /** Optimistic-concurrency version (V4); existing rows start at 1. */
    val version = long("version")
    override val primaryKey = PrimaryKey(unionId, childId)
}

object EventTable : Table("event") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val personId = (uuid("person_id") references PersonTable.id).nullable()
    val unionId = (uuid("union_id") references UnionTable.id).nullable()
    val type = text("type")
    val dateText = text("date_text").nullable()
    val dateEarliest = gedcomDate("date_earliest").nullable()
    val dateLatest = gedcomDate("date_latest").nullable()
    val placeId = (uuid("place_id") references PlaceTable.id).nullable()
    val notes = text("notes").nullable()
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

object SourceTable : Table("source") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val title = text("title")
    val type = text("type")
    val author = text("author").nullable()
    val publisher = text("publisher").nullable()
    val repository = text("repository").nullable()
    val url = text("url").nullable()
    val dateText = text("date_text").nullable()
    val dateEarliest = gedcomDate("date_earliest").nullable()
    val dateLatest = gedcomDate("date_latest").nullable()
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

object CitationTable : Table("citation") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val sourceId = uuid("source_id") references SourceTable.id
    val targetType = text("target_type")
    val targetId = uuid("target_id")
    val page = text("page").nullable()
    val confidence = short("confidence")
    val note = text("note").nullable()
    val mediaId = uuid("media_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** FT-008 (V6): one uploaded image; bytes are in the object store under server-generated keys. */
object MediaTable : Table("media") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val purpose = text("purpose")
    val status = text("status")
    val contentType = text("content_type")
    val uploadByteSize = long("upload_byte_size")
    val uploadKey = text("upload_key")
    val storageKey = text("storage_key").nullable()
    val byteSize = long("byte_size").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val sha256 = text("sha256").nullable()
    val uploadedByAccountId = uuid("uploaded_by_account_id") references AccountTable.id
    val createdAt = timestamp("created_at")
    val finalizedAt = timestamp("finalized_at").nullable()
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

object MembershipTable : Table("membership") {
    val treeId = uuid("tree_id") references TreeTable.id
    val accountId = uuid("account_id") references AccountTable.id
    val personId = (uuid("person_id") references PersonTable.id).nullable()
    val status = text("status")
    /** FT-011 (V9): advances on every change; a person is linked to at most one membership per tree. */
    val version = long("version")
    override val primaryKey = PrimaryKey(treeId, accountId)
}

/**
 * FT-011 (V9): a pending invitation. [tokenDigest] is the SHA-256 of the secret token,
 * which is never stored; the row is deleted when the invitation is accepted or revoked.
 * [email] is a delivery address, never an identity.
 */
object InvitationTable : Table("invitation") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val tokenDigest = binary("token_digest")
    val email = text("email").nullable()
    val personId = uuid("person_id").nullable()
    val expiresAt = timestamp("expires_at")
    val createdByAccountId = uuid("created_by_account_id") references AccountTable.id
    val createdAt = timestamp("created_at")
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

/** FT-011 (V9): a grant proposed by an invitation, created as a `tree_grant` on acceptance. */
object InvitationGrantTable : Table("invitation_grant") {
    val id = uuid("id")
    val invitationId = uuid("invitation_id")
    val treeId = uuid("tree_id") references TreeTable.id
    val ordinal = integer("ordinal")
    val role = text("role")
    val anchorPersonId = uuid("anchor_person_id")
    val direction = text("direction")
    val depth = integer("depth").nullable()
    val includePartners = bool("include_partners")
    override val primaryKey = PrimaryKey(id)
}

/**
 * FT-010 (V7): a branch grant held by one membership; real data, its anchor a real
 * reference. The derived tables (`grant_scope`, `grant_scope_state`,
 * `tree_graph_generation`) are read and written only by reviewed raw SQL in the
 * `permissions` package, so they have no Exposed declaration.
 */
object TreeGrantTable : Table("tree_grant") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val accountId = uuid("account_id")
    val role = text("role")
    val anchorPersonId = uuid("anchor_person_id")
    val direction = text("direction")
    val depth = integer("depth").nullable()
    val includePartners = bool("include_partners")
    val version = long("version")
    val createdByAccountId = uuid("created_by_account_id") references AccountTable.id
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AuditLogTable : Table("audit_log") {
    val id = uuid("id")
    val treeId = uuid("tree_id") references TreeTable.id
    val actorAccountId = uuid("actor_account_id") references AccountTable.id
    val entityType = text("entity_type")
    val entityId = text("entity_id")
    val action = text("action")
    val beforeJson = text("before_json").nullable()
    val afterJson = text("after_json").nullable()
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(id)
}
