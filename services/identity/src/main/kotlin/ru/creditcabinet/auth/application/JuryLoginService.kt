package ru.creditcabinet.auth.application

import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.auth.domain.JuryLoginAccount
import ru.creditcabinet.auth.domain.JuryLoginGrantPort
import ru.creditcabinet.identity.application.IdentityTransactions
import ru.creditcabinet.identity.domain.IdentityFailure.InvalidLink
import ru.creditcabinet.user.domain.UserDataPort

class JuryLoginService(
    private val grants: JuryLoginGrantPort,
    private val users: UserDataPort,
    private val tokens: SessionTokens,
    private val transactions: IdentityTransactions,
) {
    fun inspect(token: String?): JuryLoginAccount = grants.inspect(tokenHash(token)) ?: throw InvalidLink()

    fun redeem(token: String?): IdentitySession {
        val hash = tokenHash(token)
        return transactions.write {
            val userId = grants.consume(hash) ?: throw InvalidLink()
            val user = users.findById(userId) ?: throw InvalidLink()
            IdentitySession(tokens.issue(user), user)
        }
    }

    private fun tokenHash(token: String?): String {
        if (token == null || !TOKEN_FORMAT.matches(token)) throw InvalidLink()
        return AccessGrant.hash(token)
    }

    companion object {
        // The final character of an unpadded base64url encoding of 32 bytes has two zero bits.
        private val TOKEN_FORMAT = Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
    }
}
