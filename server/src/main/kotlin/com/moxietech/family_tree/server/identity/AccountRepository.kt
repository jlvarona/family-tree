package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.server.config.AppDatabase
import java.sql.Connection
import java.sql.ResultSet

object AccountRepository {

    private const val INSERT_IF_ABSENT = """
        insert into account (oidc_issuer, oidc_subject, email, display_name, locale)
        values (?, ?, ?, ?, ?)
        on conflict (oidc_issuer, oidc_subject) do nothing
    """

    // Only writes when the provider reports a new address, so ordinary requests stay read-only.
    private const val REFRESH_EMAIL = """
        update account set email = ?
        where oidc_issuer = ? and oidc_subject = ? and email <> ?
    """

    private const val SELECT_BY_IDENTITY = """
        select id, email, display_name, locale from account
        where oidc_issuer = ? and oidc_subject = ?
    """

    /**
     * Returns the account bound to [identity], creating it on first login, and
     * records [email] as the account's current address.
     *
     * One READ COMMITTED transaction. Parallel first logins race on
     * `account_oidc_identity_key`: the losing insert waits for the winner, then
     * does nothing, and the select sees the winner's row, so every caller gets
     * the same account. [displayName] and [locale] apply only to a new account.
     * Email never selects an account, and legacy accounts without an identity
     * are never matched (V3, README "Resolving legacy accounts").
     */
    fun getOrCreateByIdentity(identity: OidcIdentity, email: String, displayName: String, locale: Locale): Account =
        AppDatabase.dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            try {
                connection.prepareStatement(INSERT_IF_ABSENT).use { st ->
                    st.setString(1, identity.issuer)
                    st.setString(2, identity.subject)
                    st.setString(3, email)
                    st.setString(4, displayName)
                    st.setString(5, locale.name.lowercase())
                    st.executeUpdate()
                }
                connection.prepareStatement(REFRESH_EMAIL).use { st ->
                    st.setString(1, email)
                    st.setString(2, identity.issuer)
                    st.setString(3, identity.subject)
                    st.setString(4, email)
                    st.executeUpdate()
                }
                val account = connection.prepareStatement(SELECT_BY_IDENTITY).use { st ->
                    st.setString(1, identity.issuer)
                    st.setString(2, identity.subject)
                    st.executeQuery().use { rs -> if (rs.next()) rs.toAccount() else null }
                } ?: error("Account for an OIDC identity was just ensured but cannot be read back")
                connection.commit()
                account
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }

    private fun ResultSet.toAccount() = Account(
        id = AccountId(getObject("id").toString()),
        email = getString("email"),
        displayName = getString("display_name"),
        locale = Locale.valueOf(getString("locale").uppercase()),
    )
}
