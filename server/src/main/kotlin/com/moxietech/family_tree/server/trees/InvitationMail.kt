package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.domain.Locale
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.ResourceBundle
import kotlin.uuid.Uuid

/**
 * FT-011: one invitation email. [toString] leaves out the address and the body (which
 * holds the token), so a message logged by mistake leaks neither.
 */
class InvitationMail(
    val invitationId: Uuid,
    val from: String,
    val to: String,
    val subject: String,
    val body: String,
) {
    override fun toString() = "InvitationMail(invitationId=$invitationId)"
}

/**
 * FT-011: hands an invitation email to a transport. Called after the invitation is
 * committed, outside any transaction; a failure leaves the invitation in place and is
 * reported to the owner as [com.moxietech.family_tree.api.InvitationDelivery.FAILED].
 */
fun interface InvitationMailer {
    fun send(mail: InvitationMail)

    companion object {
        /** The configured transport, or null when email invitations are off (`none`). */
        fun from(settings: InvitationSettings): InvitationMailer? = when (settings.mailTransport) {
            InvitationSettings.MailTransport.NONE -> null
            InvitationSettings.MailTransport.CAPTURE -> DirectoryMailCapture(checkNotNull(settings.mailCaptureDir))
        }
    }
}

/**
 * Local capture transport: writes each message as `{invitationId}.eml` (RFC 5322, UTF-8)
 * into [directory], written to a temporary file first and then moved into place, so a
 * reader never sees half a message. For local development and tests only: the files
 * hold live tokens, so the directory must stay private to the developer.
 */
class DirectoryMailCapture(private val directory: Path) : InvitationMailer {
    override fun send(mail: InvitationMail) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "invitation-", ".tmp")
        try {
            Files.write(temp, render(mail).toByteArray(StandardCharsets.UTF_8))
            Files.move(temp, directory.resolve("${mail.invitationId}.eml"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun render(mail: InvitationMail): String = buildString {
        append("From: ").append(mail.from).append("\r\n")
        append("To: ").append(mail.to).append("\r\n")
        append("Subject: ").append(encodedWord(mail.subject)).append("\r\n")
        append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC))).append("\r\n")
        append("Message-ID: <").append(mail.invitationId).append("@family-tree.invalid>\r\n")
        append("MIME-Version: 1.0\r\n")
        append("Content-Type: text/plain; charset=UTF-8\r\n")
        append("Content-Transfer-Encoding: 8bit\r\n")
        append("\r\n")
        append(mail.body.replace("\r\n", "\n").replace("\n", "\r\n"))
        append("\r\n")
    }

    /** RFC 2047 so non-ASCII subjects (Spanish) stay intact in a header. */
    private fun encodedWord(text: String): String =
        if (text.all { it.code in 0x20..0x7e }) text
        else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8)) + "?="
}

/**
 * Builds the invitation email from the `i18n/InvitationMail` resources (English base,
 * Spanish `_es`). The subject is fixed text, so no caller-supplied value ever reaches a
 * header except the validated recipient address. The inviter's name and the tree's name
 * go into the plain-text body only, without control or bidi-override characters.
 */
internal object InvitationMailComposer {
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    fun compose(
        invitationId: Uuid,
        treeId: Uuid,
        settings: InvitationSettings,
        to: String,
        locale: Locale,
        inviterName: String,
        treeName: String,
        token: String,
        expiresAt: Instant,
    ): InvitationMail {
        val bundle = ResourceBundle.getBundle(
            "i18n.InvitationMail",
            java.util.Locale.forLanguageTag(locale.name.lowercase()),
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
        )
        val link = checkNotNull(settings.acceptLink(treeId, token)) { "A mail transport needs INVITATION_ACCEPT_URL" }
        val body = bundle.getString("body")
            .replace("{inviter}", plain(inviterName))
            .replace("{tree}", plain(treeName))
            .replace("{link}", link)
            .replace("{expires}", DATE.format(expiresAt))
        return InvitationMail(invitationId, settings.mailFrom, to, bundle.getString("subject"), body)
    }

    private val BIDI_CONTROLS = Regex("[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F]")

    private fun plain(value: String): String =
        value.filterNot { it.isISOControl() }.replace(BIDI_CONTROLS, "").take(200)
}
