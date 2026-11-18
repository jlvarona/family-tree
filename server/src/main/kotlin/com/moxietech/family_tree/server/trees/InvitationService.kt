package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.api.CreateInvitationRequest
import com.moxietech.family_tree.api.CreatedInvitationResponse
import com.moxietech.family_tree.api.GrantDto
import com.moxietech.family_tree.api.InvitationDelivery
import com.moxietech.family_tree.api.InvitationDto
import com.moxietech.family_tree.api.InvitationGrantDto
import com.moxietech.family_tree.api.InvitationGrantRequest
import com.moxietech.family_tree.api.RedeemInvitationRequest
import com.moxietech.family_tree.api.RedeemInvitationResponse
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.permissions.TreePermissions
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseOptionalUuidField
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.parseVersionParam
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.graph.GraphWriteLock
import com.moxietech.family_tree.server.graph.isUniqueViolation
import com.moxietech.family_tree.server.permissions.GrantRepository
import com.moxietech.family_tree.server.permissions.toDto
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

const val INVITATION_NOT_FOUND_MESSAGE = "Invitation not found or no longer valid"
/** One answer for a wrong, unverified or missing email, so none of them is distinguishable. */
const val INVITATION_EMAIL_NOT_CLAIMABLE_MESSAGE = "This invitation can't be accepted with the account you're signed in with"
const val INVITATION_ALREADY_MEMBER_MESSAGE = "You are already a member of this tree"
const val INVITATION_PERSON_LINKED_MESSAGE = "That person is already linked to a member of this tree"
const val INVITATION_PERSON_PENDING_MESSAGE = "That person already has a pending invitation; revoke it first"
const val INVITATION_EMAIL_UNAVAILABLE_MESSAGE = "Email invitations are not available on this server; share the link instead"
const val INVITATION_EMAIL_INVALID_MESSAGE = "email must be a single valid email address"
const val MAX_INVITATION_GRANTS = 20
const val INVITATION_TOO_MANY_GRANTS_MESSAGE = "An invitation can propose at most $MAX_INVITATION_GRANTS grants"
const val INVITATION_LOCALE_MESSAGE = "locale must be EN or ES"

private const val MEMBERSHIP_PKEY = "membership_pkey"
private const val MEMBERSHIP_PERSON_UNIQUE = "membership_tree_person_key"
private const val INVITATION_PERSON_UNIQUE = "invitation_tree_person_key"

/**
 * FT-011: invitations to a tree and their redemption.
 *
 * **Gate.** Unless [InvitationSettings.enabled] (default off; the backlog keeps
 * invitations off until FT-012 to FT-014 cover shared-tree authorization) the routes are
 * not installed at all, and every method here also answers the generic `404` before any
 * read. Phase 1 authorization (FT-068) is unchanged either way.
 *
 * **Owner policy.** Creating, listing and revoking go through
 * [TreeService.getTreeForWriter], so only the tree's active owner can invite, exactly
 * like every other Phase 1 write. A redemption honours an invitation only while its
 * creator still passes that same rule ([TreePermissions.canWrite]); an invitation can
 * never give more than its creator could grant directly through [GrantRepository].
 *
 * **Secrets.** The token is 256 random bits, returned once in the create response and,
 * with a mail transport, mailed in a link `{acceptUrl}#/trees/{treeId}/join?token=…`
 * whose fragment carries it. Only its SHA-256 digest is stored ([InvitationTokens]). It
 * travels to the API only in a request body, and it is never logged or audited; nor is
 * the invitee's email.
 *
 * **Person reservation.** Create, redemption and owner linking take the linked person's
 * row `FOR NO KEY UPDATE` ([InvitationRepository.reservePerson]) before checking for an
 * existing member or pending invitation, so two of them can't both promise one person.
 *
 * **Redemption** (`POST /api/v1/trees/{treeId}/invitations/redeem`) runs in one READ
 * COMMITTED transaction:
 * 1. take [GraphWriteLock] shared for the URL's tree (the invitation and its grants
 *    reference people, so merges wait for it, FT-007);
 * 2. find and lock the invitation `FOR UPDATE` by tree and digest in one statement: a
 *    token of another tree, or one a concurrent redemption or revocation already used,
 *    finds nothing (`404`);
 * 3. with the time read after that lock, reject an expired invitation (`404`, same
 *    message), an email-addressed invitation whose address isn't the caller's verified
 *    email ([InvitationEmails.claims]; one generic `409`, nothing written, the invitation
 *    stays usable), or one whose creator is no longer the active owner (`404`);
 * 4. lock the caller's membership; an ACTIVE one is a `409`;
 * 5. reserve the linked person and refuse one already linked to another member (`409`,
 *    also enforced by `membership_tree_person_key`); lock each grant anchor
 *    `FOR KEY SHARE` (none can be deleted while the invitation exists, V9);
 * 6. create or activate the membership with the person, create each proposed grant as a
 *    `tree_grant` for the caller (FT-010, same definition; `created_by` is the inviter),
 *    delete the invitation, and audit the membership, every grant and the invitation.
 * A unique violation from a concurrent writer (same account, same person) is a `409`
 * after rollback.
 *
 * The caller is the account bound to the token's verified issuer and subject (FT-003).
 * Email never finds or links an account: for an email-addressed invitation it is only a
 * condition checked against the same token's verified email. Link invitations carry no
 * address and need only a signed-in account.
 */
class InvitationService(
    private val settings: InvitationSettings = InvitationSettings(),
    private val mailer: InvitationMailer? = InvitationMailer.from(settings),
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(InvitationService::class.java)

    init {
        if (settings.enabled) {
            log.warn(
                "Invitations are enabled (INVITATIONS_ENABLED=true) before FT-012 to FT-014 complete shared-tree " +
                    "authorization; use disposable test accounts and data only",
            )
        }
    }

    val enabled: Boolean get() = settings.enabled

    suspend fun create(treeId: TreeId, caller: Account, request: CreateInvitationRequest): CreatedInvitationResponse {
        requireEnabled()
        val tree = TreeService.getTreeForWriter(treeId, caller.id)
        val treeUuid = Uuid.parse(tree.id.value)
        val personUuid = parseOptionalUuidField("personId", request.personId)
        val email = request.email?.let(::normalizeEmail)
        if (email != null && mailer == null) throw ValidationException(listOf(INVITATION_EMAIL_UNAVAILABLE_MESSAGE))
        val locale = parseLocale(request.locale, caller.locale)
        val grants = parseGrants(request.grants)
        val ttl = parseTtl(request.expiresInHours)

        val token = InvitationTokens.generate()
        val now = clock()
        val invitation = writeGuarded {
            GraphWriteLock.shared(treeUuid)
            if (personUuid != null) {
                reservePerson(treeUuid, personUuid)
                if (MembershipRepository.findByPerson(treeUuid, personUuid) != null) throw ConflictException(INVITATION_PERSON_LINKED_MESSAGE)
                if (InvitationRepository.findForPerson(treeUuid, personUuid) != null) throw ConflictException(INVITATION_PERSON_PENDING_MESSAGE)
            }
            grants.forEach { requirePerson(treeUuid, Uuid.parse(it.branch.anchorPersonId.value)) }
            val created = InvitationRepository.insert(
                treeId = treeUuid,
                tokenDigest = InvitationTokens.digest(token),
                email = email,
                personId = personUuid,
                grants = grants,
                createdAt = now,
                expiresAt = now.plus(ttl),
                createdByAccountId = Uuid.parse(caller.id.value),
            )
            audit(treeUuid, caller.id, created.id, AuditAction.CREATE, null, AuditJson.encodeToString(created.auditSnapshot()))
            created
        }

        val delivery = if (email == null) {
            InvitationDelivery.NOT_REQUESTED
        } else {
            deliver(invitation, email, locale, caller.displayName, tree.name, token)
        }
        return CreatedInvitationResponse(invitation.toDto(clock()), token, delivery, settings.acceptLink(invitation.treeId, token))
    }

    suspend fun list(treeId: TreeId, callerAccountId: AccountId): List<InvitationDto> {
        requireEnabled()
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        val now = clock()
        return withContext(DatabaseDispatcher) {
            InvitationRepository.listByTree(Uuid.parse(tree.id.value)).map { it.toDto(now) }
        }
    }

    /** Revokes a pending (or expired) invitation at the version last read; its token stops working. */
    suspend fun revoke(treeId: TreeId, invitationId: String, callerAccountId: AccountId, version: List<String>?) {
        requireEnabled()
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val invitationUuid = parseUuidField("invitationId", invitationId)
        val expected = parseVersionParam(version)
        writeGuarded {
            val before = InvitationRepository.lockForUpdate(treeUuid, invitationUuid)
                ?: throw NotFoundException("No invitation with id $invitationUuid")
            if (before.version != expected) throw staleVersion("Invitation", before.id.toString())
            if (InvitationRepository.delete(treeUuid, invitationUuid, expected) != 1) throw staleVersion("Invitation", before.id.toString())
            audit(
                treeUuid, callerAccountId, before.id, AuditAction.DELETE,
                AuditJson.encodeToString(before.auditSnapshot()),
                AuditJson.encodeToString(InvitationClosedAudit(outcome = "REVOKED")),
            )
        }
    }

    /**
     * Accepts the invitation of [treeId] whose token is in [request]. The tree comes from
     * the link and the URL, and the invitation is looked up by tree and digest together:
     * a malformed tree ID or token, an unknown tree, a token of another tree and an
     * expired, revoked or used token are all the same `404`, and none of them writes or
     * locks the invitation they may belong to.
     *
     * [verifiedEmail] is the caller's token `email` when that token's `email_verified` is
     * the boolean `true`, else null ([com.moxietech.family_tree.server.config.verifiedEmail]).
     */
    suspend fun redeem(
        treeId: String,
        account: Account,
        request: RedeemInvitationRequest,
        verifiedEmail: String? = null,
    ): RedeemInvitationResponse {
        requireEnabled()
        val treeUuid = Uuid.parseOrNull(treeId) ?: throw NotFoundException(INVITATION_NOT_FOUND_MESSAGE)
        val digest = InvitationTokens.digestOrNull(request.token) ?: throw NotFoundException(INVITATION_NOT_FOUND_MESSAGE)
        val caller = account.id
        val accountUuid = Uuid.parse(caller.value)
        return writeGuarded {
            GraphWriteLock.shared(treeUuid)
            val invitation = InvitationRepository.lockByDigest(treeUuid, digest)
                ?: throw NotFoundException(INVITATION_NOT_FOUND_MESSAGE)
            // The time is read after the row lock: an invitation that expired while this
            // redemption waited behind another transaction is refused like any expired one.
            if (invitation.isExpired(clock())) throw NotFoundException(INVITATION_NOT_FOUND_MESSAGE)
            // Decided (concept doc, "Invitation email claims"): an email-addressed invitation is
            // claimed only by an account whose token carries that address as verified. A miss
            // writes nothing and keeps the invitation for the right account.
            val invitedEmail = invitation.email
            if (invitedEmail != null && !InvitationEmails.claims(invitedEmail, verifiedEmail)) {
                throw ConflictException(INVITATION_EMAIL_NOT_CLAIMABLE_MESSAGE)
            }

            // The owner policy at redemption time: the creator must still be the tree's
            // active owner, or the invitation is void.
            val tree = TreeRepository.findById(treeUuid) ?: throw NotFoundException(INVITATION_NOT_FOUND_MESSAGE)
            val inviter = AccountId(invitation.createdByAccountId.toString())
            if (!TreePermissions.canWrite(tree, inviter, MembershipRepository.find(treeUuid, invitation.createdByAccountId))) {
                throw NotFoundException(INVITATION_NOT_FOUND_MESSAGE)
            }

            val existing = MembershipRepository.lockForUpdate(treeUuid, accountUuid)
            if (existing?.status == MembershipStatus.ACTIVE) throw ConflictException(INVITATION_ALREADY_MEMBER_MESSAGE)
            val personUuid = invitation.personId ?: existing?.personId?.let { Uuid.parse(it.value) }
            if (personUuid != null) {
                reservePerson(treeUuid, personUuid)
                val linked = MembershipRepository.findByPerson(treeUuid, personUuid)
                if (linked != null && linked.accountId != caller) throw ConflictException(INVITATION_PERSON_LINKED_MESSAGE)
            }

            val member = if (existing == null) {
                MembershipRepository.insert(treeUuid, accountUuid, personUuid, MembershipStatus.ACTIVE)
                MembershipRepository.find(treeUuid, accountUuid) ?: error("Membership was just inserted but cannot be read back")
            } else {
                MembershipRepository.update(treeUuid, accountUuid, existing.version, personUuid, MembershipStatus.ACTIVE)
                    ?: error("Membership was locked but its version changed")
            }
            AuditLogRepository.append(
                treeId = treeUuid,
                actorAccountId = accountUuid,
                entityType = "Membership",
                entityId = member.auditEntityId(),
                action = if (existing == null) AuditAction.CREATE else AuditAction.UPDATE,
                beforeJson = existing?.let { AuditJson.encodeToString(it.auditSnapshot()) },
                afterJson = AuditJson.encodeToString(member.auditSnapshot()),
            )

            val grants: List<GrantDto> = invitation.grants.map { proposed ->
                requirePerson(treeUuid, Uuid.parse(proposed.branch.anchorPersonId.value))
                val grant = GrantRepository.insert(treeUuid, accountUuid, proposed.role, proposed.branch, invitation.createdByAccountId)
                val dto = grant.toDto()
                AuditLogRepository.append(treeUuid, accountUuid, "Grant", dto.id, AuditAction.CREATE, null, AuditJson.encodeToString(dto))
                dto
            }

            if (InvitationRepository.delete(treeUuid, invitation.id, invitation.version) != 1) {
                error("Invitation ${invitation.id} was locked but could not be deleted")
            }
            audit(
                treeUuid, caller, invitation.id, AuditAction.DELETE,
                AuditJson.encodeToString(invitation.auditSnapshot()),
                AuditJson.encodeToString(InvitationClosedAudit(outcome = "ACCEPTED", accountId = caller.value)),
            )
            RedeemInvitationResponse(member.toMemberDto(account.displayName), grants)
        }
    }

    /**
     * Hands the email to the transport after the invitation is committed, outside any
     * transaction. The transport may block (the capture writes a file), so it runs on
     * [Dispatchers.IO], never on a request thread.
     */
    private suspend fun deliver(
        invitation: PendingInvitation,
        email: String,
        locale: Locale,
        inviterName: String,
        treeName: String,
        token: String,
    ): InvitationDelivery {
        val transport = mailer ?: return InvitationDelivery.FAILED
        val mail = InvitationMailComposer.compose(invitation.id, invitation.treeId, settings, email, locale, inviterName, treeName, token, invitation.expiresAt)
        return try {
            withContext(Dispatchers.IO) { transport.send(mail) }
            InvitationDelivery.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the invitation ID and the failure type: never the address, the body or the token.
            log.warn("Invitation mail delivery failed for invitation {} ({})", invitation.id, e.javaClass.simpleName)
            InvitationDelivery.FAILED
        }
    }

    private fun requireEnabled() {
        if (!settings.enabled) throw NotFoundException(NOT_FOUND_MESSAGE)
    }

    private fun normalizeEmail(raw: String): String {
        val email = raw.trim()
        val valid = email.length in 3..254 && email.none { it.isISOControl() || it == ',' || it == ';' } &&
            Validation.validateEmail(email).isEmpty()
        if (!valid) throw ValidationException(listOf(INVITATION_EMAIL_INVALID_MESSAGE))
        return email
    }

    private fun parseLocale(raw: String?, fallback: Locale): Locale =
        if (raw == null) fallback else Locale.entries.firstOrNull { it.name == raw } ?: throw ValidationException(listOf(INVITATION_LOCALE_MESSAGE))

    private fun parseTtl(hours: Int?): Duration {
        if (hours == null) return settings.defaultTtl
        val ttl = Duration.ofHours(hours.toLong())
        if (hours < 1 || ttl > settings.maxTtl) {
            throw ValidationException(listOf("expiresInHours must be between 1 and ${settings.maxTtl.toHours()}"))
        }
        return ttl
    }

    private fun parseGrants(requests: List<InvitationGrantRequest>): List<ProposedGrant> {
        if (requests.size > MAX_INVITATION_GRANTS) throw ValidationException(listOf(INVITATION_TOO_MANY_GRANTS_MESSAGE))
        return requests.map { request ->
            val role = GrantRole.entries.firstOrNull { it.name == request.role }
                ?: throw ValidationException(listOf("Unknown grant role '${request.role}'"))
            val direction = BranchDirection.entries.firstOrNull { it.name == request.direction }
                ?: throw ValidationException(listOf("Unknown branch direction '${request.direction}'"))
            Validation.validateBranchDepth(request.depth).requireValid()
            val anchor = parseUuidField("anchorPersonId", request.anchorPersonId)
            ProposedGrant(role, Branch(PersonId(anchor.toString()), direction, request.depth, request.includePartners))
        }
    }

    /** `FOR KEY SHARE` on a grant anchor in [treeId] (the FT-002 writer contract); 404 when there is none. */
    private fun requirePerson(treeId: Uuid, personId: Uuid) {
        if (!GrantRepository.lockAnchor(treeId, personId)) throw NotFoundException("No person with id $personId")
    }

    /** The linking reservation ([InvitationRepository.reservePerson]); 404 when there is no such person. */
    private fun reservePerson(treeId: Uuid, personId: Uuid) {
        if (!InvitationRepository.reservePerson(treeId, personId)) throw NotFoundException("No person with id $personId")
    }

    /**
     * One READ COMMITTED transaction (stated: every statement after a lock wait must see
     * what the other transaction committed). A unique violation from a concurrent writer
     * is a 409 after rollback.
     */
    private suspend fun <T> writeGuarded(block: () -> T): T = try {
        withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) { block() }
        }
    } catch (e: SQLException) {
        when {
            e.isUniqueViolation(MEMBERSHIP_PKEY) -> throw ConflictException(INVITATION_ALREADY_MEMBER_MESSAGE)
            e.isUniqueViolation(MEMBERSHIP_PERSON_UNIQUE) -> throw ConflictException(INVITATION_PERSON_LINKED_MESSAGE)
            e.isUniqueViolation(INVITATION_PERSON_UNIQUE) -> throw ConflictException(INVITATION_PERSON_PENDING_MESSAGE)
            else -> throw e
        }
    }

    private fun audit(treeId: Uuid, actor: AccountId, invitationId: Uuid, action: AuditAction, before: String?, after: String?) =
        AuditLogRepository.append(
            treeId = treeId,
            actorAccountId = Uuid.parse(actor.value),
            entityType = "Invitation",
            entityId = invitationId.toString(),
            action = action,
            beforeJson = before,
            afterJson = after,
        )

    companion object {
        /** The generic answer while invitations are disabled, the same as an unknown route's. */
        const val NOT_FOUND_MESSAGE = "Not found"
    }
}

/**
 * Audit shape of an invitation, entity `Invitation`: what was offered, never the token,
 * its digest or the email address ([hasEmail] only says whether one was given).
 */
@Serializable
internal data class InvitationAuditSnapshot(
    val id: String,
    val treeId: String,
    val personId: String?,
    val hasEmail: Boolean,
    val grants: List<InvitationGrantDto>,
    val expiresAt: String,
    val createdByAccountId: String,
    val version: Long,
)

/** `after` of an invitation's DELETE entry: how it ended, and who accepted it. */
@Serializable
internal data class InvitationClosedAudit(val outcome: String, val accountId: String? = null)

internal fun PendingInvitation.auditSnapshot() = InvitationAuditSnapshot(
    id = id.toString(),
    treeId = treeId.toString(),
    personId = personId?.toString(),
    hasEmail = email != null,
    grants = grants.map { it.toDto() },
    expiresAt = expiresAt.toString(),
    createdByAccountId = createdByAccountId.toString(),
    version = version,
)

internal fun PendingInvitation.toDto(now: Instant) = InvitationDto(
    id = id.toString(),
    treeId = treeId.toString(),
    personId = personId?.toString(),
    email = email,
    grants = grants.map { it.toDto() },
    expiresAt = expiresAt.toString(),
    expired = isExpired(now),
    createdAt = createdAt.toString(),
    version = version,
)

private fun ProposedGrant.toDto() = InvitationGrantDto(
    role = role.name,
    anchorPersonId = branch.anchorPersonId.value,
    direction = branch.direction.name,
    depth = branch.depth,
    includePartners = branch.includePartners,
)
