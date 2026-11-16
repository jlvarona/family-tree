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

object MembershipTable : Table("membership") {
    val treeId = uuid("tree_id") references TreeTable.id
    val accountId = uuid("account_id") references AccountTable.id
    val personId = (uuid("person_id") references PersonTable.id).nullable()
    val status = text("status")
    override val primaryKey = PrimaryKey(treeId, accountId)
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
