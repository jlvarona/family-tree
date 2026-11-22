package com.moxietech.family_tree.server.collaboration

import com.moxietech.family_tree.server.trees.InvitationSettings

/**
 * FT-014: whether change proposals are on. They have no setting of their own: they are
 * one half of the sharing workflow, switched with invitations by the default-off
 * `INVITATIONS_ENABLED` ([forSharing]), so a server can never invite members without
 * routing their out-of-branch edits to review.
 *
 * While off, every out-of-branch or scope-widening write is the FT-012/FT-013 `403` with
 * nothing written, exactly as before, and the proposal routes aren't installed (the plain
 * `404` of an unknown route). While on, those writes by a member who can contribute are
 * queued as proposals (`202`), and the review routes exist.
 */
data class ChangeProposalSettings(val enabled: Boolean) {
    companion object {
        val DISABLED = ChangeProposalSettings(enabled = false)

        /** Proposals follow the sharing gate (`INVITATIONS_ENABLED`). */
        fun forSharing(invitations: InvitationSettings) = ChangeProposalSettings(enabled = invitations.enabled)
    }
}
