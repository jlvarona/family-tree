package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import kotlinx.coroutines.withContext

object AccountService {

    /**
     * First call for a given email creates the account (just-in-time
     * provisioning from the OIDC token); every later call returns the same
     * row. Keyed on email for now — see AccountRepository note on adding a
     * stable OIDC-subject column before launch, since email can change.
     */
    suspend fun getOrCreateForLogin(email: String, displayName: String): Account =
        withContext(DatabaseDispatcher) {
            AccountRepository.findByEmail(email)
                ?: AccountRepository.insert(email, displayName, Locale.EN)
        }
}
