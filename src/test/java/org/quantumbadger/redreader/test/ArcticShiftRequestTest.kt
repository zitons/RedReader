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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.common.Result
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.http.FailedRequestBody
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.reddit.api.ArcticShiftRequest
import org.quantumbadger.redreader.reddit.kthings.JsonUtils
import org.quantumbadger.redreader.reddit.kthings.RedditThing
import org.quantumbadger.redreader.reddit.kthings.RedditThingResponse
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [28])
class ArcticShiftRequestTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun post(id: String, time: Long = 100) = JSONObject()
        .put("id", id).put("title", "Memory &amp; practice")
        .put("subreddit", "Anki").put("created_utc", time)
        .put("selftext_html", "&lt;p&gt;Read &lt;strong&gt;every day&lt;/strong&gt;.&lt;/p&gt;")
        .put("is_self", true).put("num_comments", 1).put("score", 4)

    private fun comment(id: String, replies: Any = "") = JSONObject().put("kind", "t1")
        .put("data", JSONObject().put("id", id).put("created_utc", 101)
            .put("body_html", "&lt;p&gt;Practice helps.&lt;/p&gt;")
            .put("parent_id", "t3_abc").put("link_id", "t3_abc").put("replies", replies))

    private class Network(val reply: (HTTPBackend.RequestDetails) -> JSONArray) : HTTPBackend() {
        val requests = mutableListOf<HTTPBackend.RequestDetails>()
        var cancelled = false
        val headers = mutableListOf<String>()
        override fun recreateHttpBackend() = Unit
        override fun resolveRedirectUri(context: Context, url: UriString): Result<UriString> =
            throw UnsupportedOperationException()
        override fun prepareRequest(context: Context, details: RequestDetails): Request {
            requests.add(details)
            return object : Request {
                override fun cancel() { cancelled = true }
                override fun addHeader(name: String, value: String) { headers.add(name) }
                override fun executeInThisThread(listener: Listener) {
                    val text = JSONObject().put("data", reply(details)).toString()
                    listener.onSuccess("application/json", null, text.byteInputStream())
                }
            }
        }
    }

    private class Response : HTTPBackend.Listener {
        var text: String? = null
        var error: Throwable? = null
        override fun onError(failureType: CacheRequest.RequestFailureType, exception: Throwable?,
                             httpStatus: Int?, body: FailedRequestBody?) {
            error = exception ?: AssertionError("HTTP $httpStatus")
        }
        override fun onSuccess(mimetype: String?, bodyBytes: Long?, body: InputStream?) {
            text = body!!.bufferedReader().use { it.readText() }
        }
        fun parsed(): RedditThingResponse {
            error?.let { throw AssertionError("Archive request failed", it) }
            return JsonUtils.decodeRedditThingResponseFromStream(text!!.byteInputStream())
        }
    }

    @Test fun listsAndPaginatesWithoutDroppingPostsAtTimestampBoundary() {
        val rows = JSONArray()
        for (id in 100..140) rows.put(post(id.toString(36), 500))
        val network = Network { rows }
        val first = Response()
        ArcticShiftRequest(context, UriString("https://oauth.reddit.com/r/Anki/new.json"),
            false, network).executeInThisThread(first)
        val listing = (first.parsed() as RedditThingResponse.Single).thing as RedditThing.Listing
        assertEquals(25, listing.data.children.size)
        val value = (listing.data.children.first().ok() as RedditThing.Post).data
        assertEquals("Memory & practice", value.title!!.decoded)
        assertTrue(value.selftext_html!!.decoded.contains("<strong>every day</strong>"))
        val second = Response()
        val uri = "https://oauth.reddit.com/r/Anki/new.json?after=${listing.data.after}"
        ArcticShiftRequest(context, UriString(uri), false, network).executeInThisThread(second)
        val next = (second.parsed() as RedditThingResponse.Single).thing as RedditThing.Listing
        assertEquals(16, next.data.children.size)
        val firstIds = listing.data.children.map { (it.ok() as RedditThing.Post).data.id }.toSet()
        assertTrue(next.data.children.none { (it.ok() as RedditThing.Post).data.id in firstIds })
        assertEquals("501", network.requests.last().url.toUri().getQueryParameter("before"))
    }

    @Test fun threadKeepsPostHtmlNestedCommentsAndMoreLinks() {
        val nested = JSONObject().put("kind", "Listing").put("data", JSONObject()
            .put("children", JSONArray().put(comment("def"))))
        val more = JSONObject().put("kind", "more").put("data", JSONObject()
            .put("id", "more").put("name", "more").put("parent_id", "t3_abc")
            .put("count", 1).put("children", JSONArray().put("ghi")))
        val network = Network {
            if (it.url.toUri().path!!.endsWith("posts/ids")) JSONArray().put(post("abc"))
            else JSONArray().put(comment("xyz", nested)).put(more)
        }
        val response = Response()
        val request = ArcticShiftRequest(context,
            UriString("https://oauth.reddit.com/comments/abc/.json"), false, network)
        request.addHeader("Authorization", "must-stay-local")
        request.executeInThisThread(response)
        val parsed = response.parsed() as RedditThingResponse.Multiple
        assertEquals(2, parsed.things.size)
        val comments = parsed.things[1] as RedditThing.Listing
        assertEquals("xyz", (comments.data.children[0].ok() as RedditThing.Comment).data.id)
        assertTrue(comments.data.children[1].ok() is RedditThing.More)
        assertTrue(network.headers.isEmpty())
        assertTrue(network.requests.all { it.url.toUri().host == "arctic-shift.photon-reddit.com" })
        val json = JSONArray(response.text)
        assertEquals("t1_def", json.getJSONObject(1).getJSONObject("data")
            .getJSONArray("children").getJSONObject(0).getJSONObject("data")
            .getJSONObject("replies").getJSONObject("data").getJSONArray("children")
            .getJSONObject(0).getJSONObject("data").getString("name"))
    }

    @Test fun moreCommentPermalinkSelectsArchivedThread() {
        val network = Network {
            if (it.url.toUri().path!!.endsWith("posts/ids")) JSONArray().put(post("abc"))
            else JSONArray()
        }
        val response = Response()
        ArcticShiftRequest(context,
            UriString("https://oauth.reddit.com/comments/abc/comment/def/.json"),
            false, network).executeInThisThread(response)
        response.parsed()
        assertEquals("t1_def", network.requests.last().url.toUri().getQueryParameter("parent_id"))
    }

    @Test fun cancellationAndWritesDoNotSendNetworkRequests() {
        val network = Network { throw AssertionError("Unexpected network request") }
        val cancelled = ArcticShiftRequest(context,
            UriString("https://oauth.reddit.com/r/Anki/new.json"), false, network)
        cancelled.cancel()
        val response = Response()
        cancelled.executeInThisThread(response)
        assertNull(response.text)
        assertNull(response.error)
        ArcticShiftRequest(context, UriString("https://oauth.reddit.com/api/vote"),
            true, network).executeInThisThread(response)
        assertNotNull(response.error)
    }

    @Test fun combinedFeedFetchesEachCommunityAndMergesChronologically() {
        val network = Network {
            val sub = it.url.toUri().getQueryParameter("subreddit")
            if (sub == "Anki") JSONArray().put(post("abc", 100))
            else JSONArray().put(post("def", 200))
        }
        val response = Response()
        ArcticShiftRequest(context, UriString("https://oauth.reddit.com/r/Anki+rust/new.json"),
            false, network).executeInThisThread(response)
        val listing = (response.parsed() as RedditThingResponse.Single).thing as RedditThing.Listing
        assertEquals("def", (listing.data.children[0].ok() as RedditThing.Post).data.id)
        assertEquals(2, network.requests.size)
    }
}
