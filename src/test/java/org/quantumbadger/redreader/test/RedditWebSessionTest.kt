/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.test

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.common.Result
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.reddit.api.RedditWebSession
import org.quantumbadger.redreader.reddit.api.RedditWebSessionRequest
import org.quantumbadger.redreader.reddit.url.SubredditPostListURL
import org.quantumbadger.redreader.reddit.PostSort
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [28])
class RedditWebSessionTest {
    private fun rewrite(path: String) = RedditWebSession.rewrite(
        UriString("https://oauth.reddit.com$path")).toUri()

    @Test fun preservesRealSortsTimeRangeAndPaging() {
        for (sort in listOf("new", "top", "controversial", "rising", "best")) {
            val result = rewrite("/r/Anki/$sort/.json?t=week&after=t3_abc&limit=25")
            assertEquals("/r/Anki/$sort.json", result.path)
            assertEquals("week", result.getQueryParameter("t"))
            assertEquals("t3_abc", result.getQueryParameter("after"))
            assertEquals("25", result.getQueryParameter("limit"))
            assertEquals("www.reddit.com", result.host)
            assertNull(result.getQueryParameter("sort"))
        }
        assertEquals("/r/Anki.json", rewrite("/r/Anki/hot/.json").path)
    }

    @Test fun homeAndCommentUrlsUseTheSameSessionWithoutChangingCommentSort() {
        assertEquals("/.json", rewrite("/hot/.json").path)
        assertEquals("/best.json", rewrite("/best/.json").path)
        val comments = rewrite("/comments/abc/.json?sort=new&context=3")
        assertEquals("/comments/abc.json", comments.path)
        assertEquals("new", comments.getQueryParameter("sort"))
        // Exercise the actual home URL used by the existing main-menu entry.
        val home = SubredditPostListURL.getFrontPage().sort(PostSort.HOT).generateJsonUri()
        assertEquals("/.json", RedditWebSession.rewrite(UriString.from(home)).toUri().path)
    }

    @Test fun acceptsHeaderOrValueButDropsUnneededCookies() {
        assertEquals("reddit_session=test.value", RedditWebSession.normalizeCookie("test.value"))
        assertEquals("reddit_session=test.value", RedditWebSession.normalizeCookie(
            "Cookie: csrf_token=unused; reddit_session=test.value; token_v2=unused"))
        for (input in listOf("", "token_v2=unused", "test\r\nInjected: yes", "a b")) {
            assertThrows(IllegalArgumentException::class.java) {
                RedditWebSession.normalizeCookie(input)
            }
        }
    }

    @Test fun refusesOtherOriginsBeforeSendingCredentials() {
        for (url in listOf("https://images.example/image.json",
            "http://oauth.reddit.com/r/Anki.json", "https://oauth.reddit.com:8443/.json",
            "https://user@oauth.reddit.com/.json")) {
            assertThrows(IllegalArgumentException::class.java) {
                RedditWebSession.rewrite(UriString(url))
            }
        }
    }

    private class Network : HTTPBackend() {
        val requests = mutableListOf<RequestDetails>()
        val headers = mutableMapOf<String, String>()
        var cancelled = false
        override fun recreateHttpBackend() = Unit
        override fun resolveRedirectUri(context: Context, url: UriString): Result<UriString> =
            throw UnsupportedOperationException()
        override fun prepareRequest(context: Context, details: RequestDetails): Request {
            requests.add(details)
            return object : Request {
                override fun executeInThisThread(listener: Listener) = Unit
                override fun cancel() { cancelled = true }
                override fun addHeader(name: String, value: String) { headers[name] = value }
            }
        }
    }

    @Test fun readOnlyAdapterDisablesRedirectsAndNeverAddsOAuth() {
        val network = Network()
        val context = RuntimeEnvironment.getApplication()
        val url = UriString("https://oauth.reddit.com/r/Anki/hot/.json")
        val config = RedditWebSession.Config("reddit_session=synthetic-test", "account-one")
        val request = RedditWebSessionRequest(context, url, false, config, network)
        request.addHeader("Authorization", "must-not-be-forwarded")
        assertFalse(network.requests.single().followRedirects)
        assertEquals(config.cookie, network.headers["Cookie"])
        assertFalse(network.headers.containsKey("Authorization"))
        assertFalse(network.requests.single().url.value.contains("synthetic-test"))
        request.cancel()
        assertTrue(network.cancelled)
        val other = RedditWebSession.Config("reddit_session=other-test", "account-two")
        assertNotEquals(RedditWebSession.cacheUrl(url, config),
            RedditWebSession.cacheUrl(url, other))
        assertThrows(IllegalArgumentException::class.java) {
            RedditWebSessionRequest(context, url, true, config, network)
        }
        assertEquals(1, network.requests.size)
    }
}
