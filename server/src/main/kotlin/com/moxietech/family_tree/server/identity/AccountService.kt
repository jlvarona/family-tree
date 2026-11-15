package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import kotlinx.coroutines.withContext

object AccountService {

    /**
     * Resolves the account bound to the verified [identity], provisioning it on
     * first login (FT-003). [email] is the token's current address and is
     * stored as profile data; it never selects, links or transfers an account.
     */
    suspend fun getOrCreateForLogin(identity: OidcIdentity, email: String, displayName: String): Account =
        withContext(DatabaseDispatcher) {
            AccountRepository.getOrCreateByIdentity(identity, email, displayName, Locale.EN)
        }
}
