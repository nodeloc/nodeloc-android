package com.nodeloc.app.core.store

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.MainActivity
import androidx.core.net.toUri
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.DiscourseNotification
import com.nodeloc.app.core.network.DiscourseClient
import com.nodeloc.app.core.network.DiscourseConfig
import kotlinx.coroutines.flow.first
import com.google.firebase.messaging.FirebaseMessaging
import com.nodeloc.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Notification families the user can silence. Each gets its own system channel
 * so Android's own settings can mute them independently too.
 */
enum class PushCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int,
) {
    Replies("replies", R.string.push_replies, R.string.push_replies_detail),
    Likes("likes", R.string.push_likes, R.string.push_likes_detail),
    PrivateMessages("private_messages", R.string.push_messages, R.string.push_messages_detail),
    System("system", R.string.push_other, R.string.push_other_detail),
    ;

    companion object {
        fun of(kind: NotificationFormatter.Kind): PushCategory = when (kind) {
            NotificationFormatter.Kind.Comment -> Replies
            NotificationFormatter.Kind.Like -> Likes
            NotificationFormatter.Kind.Message -> PrivateMessages
            NotificationFormatter.Kind.System, NotificationFormatter.Kind.Star -> System
        }
    }
}

/**
 * Push without a push server.
 *
 * Discourse only relays to the official app, so delivery is a WorkManager poll
 * of `notifications.json` with a persisted watermark. The first poll after
 * enabling **only sets the baseline** — otherwise turning push on would dump
 * the entire unread backlog into the shade at once.
 */
class PushRepository(
    private val context: Context,
    private val client: DiscourseClient,
    private val preferences: AppPreferences,
) : AccountScoped {
    private val appContext = context.applicationContext

    fun ensureChannels() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        PushCategory.entries.forEach { category ->
            val channel = NotificationChannel(
                category.id,
                appContext.getString(category.titleRes),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = appContext.getString(category.detailRes) }
            manager.createNotificationChannel(channel)
        }
    }

    fun hasSystemPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun setEnabled(enabled: Boolean) {
        val current = preferences.pushSettings.first()
        preferences.setPushSettings(current.copy(enabled = enabled))
        if (enabled) {
            // Schedules the poll first and then hands over the address: if the
            // registration fails there is still something fetching, which is
            // the state this app shipped in and is nobody's emergency.
            schedulePolling()
            registerForPush()
        } else {
            unregisterForPush()
            cancelPolling()
        }
    }

    /**
     * The categories are part of the address, so changing them re-registers.
     * Filtering only on arrival would mean waking a phone to throw the message
     * away — which is most of what the reader switched the category off for.
     */
    suspend fun onCategoriesChanged() {
        if (preferences.pushSettings.first().enabled) registerForPush()
    }

    /**
     * @param interval how often to look. Fifteen minutes is the floor
     *   WorkManager will honour and what this cost before there was anything
     *   better; once the server is delivering, the poll stays only as a net
     *   under a dropped message and can afford to be rare.
     */
    fun schedulePolling(interval: Duration = POLL_INTERVAL) {
        ensureChannels()
        val request = PeriodicWorkRequestBuilder<NotificationPollWorker>(interval.toJavaDuration())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    // ------------------------------------------------------------ real push

    /**
     * Hands this handset's address to the site, and slows the poll down.
     *
     * Done together on purpose: the poll is what keeps notifications arriving
     * while the address is unknown, so it may only be relaxed once one is
     * registered — and it is relaxed rather than cancelled, because a delivery
     * can still be dropped between Google and a dozing phone.
     */
    suspend fun registerForPush() {
        if (!client.auth.isAuthenticated) return
        if (!preferences.pushSettings.first().enabled) return

        val token = runCatchingCancellable { firebaseToken() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return

        val settings = preferences.pushSettings.first()
        val registered = runCatchingCancellable {
            client.registerDevice(
                token = token,
                appVersion = BuildConfig.VERSION_NAME,
                locale = Locale.getDefault().toLanguageTag(),
                timezone = TimeZone.getDefault().id,
                categories = enabledCategories(settings),
            )
        }.isSuccess

        preferences.setPushToken(if (registered) token else null)
        schedulePolling(if (registered) BACKSTOP_INTERVAL else POLL_INTERVAL)
    }

    /**
     * The token, awaited without pulling in kotlinx-coroutines-play-services.
     *
     * One `Task` is the only thing this app asks of Play services, and the
     * artifact that would make it a suspend call brings the whole interop
     * layer for it — the same trade the okhttp await here already declines.
     */
    private suspend fun firebaseToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> if (continuation.isActive) continuation.resume(token) {  _, _, _ -> } }
            .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    }

    /**
     * Stops the site writing here.
     *
     * Best effort and deliberately so: the token is cleared locally whatever
     * the server says, because the alternative to a failed call is a device
     * that believes it is unsubscribed while notifications keep arriving.
     * The server drops the row itself the first time FCM calls the token dead.
     */
    private suspend fun unregisterForPush() {
        val token = preferences.pushToken() ?: return
        runCatchingCancellable { client.unregisterDevice(token) }
        preferences.setPushToken(null)
    }

    /**
     * A message that arrived rather than one that was fetched.
     *
     * Goes through the same watermark as the poll, so the two paths cannot
     * banner the same event twice — and so a poll that runs afterwards does
     * not replay what was already shown.
     */
    suspend fun onPushMessage(data: Map<String, String>) {
        val settings = preferences.pushSettings.first()
        if (!settings.enabled || !hasSystemPermission()) return

        val category = PushCategory.entries.firstOrNull { it.id == data["category"] } ?: PushCategory.System
        val notificationId = data["notification_id"]?.toIntOrNull()

        // A chat message is not a notification and has no id in that sequence,
        // so it cannot go through the poll's watermark: the two are unrelated
        // counters, and letting a chat id set the watermark would silence every
        // real notification below it. Negated to keep the banner ids apart —
        // message 41 and notification 41 are different events.
        if (notificationId == null) {
            val chatId = data["chat_message_id"]?.toIntOrNull() ?: return
            if (allows(category, settings)) postFromPush(-chatId, category, data)
            return
        }

        val (watermark, baselineDone) = preferences.pushWatermark()
        if (baselineDone && notificationId <= watermark) return

        if (!allows(category, settings)) {
            // Still advanced: leaving it behind means turning the category back
            // on replays its backlog, which is the same bug the poll had.
            preferences.setPushWatermark(maxOf(notificationId, watermark), baselineDone = true)
            return
        }

        postFromPush(notificationId, category, data)
        preferences.setPushWatermark(maxOf(notificationId, watermark), baselineDone = true)
    }

    @android.annotation.SuppressLint("MissingPermission")
    // `payload` rather than `data`: inside the Intent builder below, `data` is
    // the Intent's own property and the parameter would win.
    private fun postFromPush(id: Int, category: PushCategory, payload: Map<String, String>) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!hasSystemPermission() || !manager.areNotificationsEnabled()) return

        // The server already wrote the sentence, in the reader's own language
        // and with the site's own wording. Rewriting it here would be a second
        // place for it to be wrong.
        val title = payload["title"].orEmpty().ifBlank { appContext.getString(R.string.app_name) }
        val body = payload["body"].orEmpty()

        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = (payload["url"]?.takeIf { it.isNotBlank() } ?: DiscourseConfig.BASE_URL).toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            appContext,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val built = NotificationCompat.Builder(appContext, category.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setGroup(category.id)
            .setContentIntent(pending)
            .build()

        // Keyed on the notification id, like the poll's: the same event
        // arriving twice replaces its banner rather than adding one.
        runCatching { manager.notify(id, built) }
    }

    /**
     * A force-stop or an OEM task-killer cancels scheduled work, and
     * `setEnabled(true)` was the only thing that ever scheduled it — so push
     * stayed dead until the user found the switch and toggled it twice.
     * `KEEP` makes this idempotent and free.
     */
    suspend fun rescheduleIfEnabled() {
        if (!preferences.pushSettings.first().enabled) return
        // Registering again on every launch is close to free — the token is
        // cached by Firebase and the row is keyed on it — and it is what
        // repairs a device whose registration failed the first time, or whose
        // token rotated while the app was not running to hear about it.
        registerForPush()
    }

    fun cancelPolling() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
    }

    /**
     * The poll used to stay scheduled after sign-out, waking every 15 minutes
     * to find no credential and asking WorkManager to retry — forever. Anything
     * already in the shade belonged to the account that just left, too.
     */
    override suspend fun resetForSignOut() {
        unregisterForPush()
        cancelPolling()
        clearDeliveredNotifications()
    }

    /** Coming back to the foreground: the in-app badge supersedes the shade. */
    fun clearDeliveredNotifications() {
        NotificationManagerCompat.from(appContext).cancelAll()
    }

    /** What a pass concluded, which is not the same as whether it posted. */
    enum class PollResult {
        Completed,

        /** Nothing a retry can fix — no account, no permission, or switched off. */
        Disabled,

        /** The fetch failed; the next slot may do better. */
        Failed,
    }

    suspend fun poll(): PollResult {
        val settings = preferences.pushSettings.first()
        if (!settings.enabled || !hasSystemPermission()) return PollResult.Disabled
        // Deliberately not Disabled: SecureStore.get returns null on any
        // decrypt failure, so a momentarily unavailable Keystore reads as
        // "signed out" — and cancelling the schedule for that would kill push
        // until the next launch. A real sign-out already cancels it.
        if (!client.auth.isAuthenticated) return PollResult.Completed

        val notifications = runCatchingCancellable { client.notifications().notifications }
            .getOrNull() ?: return PollResult.Failed
        val (watermark, baselineDone) = preferences.pushWatermark()
        val newest = notifications.maxOfOrNull { it.id } ?: 0

        // The first completed pass establishes the baseline whether or not it
        // fetched anything. Gating this on `newest > watermark` meant an empty
        // inbox left baselineDone false — and the next pass that did return
        // something consumed itself setting the baseline, so those
        // notifications were never delivered at all.
        if (!baselineDone) {
            preferences.setPushWatermark(newest, baselineDone = true)
            return PollResult.Completed
        }

        selectFresh(notifications, watermark, settings).forEach { post(it) }

        // Advanced after posting, not before: a crash in between re-banners,
        // and notify() is keyed on the notification id, so a repeat replaces
        // rather than duplicates. Advancing past everything fetched —
        // silenced kinds included — is deliberate, or turning a category back
        // on would replay its backlog.
        if (newest > watermark) preferences.setPushWatermark(newest)
        return PollResult.Completed
    }


    @android.annotation.SuppressLint("MissingPermission")
    private fun post(notification: DiscourseNotification) {
        val manager = NotificationManagerCompat.from(appContext)
        // Both gates matter: the runtime grant and the per-app switch.
        if (!hasSystemPermission() || !manager.areNotificationsEnabled()) return

        val kind = NotificationFormatter.kind(notification.notificationType)
        val category = PushCategory.of(kind)
        val deepLink = NotificationFormatter.target(notification)?.let(::deepLinkUri)

        // Names the activity rather than describing it. setPackage() narrows an
        // implicit intent to this app but still needs a matching intent-filter
        // to resolve, which made every notification tap depend on the https
        // App Links filter — a link route, for something that never leaves the
        // app. MainActivity.handleIntent reads the data URI either way.
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = deepLink ?: DiscourseConfig.BASE_URL.toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            appContext,
            notification.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val built = NotificationCompat.Builder(appContext, category.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(NotificationFormatter.displayName(appContext, notification, kind))
            .setContentText(NotificationFormatter.text(appContext, notification, kind))
            .setAutoCancel(true)
            .setGroup(category.id)
            .setContentIntent(pending)
            .build()

        runCatching { manager.notify(notification.id, built) }
    }

    private fun deepLinkUri(target: NotificationTarget): Uri = when (target) {
        is NotificationTarget.Topic -> {
            val suffix = target.postNumber?.takeIf { it > 1 }?.let { "/$it" }.orEmpty()
            "${DiscourseConfig.BASE_URL}/t/topic/${target.topicId}$suffix".toUri()
        }
        is NotificationTarget.Chat -> {
            val suffix = target.messageId?.let { "/$it" }.orEmpty()
            "${DiscourseConfig.BASE_URL}/chat/c/-/${target.channelId}$suffix".toUri()
        }
        is NotificationTarget.Web -> target.url.toUri()
    }

    companion object {
        const val WORK_NAME = "nodeloc.notification-poll"

        /** WorkManager's own floor, and what this cost before push existed. */
        val POLL_INTERVAL: Duration = 15.minutes

        /** Once the server is delivering, the poll is only a net under it. */
        val BACKSTOP_INTERVAL: Duration = 6.hours

        /**
         * Chooses what to banner.
         *
         * A companion function because it needs nothing from the instance, and
         * because the watermark rule is the part that fails silently — a
         * dropped notification looks exactly like no notification.
         */
        fun selectFresh(
            notifications: List<DiscourseNotification>,
            watermark: Int,
            settings: AppPreferences.PushSettings,
        ): List<DiscourseNotification> = notifications
            .filter { !it.read && it.id > watermark }
            .filter { allows(it, settings) }
            .sortedBy { it.id }
            // Oldest first so banners stack in arrival order, capped so a long
            // gap doesn't produce a wall of them.
            .takeLast(5)

        private fun allows(
            notification: DiscourseNotification,
            settings: AppPreferences.PushSettings,
        ): Boolean = allows(PushCategory.of(NotificationFormatter.kind(notification.notificationType)), settings)

        /**
         * The same rule for a message that arrived as for one that was fetched.
         * Two copies of it would be two chances for a silenced category to
         * buzz through the path nobody was looking at.
         */
        fun allows(category: PushCategory, settings: AppPreferences.PushSettings): Boolean =
            when (category) {
                PushCategory.Replies -> settings.replies
                PushCategory.Likes -> settings.likes
                PushCategory.PrivateMessages -> settings.messages
                PushCategory.System -> settings.other
            }

        /**
         * What this device is willing to be woken for, told to the server so a
         * silenced category costs no delivery at all rather than one that is
         * thrown away on arrival.
         */
        fun enabledCategories(settings: AppPreferences.PushSettings): List<String> =
            PushCategory.entries.filter { allows(it, settings) }.map { it.id }
    }
}

/** The scheduled poll. Failures just wait for the next slot. */
class NotificationPollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val services = ServiceLocator.init(applicationContext)
        val outcome = runCatching { services.pushRepository.poll() }
            .getOrDefault(PushRepository.PollResult.Failed)
        return when (outcome) {
            PushRepository.PollResult.Completed -> Result.success()
            // retry() here would have WorkManager back off and wake the app
            // again, forever, for a condition no retry can change.
            PushRepository.PollResult.Disabled -> {
                services.pushRepository.cancelPolling()
                Result.success()
            }
            PushRepository.PollResult.Failed -> Result.retry()
        }
    }
}
