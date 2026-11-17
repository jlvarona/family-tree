package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.MediaId
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.db.PersonTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/** Whether [PersonRepository.update] also changes the profile photo (FT-007 merge, FT-008). */
sealed interface ProfileMediaUpdate {
    data object Keep : ProfileMediaUpdate

    data class Set(val mediaId: Uuid?) : ProfileMediaUpdate
}

object PersonRepository {

    /** Null when [id] doesn't exist or belongs to another tree; callers treat both as not found. */
    fun findById(treeId: Uuid, id: Uuid): Person? = transaction {
        PersonTable.selectAll().where { (PersonTable.id eq id) and (PersonTable.treeId eq treeId) }.firstOrNull()?.toPerson()
    }

    /**
     * FT-007: [findById] that also locks the row `FOR UPDATE` until the transaction
     * ends. While held, no other transaction can add a foreign-key reference to the
     * person (that needs `FOR KEY SHARE`), lock it for a citation, update or delete it.
     */
    fun lockForUpdate(treeId: Uuid, id: Uuid): Person? = transaction {
        PersonTable.selectAll().where { (PersonTable.id eq id) and (PersonTable.treeId eq treeId) }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()?.toPerson()
    }

    fun listByTree(treeId: Uuid): List<Person> = transaction {
        PersonTable.selectAll().where { PersonTable.treeId eq treeId }.map { it.toPerson() }
    }

    fun insert(
        treeId: Uuid,
        givenNames: String,
        surnamePaternal: String?,
        surnameMaternal: String?,
        sex: Sex,
        isLiving: Boolean,
        bio: String?,
        createdByAccountId: Uuid?,
    ): Person = transaction {
        val id = Uuid.random()
        val now = Instant.now()
        PersonTable.insert {
            it[PersonTable.id] = id
            it[PersonTable.treeId] = treeId
            it[PersonTable.givenNames] = givenNames
            it[PersonTable.surnamePaternal] = surnamePaternal
            it[PersonTable.surnameMaternal] = surnameMaternal
            it[PersonTable.sex] = sex.name
            it[PersonTable.isLiving] = isLiving
            it[PersonTable.bio] = bio
            it[PersonTable.createdByAccountId] = createdByAccountId
            it[PersonTable.version] = 1
            it[PersonTable.createdAt] = now
            it[PersonTable.updatedAt] = now
        }
        findById(treeId, id) ?: error("Person $id was just inserted but cannot be read back")
    }

    /**
     * FT-001: replaces the editable fields of [id] in [treeId] only while its
     * stored version is still [expectedVersion], and increments the version in
     * the same statement. Returns null, changing nothing, when no row matched:
     * a concurrent writer committed first and the caller must report a conflict.
     * Identity, tree and creator are never changed here; the profile photo only
     * when [profileMedia] says so (a merge's explicit photo choice).
     */
    fun update(
        treeId: Uuid,
        id: Uuid,
        expectedVersion: Long,
        givenNames: String,
        surnamePaternal: String?,
        surnameMaternal: String?,
        sex: Sex,
        isLiving: Boolean,
        bio: String?,
        profileMedia: ProfileMediaUpdate = ProfileMediaUpdate.Keep,
    ): Person? = transaction {
        val updated = PersonTable.update({
            (PersonTable.id eq id) and (PersonTable.treeId eq treeId) and (PersonTable.version eq expectedVersion)
        }) {
            it[PersonTable.givenNames] = givenNames
            it[PersonTable.surnamePaternal] = surnamePaternal
            it[PersonTable.surnameMaternal] = surnameMaternal
            it[PersonTable.sex] = sex.name
            it[PersonTable.isLiving] = isLiving
            it[PersonTable.bio] = bio
            if (profileMedia is ProfileMediaUpdate.Set) it[PersonTable.profileMediaId] = profileMedia.mediaId
            it[PersonTable.version] = expectedVersion + 1
            it[PersonTable.updatedAt] = Instant.now()
        }
        if (updated == 0) null else findById(treeId, id) ?: error("Person $id was just updated but cannot be read back")
    }

    /**
     * FT-008: sets (or, with null, clears) the profile photo of [id] in [treeId] while its
     * version is still [expectedVersion], advancing the version. Null when no row matched
     * (stale). The caller has checked the media under its lock ([com.moxietech.family_tree.server.media.MediaAttachments]).
     */
    fun setProfileMedia(treeId: Uuid, id: Uuid, expectedVersion: Long, mediaId: Uuid?): Person? = transaction {
        val updated = PersonTable.update({
            (PersonTable.id eq id) and (PersonTable.treeId eq treeId) and (PersonTable.version eq expectedVersion)
        }) {
            it[PersonTable.profileMediaId] = mediaId
            it[PersonTable.version] = expectedVersion + 1
            it[PersonTable.updatedAt] = Instant.now()
        }
        if (updated == 0) null else findById(treeId, id) ?: error("Person $id was just updated but cannot be read back")
    }

    /** FT-008: the person in [treeId] whose profile photo is [mediaId], if any (at most one, V6). */
    fun findByProfileMedia(treeId: Uuid, mediaId: Uuid): Person? = transaction {
        PersonTable.selectAll().where { (PersonTable.profileMediaId eq mediaId) and (PersonTable.treeId eq treeId) }
            .firstOrNull()?.toPerson()
    }

    /**
     * FT-001: deletes [id] in [treeId] only while its stored version is still
     * [expectedVersion]. Returns the number of rows deleted (0 = stale or gone).
     * A row still referenced through a foreign key makes PostgreSQL reject it.
     */
    fun delete(treeId: Uuid, id: Uuid, expectedVersion: Long): Int = transaction {
        PersonTable.deleteWhere {
            (PersonTable.id eq id) and (PersonTable.treeId eq treeId) and (PersonTable.version eq expectedVersion)
        }
    }

    /**
     * Ancestors of [personId] (parents, grandparents, ...), via a recursive
     * CTE over union_record/union_child (ARCHITECTURE.md). Raw SQL: Exposed's
     * DSL does not model recursive CTEs cleanly, and this is exactly the
     * Postgres-specific, perf-critical case raw SQL is allowed for.
     *
     * Tenant isolation (FT-065): the anchor, every join and every recursive
     * step are constrained to [treeId], not just the final select, so a
     * cross-tree link can never be traversed even if one existed. V2's
     * same-tree foreign keys make such links impossible to insert anyway.
     */
    fun ancestorsOf(personId: Uuid, treeId: Uuid): List<Person> = rawQuery(
        sql = """
            with recursive ancestors(id) as (
                -- Postgres requires the recursive term to reference the CTE
                -- exactly once; the lateral VALUES unnest lets one join yield
                -- both partner1_id and partner2_id instead of unioning two
                -- separately self-referencing subqueries.
                select parent.id
                from union_child uc
                join union_record ur on ur.id = uc.union_id and ur.tree_id = uc.tree_id
                cross join lateral (values (ur.partner1_id), (ur.partner2_id)) as parent(id)
                where uc.child_id = ? and uc.tree_id = ? and parent.id is not null
                union
                select parent.id
                from ancestors a
                join union_child uc on uc.child_id = a.id and uc.tree_id = ?
                join union_record ur on ur.id = uc.union_id and ur.tree_id = uc.tree_id
                cross join lateral (values (ur.partner1_id), (ur.partner2_id)) as parent(id)
                where parent.id is not null
            )
            select p.* from person p join ancestors a on a.id = p.id where p.tree_id = ?
        """.trimIndent(),
        params = listOf(personId, treeId, treeId, treeId),
    )

    /** Descendants of [personId] (children, grandchildren, ...), via the mirror-image recursive CTE. */
    fun descendantsOf(personId: Uuid, treeId: Uuid): List<Person> = rawQuery(
        sql = """
            with recursive descendants(id) as (
                select uc.child_id as id
                from union_record ur
                join union_child uc on uc.union_id = ur.id and uc.tree_id = ur.tree_id
                where (ur.partner1_id = ? or ur.partner2_id = ?) and ur.tree_id = ?
                union
                select uc.child_id
                from descendants d
                join union_record ur on (ur.partner1_id = d.id or ur.partner2_id = d.id) and ur.tree_id = ?
                join union_child uc on uc.union_id = ur.id and uc.tree_id = ur.tree_id
            )
            select p.* from person p join descendants d on d.id = p.id where p.tree_id = ?
        """.trimIndent(),
        params = listOf(personId, personId, treeId, treeId, treeId),
    )

    private fun rawQuery(sql: String, params: List<Uuid>): List<Person> {
        AppDatabase.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value.toJavaUuid()) }
                statement.executeQuery().use { rs -> return rs.toPersonList() }
            }
        }
    }

    private fun ResultSet.toPersonList(): List<Person> {
        val results = mutableListOf<Person>()
        while (next()) {
            results += Person(
                id = PersonId(getObject("id", UUID::class.java).toString()),
                treeId = TreeId(getObject("tree_id", UUID::class.java).toString()),
                givenNames = getString("given_names"),
                surnamePaternal = getString("surname_paternal"),
                surnameMaternal = getString("surname_maternal"),
                sex = Sex.valueOf(getString("sex")),
                isLiving = getBoolean("is_living"),
                profileMediaId = getObject("profile_media_id", UUID::class.java)?.let { MediaId(it.toString()) },
                bio = getString("bio"),
                createdByAccountId = getObject("created_by_account_id", UUID::class.java)?.let { AccountId(it.toString()) },
                version = getLong("version"),
            )
        }
        return results
    }

    private fun ResultRow.toPerson() = Person(
        id = PersonId(this[PersonTable.id].toString()),
        treeId = TreeId(this[PersonTable.treeId].toString()),
        givenNames = this[PersonTable.givenNames],
        surnamePaternal = this[PersonTable.surnamePaternal],
        surnameMaternal = this[PersonTable.surnameMaternal],
        sex = Sex.valueOf(this[PersonTable.sex]),
        isLiving = this[PersonTable.isLiving],
        profileMediaId = this[PersonTable.profileMediaId]?.let { MediaId(it.toString()) },
        bio = this[PersonTable.bio],
        createdByAccountId = this[PersonTable.createdByAccountId]?.let { AccountId(it.toString()) },
        version = this[PersonTable.version],
    )
}
