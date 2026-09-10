package com.nodeloc.app

import android.content.Context
import com.nodeloc.app.core.util.DeviceInfo
import com.nodeloc.app.core.network.AuthService
import com.nodeloc.app.core.network.ChatDiskCache
import com.nodeloc.app.core.network.DiscourseAuth
import com.nodeloc.app.core.network.DiscourseClient
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.AppLocale
import com.nodeloc.app.core.update.UpdateController
import com.nodeloc.app.core.network.MessageBusClient
import com.nodeloc.app.core.network.PersistentCookieJar
import com.nodeloc.app.core.network.SecureStore
import com.nodeloc.app.core.store.AccountScoped
import com.nodeloc.app.core.store.AppPreferences
import com.nodeloc.app.core.store.MessageCenterRepository
import com.nodeloc.app.core.store.PushRepository
import com.nodeloc.app.core.store.SessionRepository
import com.nodeloc.app.core.store.SiteRepository
import com.nodeloc.app.core.store.UserPreferencesRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled container. The graph is a dozen singletons with no cycles, so a
 * DI framework would add a build step and annotation processing for no gain —
 * this file *is* the graph, readable top to bottom.
 */
class ServiceLocator private constructor(context: Context) {
    val appContext: Context = context.applicationContext

    init {
        // Before any repository is built: the language decides what the very
        // first toast says, and toasts are raised before a screen exists.
        AppLocale.install(appContext)
    }

    val secureStore = SecureStore(appContext)
    val cookieJar = PersistentCookieJar(secureStore.cookieStorage)
    val auth = DiscourseAuth()
    val client = DiscourseClient(DiscourseConfig.clientId(appContext), auth, cookieJar)
    val authService = AuthService(appContext, client, secureStore)
    val chatDiskCache = ChatDiskCache(appContext)

    /**
     * One bus for the app. Per-ViewModel instances shared this install's single
     * MessageBus client id, and Discourse cancels a poll when another arrives
     * under the same id — two live conversations made both loops spin.
     */
    val messageBus = MessageBusClient(client)

    /**
     * For the few writes that must outlive the screen that asked for them.
     *
     * Discarding a draft is one: the tap that discards it is also the tap that
     * leaves the composer, so a `viewModelScope` job racing `onCleared` is how
     * a discarded draft comes back.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val preferences = AppPreferences(appContext)
    val siteRepository = SiteRepository(client)
    val session = SessionRepository(client, authService, preferences)
    val blockedUsers = com.nodeloc.app.core.store.BlockedUsers(client)
    val messageCenter = MessageCenterRepository(appContext, client, session, messageBus)
    val userPreferences = UserPreferencesRepository(client, session)
    val pushRepository = PushRepository(appContext, client, preferences)
    // Which UpdateController this is depends on the flavour: the github build
    // installs an APK, the play build opens the store listing.
    val updates = UpdateController(appContext, client)

    init {
        // A rejection from any call, not just the current-user fetch.
        auth.onSessionRejected = session::onSessionRejectedByServer

        // A tail belongs to the request rather than to the screen that started
        // it, so the client asks here instead of every composer remembering to
        // pass one down. Reduced on this side: the rung the account agreed to
        // decides which fields leave the device at all, not merely which ones
        // the server is then allowed to keep.
        client.postSourceFields = {
            DeviceInfo.postSourceFields(preferences.currentPostSourceLevel())
        }

        // After every `val` above, because Kotlin initialises in declaration
        // order and each of these is built later than `session` is.
        session.registerAccountScoped(
            siteRepository,
            messageCenter,
            userPreferences,
            pushRepository,
            // Wrapped rather than implemented: these live outside core/store,
            // which nothing there may depend on.
            object : AccountScoped {
                override suspend fun resetForSignOut() {
                    chatDiskCache.clear()
                    // Avatars are the harmless half; the rest is private-message
                    // and attachment imagery belonging to whoever just left.
                    listOf("image_cache", "reply", "composer").forEach { name ->
                        runCatching { File(appContext.cacheDir, name).deleteRecursively() }
                    }
                }
            },
        )
    }

    companion object {
        @Volatile
        private var instance: ServiceLocator? = null

        fun init(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context).also { instance = it }
            }

        val get: ServiceLocator
            get() = instance ?: error("ServiceLocator not initialised")
    }
}
