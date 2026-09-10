package com.nodeloc.app.core.store

import com.nodeloc.app.core.network.DiscourseAuth
import com.nodeloc.app.core.network.DiscourseClient
import com.nodeloc.app.core.network.InMemoryCookieStorage
import com.nodeloc.app.core.network.PersistentCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The category map is read by nearly every screen and refetched from several,
 * so the ways it can go empty matter more than the ways it fills.
 */
class SiteRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: SiteRepository

    /** Two nodes under one parent — the shape `slugPath` exists to walk. */
    private val siteJson = """
        {
          "categories": [
            {
              "id": 1, "name": "Parent", "slug": "parent",
              "subcategory_list": [
                {"id": 10, "name": "Alpha", "slug": "alpha", "parent_category_id": 1},
                {"id": 11, "name": "Beta", "slug": "beta", "parent_category_id": 1}
              ]
            },
            {"id": 2, "name": "Loose", "slug": "loose"}
          ]
        }
    """.trimIndent()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        repository = SiteRepository(
            DiscourseClient(
                clientId = "test-client",
                auth = DiscourseAuth(),
                cookieJar = PersistentCookieJar(InMemoryCookieStorage()),
                baseUrl = server.url("/").toString().trimEnd('/'),
            ),
        )
    }

    @After
    fun stop() = server.close()

    private fun enqueueSite(count: Int = 1) = repeat(count) {
        server.enqueue(MockResponse.Builder().body(siteJson).build())
    }

    @Test
    fun `subcategories are flattened into the map`() = runBlocking {
        enqueueSite()
        repository.siteResponse()

        assertEquals(setOf(1, 2, 10, 11), repository.categories().keys)
    }

    @Test
    fun `the response is fetched once and then cached`() = runBlocking {
        enqueueSite()
        repository.siteResponse()
        repository.siteResponse()

        assertEquals(1, server.requestCount)
    }

    /** The mutex is there so a cold feed does not fetch `site.json` per row. */
    @Test
    fun `concurrent first calls collapse into one request`() = runBlocking {
        enqueueSite()

        (1..5).map { async(Dispatchers.IO) { repository.siteResponse() } }.awaitAll()

        assertEquals(1, server.requestCount)
    }

    /**
     * The regression this guards: `refresh()` used to clear the map before
     * refetching, so one failed refetch — a deep link followed offline — left
     * every screen with no categories for the rest of the process.
     */
    @Test
    fun `a failed refresh keeps the categories it already had`() = runBlocking {
        enqueueSite()
        repository.siteResponse()
        server.enqueue(MockResponse.Builder().code(500).body("nope").build())

        repository.refresh()

        assertEquals(setOf(1, 2, 10, 11), repository.categories().keys)
    }

    @Test
    fun `signing out empties the map`() = runBlocking {
        enqueueSite()
        repository.siteResponse()

        repository.resetForSignOut()

        assertEquals(emptyMap<Int, Any>(), repository.categories())
        assertNull(repository.settings)
    }

    @Test
    fun `slugPath prefixes a subcategory with its parent`() = runBlocking {
        enqueueSite()
        repository.siteResponse()

        assertEquals("parent/alpha", repository.slugPath(10))
        assertEquals("loose", repository.slugPath(2))
        assertNull(repository.slugPath(999))
    }

    /**
     * What routes a bare `/c/{slug}` link natively. It has to reach nested
     * nodes, because every node on this site is a subcategory.
     */
    @Test
    fun `categoryBySlug finds nested nodes, ignoring case`() = runBlocking {
        enqueueSite()
        repository.siteResponse()

        assertEquals(10, repository.categoryBySlug("alpha")?.id)
        assertEquals(10, repository.categoryBySlug("ALPHA")?.id)
        assertEquals(2, repository.categoryBySlug("loose")?.id)
        assertNull(repository.categoryBySlug("nope"))
    }

    /** A failed first fetch must not be cached as "the site has no nodes". */
    @Test
    fun `a failed first fetch is retried, not remembered`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).body("nope").build())
        assertNull(repository.siteResponse())

        enqueueSite()
        assertNotNull(repository.siteResponse())
        assertEquals(setOf(1, 2, 10, 11), repository.categories().keys)
    }
}
