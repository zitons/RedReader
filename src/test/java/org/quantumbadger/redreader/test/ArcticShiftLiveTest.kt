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

import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.reddit.api.ArcticShiftRequest
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.http.FailedRequestBody
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.reddit.kthings.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [28])
class ArcticShiftLiveTest {
    @Test fun liveNormalDownloadPath() {
        val proxy = System.getenv("REDREADER_ARCTIC_TEST_PROXY")
        assumeTrue("Live archive check requires REDREADER_ARCTIC_TEST_PROXY", proxy != null)
        val proxyUri = android.net.Uri.parse(proxy)
        val context = RuntimeEnvironment.getApplication()
        val info = PackageInfo()
        info.packageName = context.packageName
        info.versionName = "1.27"
        info.signatures = emptyArray<Signature>()
        Shadows.shadowOf(context.packageManager).installPackage(info)
        PrefsUtility.init(context)
        System.setProperty("https.proxyHost", proxyUri.host!!)
        System.setProperty("https.proxyPort", proxyUri.port.toString())
        try {
            for (path in listOf("/r/Anki/new.json", "/comments/1xxmkwc/.json")) {
                var parsed: RedditThingResponse? = null
                var failure: Throwable? = null
                ArcticShiftRequest(context, UriString("https://oauth.reddit.com" + path),
                    false, HTTPBackend.backend).executeInThisThread(object : HTTPBackend.Listener {
                    override fun onSuccess(mimetype: String?, bodyBytes: Long?, body: InputStream?) {
                        parsed = body!!.use { JsonUtils.decodeRedditThingResponseFromStream(it) }
                    }
                    override fun onError(failureType: CacheRequest.RequestFailureType,
                        exception: Throwable?, httpStatus: Int?, body: FailedRequestBody?) {
                        failure = AssertionError("HTTP $httpStatus, $failureType", exception)
                    }
                })
                failure?.let { throw it }
                assertNotNull(parsed)
                val things = when(val response = parsed!!) {
                    is RedditThingResponse.Single -> listOf(response.thing)
                    is RedditThingResponse.Multiple -> response.things
                }
                for (thing in things) {
                    val rows = (thing as RedditThing.Listing).data.children
                    assertTrue("No rows for $path", rows.isNotEmpty())
                    rows.forEach { it.ok() }
                    println("LIVE $path parsed rows=${rows.size}")
                }
            }
        } finally {
            System.clearProperty("https.proxyHost")
            System.clearProperty("https.proxyPort")
        }
    }
}
