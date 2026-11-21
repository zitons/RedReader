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

package org.quantumbadger.redreader.reddit.api

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.http.FailedRequestBody
import org.quantumbadger.redreader.http.HTTPBackend
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Adapts archive responses to the Reddit JSON consumed by the native reader. */
class ArcticShiftRequest(
    private val context: Context,
    private val original: UriString,
    private val hasBody: Boolean,
    private val backend: HTTPBackend = HTTPBackend.backend
) : HTTPBackend.Request {
    @Volatile private var cancelled = false
    @Volatile private var active: HTTPBackend.Request? = null

    override fun cancel() {
        cancelled = true
        active?.cancel()
    }

    override fun addHeader(name: String, value: String) {
        // Reddit credentials and server keys must not be forwarded to the archive.
    }

    override fun executeInThisThread(listener: HTTPBackend.Listener) {
        try {
            if (hasBody) throw IOException("Arctic Shift supports archive reading only")
            val response = load()
            if (cancelled) return
            val bytes = response.toString().toByteArray(Charsets.UTF_8)
            listener.onSuccess("application/json", bytes.size.toLong(), ByteArrayInputStream(bytes))
        } catch (error: ArchiveFailure) {
            if (!cancelled) listener.onError(error.type, error.cause, error.status, error.body)
        } catch (error: Exception) {
            if (!cancelled) listener.onError(
                CacheRequest.RequestFailureType.CONNECTION, error, null, null)
        }
    }

    private fun fetch(endpoint: String, params: Map<String, String>): JSONArray {
        if (cancelled) throw IOException("Cancelled")
        val uri = Uri.parse(BASE + endpoint).buildUpon()
        params.forEach { (key, value) -> uri.appendQueryParameter(key, value) }
        uri.appendQueryParameter("md2html", "true")
        val request = backend.prepareRequest(context,
            HTTPBackend.RequestDetails(UriString.from(uri), null))
        active = request
        if (cancelled) { request.cancel(); throw IOException("Cancelled") }
        var result: JSONArray? = null
        var failure: Exception? = null
        request.executeInThisThread(object : HTTPBackend.Listener {
            override fun onError(failureType: CacheRequest.RequestFailureType, exception: Throwable?,
                                 httpStatus: Int?, body: FailedRequestBody?) {
                failure = ArchiveFailure(failureType, exception, httpStatus, body)
            }
            override fun onSuccess(mimetype: String?, bodyBytes: Long?, body: InputStream?) {
                try {
                    val text = body?.use { General.readWholeStreamAsUTF8(it) }
                        ?: throw IOException("Arctic Shift returned an empty body")
                    result = JSONObject(text).getJSONArray("data")
                } catch (error: Exception) { failure = error }
            }
        })
        failure?.let { throw it }
        return result ?: throw IOException("Arctic Shift returned no data")
    }

    private fun load(): Any {
        val uri = original.toUri()
        require(uri.host == "oauth.reddit.com") { "Expected a Reddit API URL" }
        val segments = uri.pathSegments.map { it.removeSuffix(".json") }.filter { it.isNotEmpty() }
        val comments = segments.indexOf("comments")
        if (comments >= 0 && comments + 1 < segments.size) {
            val id = segments[comments + 1]
            require(id.matches(Regex("[a-zA-Z0-9]+")))
            val posts = fetch("posts/ids", mapOf("ids" to id))
            if (posts.length() == 0) throw IOException("This post has not been archived yet")
            val params = mutableMapOf("link_id" to "t3_$id", "limit" to "1000",
                "start_breadth" to "1000", "start_depth" to "100")
            // Comment permalinks put the focused comment after the post slug.
            segments.getOrNull(comments + 3)?.takeIf { it.isNotEmpty() }?.let {
                params["parent_id"] = "t1_$it"
            }
            val replies = fetch("comments/tree", params)
            normalizeComments(replies)
            sortComments(replies, uri.getQueryParameter("sort") ?: "best")
            return JSONArray().put(postsListing(posts, null)).put(listing(replies))
        }
        if (segments.firstOrNull() == "api" && segments.getOrNull(1) == "info") {
            val ids = uri.getQueryParameter("id") ?: ""
            return postsListing(fetch("posts/ids", mapOf("ids" to ids)), null)
        }
        val subreddit = if (segments.firstOrNull() == "r") segments.getOrNull(1) else null
        if (segments.lastOrNull() == "about" && subreddit != null) {
            val rows = fetch("subreddits/search", mapOf("subreddit" to subreddit, "limit" to "1"))
            if (rows.length() == 0) throw IOException("Subreddit is not in the archive")
            return JSONObject().put("kind", "t5").put("data", rows.getJSONObject(0))
        }
        if (segments.firstOrNull() == "subreddits") {
            val params = mutableMapOf("limit" to "50")
            uri.getQueryParameter("q")?.let { params["subreddit_prefix"] = it }
            val rows = fetch("subreddits/search", params)
            val children = JSONArray()
            for (i in 0 until rows.length()) children.put(
                JSONObject().put("kind", "t5").put("data", rows.getJSONObject(i)))
            return listing(children)
        }
        val user = if (segments.firstOrNull() in listOf("user", "u"))
            segments.getOrNull(1) else null
        if (subreddit == null && user == null && segments.firstOrNull() !in
            listOf(null, "", "hot", "new", "top", "rising", "best", "search")) {
            throw IOException("This feature is unavailable in Arctic Shift archive mode")
        }
        val params = mutableMapOf("limit" to "100", "sort" to "desc")

        if (user != null) params["author"] = user
        uri.getQueryParameter("q")?.takeIf { it.isNotBlank() }?.let { params["query"] = it }
        val after = uri.getQueryParameter("after")
        val cursor = after?.takeIf { it.startsWith("as_") }?.removePrefix("as_")
        if (cursor != null) params["before"] = (cursor.substringBefore('_').toLong() + 1).toString()
        val rows = JSONArray()
        val communities = subreddit?.takeUnless { it in listOf("all", "popular") }
            ?.split('+') ?: listOf(null)
        require(communities.size <= 20) { "Archive feeds support up to 20 subreddits" }
        for (community in communities) {
            if (community != null) params["subreddit"] = community
            val page = fetch("posts/search", params)
            for (i in 0 until page.length()) rows.put(page.getJSONObject(i))
        }
        return postsListing(rows, cursor)
    }

    private class ArchiveFailure(val type: CacheRequest.RequestFailureType, cause: Throwable?,
                                 val status: Int?, val body: FailedRequestBody?) : IOException(cause)

    companion object {
        const val BASE = "https://arctic-shift.photon-reddit.com/api/"
        @JvmStatic fun enabled() = PrefsUtility.getString(R.string.pref_read_source_key,
            "arctic") == "arctic"

        private fun listing(children: JSONArray, after: String? = null) = JSONObject()
            .put("kind", "Listing").put("data", JSONObject().put("children", children)
                .put("after", after ?: JSONObject.NULL).put("before", JSONObject.NULL))

        internal fun postsListing(rows: JSONArray, cursor: String?): JSONObject {
            val selected = (0 until rows.length()).map { rows.getJSONObject(it) }
                .sortedWith(compareByDescending<JSONObject> { it.optLong("created_utc") }
                    .thenByDescending { it.getString("id").toLong(36) })
                .filter {
                    cursor == null || it.optLong("created_utc") < cursor.substringBefore('_').toLong()
                        || (it.optLong("created_utc") == cursor.substringBefore('_').toLong()
                        && it.getString("id").toLong(36) < cursor.substringAfter('_').toLong(36))
                }.take(25)
            val children = JSONArray()
            for (post in selected) {
                val id = post.getString("id")
                post.put("name", "t3_$id")
                if (post.isNull("score")) post.put("score", 0)
                if (post.isNull("num_comments")) post.put("num_comments", 0)
                if (post.isNull("permalink")) post.put("permalink",
                    "/r/${post.getString("subreddit")}/comments/$id/")
                children.put(JSONObject().put("kind", "t3").put("data", post))
            }
            val last = selected.lastOrNull()
            val after = if (last != null && selected.size == 25)
                "as_${last.optLong("created_utc")}_${last.getString("id")}" else null
            return listing(children, after)
        }

        internal fun normalizeComments(children: JSONArray) {
            for (i in 0 until children.length()) {
                val thing = children.getJSONObject(i)
                if (thing.optString("kind") != "t1") continue
                val data = thing.getJSONObject("data")
                data.put("name", "t1_${data.getString("id")}")
                val replies = data.optJSONObject("replies")?.optJSONObject("data")
                    ?.optJSONArray("children")
                if (replies != null) normalizeComments(replies)
            }
        }

        private fun sortComments(children: JSONArray, sort: String) {
            val items = (0 until children.length()).map { children.getJSONObject(it) }
            val sorted = when (sort) {
                "new" -> items.sortedByDescending { it.optJSONObject("data")?.optLong("created_utc") }
                "old" -> items.sortedBy { it.optJSONObject("data")?.optLong("created_utc") }
                else -> items.sortedByDescending { it.optJSONObject("data")?.optInt("score") }
            }
            sorted.forEachIndexed { index, thing ->
                children.put(index, thing)
                thing.optJSONObject("data")?.optJSONObject("replies")?.optJSONObject("data")
                    ?.optJSONArray("children")?.let { sortComments(it, sort) }
            }
        }
    }
}
