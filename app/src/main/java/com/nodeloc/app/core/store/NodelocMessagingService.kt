package com.nodeloc.app.core.store

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nodeloc.app.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a notification arrives now, instead of being found fifteen minutes late.
 *
 * Data-only messages by design — the server sends no `notification` block for
 * Android. With one, the system draws the banner while this class never runs,
 * which would mean no de-duplication against the poll, no per-category silence
 * that the device decides, and no chance to route the tap ourselves.
 */
class NodelocMessagingService : FirebaseMessagingService() {

    /**
     * The service is torn down as soon as it returns, so the work cannot live
     * on a scope tied to it. Android gives a data message about ten seconds of
     * wakelock either way, which is more than a notify() needs.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) return
        val services = ServiceLocator.init(applicationContext)
        scope.launch { services.pushRepository.onPushMessage(data) }
    }

    /**
     * A token is not forever: it rotates on reinstall, on restore to a new
     * phone, and whenever Firebase decides. Re-registering here is what stops
     * the site writing to an address that has moved.
     */
    override fun onNewToken(token: String) {
        val services = ServiceLocator.init(applicationContext)
        scope.launch { services.pushRepository.registerForPush() }
    }
}
