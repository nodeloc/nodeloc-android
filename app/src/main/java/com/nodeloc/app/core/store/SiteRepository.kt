package com.nodeloc.app.core.store

import com.nodeloc.app.core.model.AuthProvider
import com.nodeloc.app.core.model.UserFieldDefinition
import com.nodeloc.app.core.model.CustomBadgeStyle
import com.nodeloc.app.core.model.DiscourseCategory
import com.nodeloc.app.core.model.DiscourseSiteSettings
import com.nodeloc.app.core.model.EmojiGroup
import com.nodeloc.app.core.model.PostActionType
import com.nodeloc.app.core.model.SiteResponse
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.model.VoteFace
import com.nodeloc.app.core.network.DiscourseClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session-level cache for `site.json` and the category tree.
 *
 * Everything that renders a post needs the node it belongs to, so this would
 * otherwise be fetched dozens of times on a cold feed. The mutex collapses
 * concurrent first calls into one request rather than racing.
 */
class SiteRepository(private val client: DiscourseClient) : AccountScoped {
    private val mutex = Mutex()

    @Volatile private var site: SiteResponse? = null
    @Volatile private var categoriesById: Map<Int, DiscourseCategory> = emptyMap()

    @Volatile private var titleStyles: Map<String, CustomBadgeStyle> = emptyMap()
    private val titleMutex = Mutex()

    @Volatile private var emojiGroups: List<EmojiGroup> = emptyList()
    private val emojiMutex = Mutex()

    val settings: DiscourseSiteSettings? get() = site?.siteSettings

    /**
     * Where discourse-vote folds a post, or null when voting is off. Empty
     * until [siteResponse] has run, which is why a screen reads it after its
     * own load rather than at construction.
     */
    val voteCollapseThreshold: Int? get() = site?.voteCollapseScoreThreshold

    /**
     * The faces an arrow offers, paired with the image each one draws as.
     *
     * The site names the reactions and `emojis.json` knows where their pictures
     * live, so this is the join of the two — and a reaction with no emoji
     * behind it is dropped rather than drawn as a gap.
     */
    suspend fun voteFaces(direction: VoteDirection): List<VoteFace> {
        val names = when (direction) {
            VoteDirection.Up -> site?.voteUpvoteReactions
            VoteDirection.Down -> site?.voteDownvoteReactions
            VoteDirection.None -> null
        }.orEmpty()
        if (names.isEmpty()) return emptyList()
        val urls = emojiUrls()
        return names.mapNotNull { name -> urls[name]?.let { VoteFace(name, it) } }
    }

    /** Where one emoji's picture lives, by the name a reaction is called by. */
    suspend fun emojiUrl(name: String): String? = emojiUrls()[name]

    private suspend fun emojiUrls(): Map<String, String> =
        emojiGroups().flatMap { it.emojis }.associate { it.name to it.url }

    /** Sign-in providers the site offers; empty until [siteResponse] has run. */
    fun authProviders(): List<AuthProvider> = site?.authProviders.orEmpty()

    /** The fields the signup form has to collect, in the site's own order. */
    fun signupUserFields(): List<UserFieldDefinition> =
        site?.userFields.orEmpty().filter { it.showOnSignup }

    /** Cached categories; empty until [siteResponse] has run at least once. */
    fun categories(): Map<Int, DiscourseCategory> = categoriesById

    fun category(id: Int?): DiscourseCategory? = id?.let { categoriesById[it] }

    /**
     * The flag reasons that apply to a post, in the order the site lists them.
     *
     * The same table carries the like, and several of its rows are for topics
     * or chat messages only — a reason offered against a post it does not apply
     * to is refused by the server, so the filter is not cosmetic.
     */
    fun postFlagTypes(): List<PostActionType> =
        site?.postActionTypes.orEmpty()
            .filter { it.isFlag && it.enabled && "Post" in it.appliesTo }
            .sortedBy { it.position }

    suspend fun siteResponse(): SiteResponse? {
        site?.let { return it }
        return mutex.withLock {
            site ?: runCatching { client.site() }.getOrNull()?.also { response ->
                site = response
                categoriesById = flatten(response.categories.orEmpty()).associateBy { it.id }
            }
        }
    }

    /**
     * Forces a refetch — after joining or leaving a node changes membership, or
     * when a category id is not in the cache yet.
     *
     * Swaps the new response in only once it has arrived. Clearing first meant
     * every other screen read an empty category map for the length of a network
     * call — the reader losing its node name mid-load, `slugPath` resolving a
     * parent to nothing and building a wrong URL — and if the refetch failed,
     * the map stayed empty for the rest of the process.
     */
    suspend fun refresh() {
        val response = runCatching { client.site() }.getOrNull() ?: return
        mutex.withLock {
            site = response
            categoriesById = flatten(response.categories.orEmpty()).associateBy { it.id }
        }
    }

    /**
     * `site.json` is per-account despite the name — which categories this user
     * belongs to, and what they may do in them — so none of it may outlive the
     * account it described.
     */
    override suspend fun resetForSignOut() {
        mutex.withLock {
            site = null
            categoriesById = emptyMap()
        }
        titleMutex.withLock { titleStyles = emptyMap() }
    }

    /**
     * The emoji list, fetched once per session.
     *
     * Group order is the server's, which puts the Unicode sets first and the
     * site's own uploads after them — the order the web picker shows and the
     * one people here already know.
     */
    suspend fun emojiGroups(): List<EmojiGroup> {
        emojiGroups.takeIf { it.isNotEmpty() }?.let { return it }
        return emojiMutex.withLock {
            emojiGroups.takeIf { it.isNotEmpty() } ?: run {
                val fetched = runCatching { client.emojis() }.getOrNull().orEmpty()
                    .map { (group, list) ->
                        EmojiGroup(group, list.filter { it.name.isNotBlank() && it.url.isNotBlank() })
                    }
                    .filter { it.emojis.isNotEmpty() }
                emojiGroups = fetched
                fetched
            }
        }
    }

    /**
     * The node's `/c/{parent}/{child}/{id}` path. Nodes are subcategories here,
     * so the parent slug is required; a top-level category resolves to itself.
     */
    fun slugPath(category: DiscourseCategory): String {
        val parent = category.parentCategoryId?.let { categoriesById[it] }
        return if (parent != null) "${parent.slug}/${category.slug}" else category.slug
    }

    fun slugPath(categoryId: Int): String? = categoriesById[categoryId]?.let { slugPath(it) }

    /** Resolves a node by slug — needed to route `/c/...` and `/n/...` links. */
    fun categoryBySlug(slug: String): DiscourseCategory? =
        categoriesById.values.firstOrNull { it.slug.equals(slug, ignoreCase = true) }

    private fun flatten(categories: List<DiscourseCategory>): List<DiscourseCategory> =
        categories.flatMap { listOf(it) + flatten(it.subcategoryList.orEmpty()) }

    // --------------------------------------------------------- title styles

    /**
     * discourse-custom-badge styles, keyed by the group/badge title they apply
     * to. Fetched once per session; a site without the plugin just yields an
     * empty map and every title renders plain.
     */
    suspend fun titleStyle(title: String?): CustomBadgeStyle? {
        if (title.isNullOrEmpty()) return null
        if (titleStyles.isEmpty()) loadTitleStyles()
        return titleStyles[title]
    }

    suspend fun loadTitleStyles() {
        titleMutex.withLock {
            if (titleStyles.isNotEmpty()) return
            val merged = mutableMapOf<String, CustomBadgeStyle>()
            runCatching { client.customGroupStyles() }.getOrNull()?.forEach { item ->
                val style = item.customGroupStyle ?: return@forEach
                listOfNotNull(item.title, item.fullName, item.name).forEach { key -> merged[key] = style }
            }
            runCatching { client.customBadgeStyles() }.getOrNull()?.forEach { item ->
                val style = item.customStyle ?: return@forEach
                item.name?.let { merged[it] = style }
            }
            titleStyles = merged
        }
    }
}
