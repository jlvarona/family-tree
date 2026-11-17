package com.moxietech.family_tree.server.trees

import java.nio.file.Path
import java.time.Duration

/**
 * FT-011 invitation settings, all from environment variables (README "Invitations").
 *
 * Invitations stay **off** unless `INVITATIONS_ENABLED=true`: the backlog keeps them
 * gated until FT-012 to FT-014 cover shared-tree authorization, privacy and proposals.
 * While off, every invitation and member-linking route answers the generic `404` and
 * nothing is read or written, so Phase 1 authorization (FT-068) is exactly as before.
 *
 * Mail never leaves the machine in FT-011: the only transports are `none` (default;
 * email invitations are refused and links are shared by hand) and `capture`, which
 * writes each message as an `.eml` file into `INVITATION_MAIL_CAPTURE_DIR` for local
 * development and tests. There is no SMTP or SES transport yet.
 */
data class InvitationSettings(
    val enabled: Boolean = strictBoolean("INVITATIONS_ENABLED") ?: false,
    /** Lifetime of an invitation when the request names none. */
    val defaultTtl: Duration = Duration.ofHours(positiveLong("INVITATION_DEFAULT_TTL_HOURS") ?: 168),
    /** Longest lifetime a request may ask for. */
    val maxTtl: Duration = Duration.ofHours(positiveLong("INVITATION_MAX_TTL_HOURS") ?: 720),
    val mailTransport: MailTransport = env("INVITATION_MAIL_TRANSPORT")?.let(MailTransport::parse) ?: MailTransport.NONE,
    /** Directory the `capture` transport writes to; required with it. */
    val mailCaptureDir: Path? = env("INVITATION_MAIL_CAPTURE_DIR")?.ifBlank { null }?.let { Path.of(it) },
    /**
     * The web client's address, e.g. `http://localhost:3000/`. Links are
     * `{acceptUrl}#/trees/{treeId}/join?token=…` ([acceptLink]): the tree ID and the token
     * sit in the fragment, which browsers never send to a server, and the web client's
     * join page sends them to `POST /api/v1/trees/{treeId}/invitations/redeem`. Required
     * with a mail transport; without it the create response has no link and the owner
     * shares the tree ID and token another way.
     */
    val acceptUrl: String? = env("INVITATION_ACCEPT_URL")?.ifBlank { null },
    val mailFrom: String = env("INVITATION_MAIL_FROM")?.ifBlank { null } ?: "invitations@family-tree.invalid",
) {
    /** The acceptance link for an invitation of [treeId], or null without [acceptUrl]. */
    fun acceptLink(treeId: kotlin.uuid.Uuid, token: String): String? =
        acceptUrl?.let { "$it#/trees/$treeId/join?token=$token" }

    enum class MailTransport {
        NONE,
        CAPTURE,
        ;

        companion object {
            fun parse(raw: String): MailTransport = entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("INVITATION_MAIL_TRANSPORT must be 'none' or 'capture'")
        }
    }

    init {
        require(!defaultTtl.isNegative && !defaultTtl.isZero) { "INVITATION_DEFAULT_TTL_HOURS must be positive" }
        require(defaultTtl <= maxTtl) { "INVITATION_DEFAULT_TTL_HOURS must not exceed INVITATION_MAX_TTL_HOURS" }
        require(mailTransport != MailTransport.CAPTURE || mailCaptureDir != null) {
            "INVITATION_MAIL_TRANSPORT=capture needs INVITATION_MAIL_CAPTURE_DIR"
        }
        require(mailTransport == MailTransport.NONE || acceptUrl != null) {
            "A mail transport needs INVITATION_ACCEPT_URL, the web client's address for accepting invitations"
        }
        require(acceptUrl == null || '#' !in acceptUrl) { "INVITATION_ACCEPT_URL must not contain a fragment" }
        require(mailFrom.none { it.isISOControl() }) { "INVITATION_MAIL_FROM must be a single line" }
    }
}

private fun env(name: String): String? = System.getenv(name)

/** Unset is null; anything but `true` or `false` stops the server rather than guessing. */
private fun strictBoolean(name: String): Boolean? = env(name)?.let {
    it.trim().toBooleanStrictOrNull() ?: throw IllegalArgumentException("$name must be 'true' or 'false'")
}

private fun positiveLong(name: String): Long? = env(name)?.let {
    it.trim().toLongOrNull()?.takeIf { value -> value > 0 } ?: throw IllegalArgumentException("$name must be a positive number of hours")
}
