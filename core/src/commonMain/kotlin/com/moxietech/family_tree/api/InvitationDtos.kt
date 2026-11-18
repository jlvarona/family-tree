package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable

/**
 * FT-011: a grant the tree owner proposes in an invitation. It becomes a real grant
 * (FT-010) for the new member when the invitation is accepted, with the same
 * definition. [role] is a `GrantRole` name, [direction] a `BranchDirection` name.
 */
@Serializable
data class InvitationGrantRequest(
    val role: String,
    val anchorPersonId: String,
    val direction: String,
    val depth: Int? = null,
    val includePartners: Boolean = false,
)

/**
 * FT-011: a new invitation to the tree (owner only).
 *
 * - [personId]: the invitee's own person in the tree, linked to their membership on
 *   acceptance; it drives their home branch. Optional: the owner can link it later.
 * - [email]: where to send the invitation. Delivery data only, never an account
 *   identity (FT-003). Without it, the invitation is a shareable link.
 * - [grants]: proposed grants, created for the member on acceptance.
 * - [expiresInHours]: lifetime; the server's default when null, capped by its maximum.
 * - [locale]: language of the email (`EN` or `ES`); the inviter's own when null.
 */
@Serializable
data class CreateInvitationRequest(
    val personId: String? = null,
    val email: String? = null,
    val grants: List<InvitationGrantRequest> = emptyList(),
    val expiresInHours: Int? = null,
    val locale: String? = null,
)

@Serializable
data class InvitationGrantDto(
    val role: String,
    val anchorPersonId: String,
    val direction: String,
    val depth: Int? = null,
    val includePartners: Boolean,
)

/**
 * FT-011: a pending invitation as the tree owner sees it. The secret token is never
 * part of it; the server keeps only a digest. [expiresAt] is ISO-8601 UTC.
 */
@Serializable
data class InvitationDto(
    val id: String,
    val treeId: String,
    val personId: String? = null,
    val email: String? = null,
    val grants: List<InvitationGrantDto>,
    val expiresAt: String,
    val expired: Boolean,
    val createdAt: String,
    val version: Long,
)

/** FT-011: what happened to an invitation email when the invitation was created. */
@Serializable
enum class InvitationDelivery {
    /** No email address was given: share [CreatedInvitationResponse.token] as a link. */
    NOT_REQUESTED,
    /** Handed to the configured mail transport. */
    SENT,
    /** The transport refused or failed; the invitation exists and the token can be shared another way. */
    FAILED,
}

/**
 * FT-011: the created invitation and its secret [token], returned exactly once. The
 * token is what the invitee presents to accept, with the invitation's tree. [link] is
 * the web client's join page `{base}#/trees/{treeId}/join?token=…` when the server
 * knows the client's address (`INVITATION_ACCEPT_URL`): both values sit in the URL
 * fragment, never in a path or query string sent to a server.
 */
@Serializable
data class CreatedInvitationResponse(
    val invitation: InvitationDto,
    val token: String,
    val delivery: InvitationDelivery,
    val link: String? = null,
)

/**
 * FT-011: accepts an invitation (`POST /api/v1/trees/{treeId}/invitations/redeem`). The
 * token travels only in the request body; the tree is the one in the link.
 */
@Serializable
data class RedeemInvitationRequest(val token: String)

/** FT-011: a tree's member as the tree owner sees it. Email is not included. */
@Serializable
data class MemberDto(
    val treeId: String,
    val accountId: String,
    val displayName: String,
    val personId: String? = null,
    val status: String,
    val version: Long,
)

/** FT-011: the membership the redeemer now holds and the grants created from the invitation. */
@Serializable
data class RedeemInvitationResponse(
    val member: MemberDto,
    val grants: List<GrantDto>,
)

/** FT-011: links a member to their own person in the tree, or unlinks them (null), at the [version] last read. */
@Serializable
data class LinkMemberPersonRequest(
    val personId: String? = null,
    val version: Long,
)
