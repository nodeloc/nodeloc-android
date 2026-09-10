package com.nodeloc.app.core.store

/**
 * State that belongs to one account rather than to the device.
 *
 * Sign-out used to clear the credential and three flags, which left the next
 * account reading the previous one's preferences, node memberships, chat
 * transcripts and push watermark. Anything holding such state implements this
 * and is registered with [SessionRepository].
 *
 * [resetForSignOut] runs *after* the credential is already gone, so it cannot
 * reach the network, and it must not throw: one repository failing cannot leave
 * the next one still holding the account that just left.
 */
interface AccountScoped {
    suspend fun resetForSignOut()
}
