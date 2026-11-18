package com.moxietech.family_tree.server.trees

/**
 * FT-011: who may claim an email-addressed invitation (concept doc, "Invitation email
 * claims"): an account whose token carries the invited address as verified. Addresses are
 * compared after trimming and folding ASCII case. Other characters must match exactly.
 */
internal object InvitationEmails {

    /** Whether [verifiedEmail] (null when the token's email isn't verified) claims an invitation to [invitedEmail]. */
    fun claims(invitedEmail: String, verifiedEmail: String?): Boolean {
        val claimed = verifiedEmail?.matchingForm() ?: return false
        return claimed.isNotEmpty() && invitedEmail.matchingForm() == claimed
    }

    private fun String.matchingForm(): String = trim().map {
        if (it in 'A'..'Z') it.lowercaseChar() else it
    }.joinToString("")
}
