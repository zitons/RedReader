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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.http.HTTPBackend
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A user-supplied web session, independent of RedReader's OAuth accounts. */
object RedditWebSession {
    private const val KEY_ALIAS = "reddit-web-session-v1"

    // Deliberately not a data class: toString() must never expose the cookie.
    class Config(val cookie: String, val scope: String)

    @JvmStatic fun enabled(): Boolean =
        PrefsUtility.getString(R.string.pref_read_source_key, "arctic") == "web"

    private fun file(context: Context) =
        AtomicFile(File(context.noBackupFilesDir, "reddit-web-session"))

    private fun key(): SecretKey {
        check(android.os.Build.VERSION.SDK_INT >= 23) { "Android 6 or newer required" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }

    /** Accept either the session value or a Cookie header; retain only reddit_session. */
    @JvmStatic fun normalizeCookie(input: String): String {
        require(!input.contains('\r') && !input.contains('\n')) { "Invalid cookie" }
        val text = input.trim().removePrefix("Cookie:").removePrefix("cookie:").trim()
        val value = if (text.contains('=')) {
            text.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("reddit_session=") }
                ?.substringAfter('=') ?: throw IllegalArgumentException("Missing reddit_session")
        } else text
        require(value.isNotEmpty() && value.length <= 16384 &&
            value.matches(Regex("[A-Za-z0-9._~%+-]+"))) { "Invalid reddit_session" }
        return "reddit_session=$value"
    }

    @JvmStatic @Synchronized fun save(context: Context, input: String) {
        val cookie = normalizeCookie(input)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = JSONObject().put("cookie", cookie)
            .put("scope", UUID.randomUUID().toString()).toString()
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val bytes = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString().toByteArray(Charsets.UTF_8)
        val target = file(context)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    @JvmStatic @Synchronized fun configured(context: Context): Config {
        return try {
            val envelope = JSONObject(String(file(context).readFully(), Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128,
                Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
            val payload = JSONObject(String(cipher.doFinal(
                Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
            Config(normalizeCookie(payload.getString("cookie")), payload.getString("scope"))
        } catch (_: Exception) {
            Config("", "unconfigured")
        }
    }

    @JvmStatic @Synchronized fun clear(context: Context) { file(context).delete() }

    @JvmStatic fun cacheUrl(url: UriString, config: Config): UriString =
        UriString.from(url.toUri().buildUpon().fragment("reddit-web-${config.scope}"))

    @JvmStatic fun rewrite(original: UriString): UriString {
        val uri = original.toUri()
        require(uri.scheme == "https" && uri.host == "oauth.reddit.com" &&
            uri.userInfo == null && uri.port == -1) { "Expected a Reddit API URL" }
        var path = uri.encodedPath.orEmpty().replace(Regex("/\\.json$"), ".json")
        if (path.isEmpty() || path == ".json") path = "/.json"
        // The base listing is hot. Other sorts MUST keep their actual path: the web
        // endpoint may silently ignore ?sort=new/top/etc and return hot instead.
        if (path == "/hot.json") path = "/.json"
        if (Regex("/r/[^/]+/hot\\.json").matches(path)) {
            path = path.removeSuffix("/hot.json") + ".json"
        }
        return UriString.from(uri.buildUpon().authority("www.reddit.com")
            .encodedPath(path).fragment(null))
    }
}

/** Read-only, no OAuth header, and no redirects carrying a session to other origins. */
class RedditWebSessionRequest(
    context: Context,
    url: UriString,
    hasBody: Boolean,
    config: RedditWebSession.Config,
    backend: HTTPBackend
) : HTTPBackend.Request {
    private val delegate: HTTPBackend.Request

    init {
        require(!hasBody) { context.getString(R.string.web_session_read_only) }
        require(config.cookie.isNotEmpty()) { context.getString(R.string.web_session_missing) }
        delegate = backend.prepareRequest(context,
            HTTPBackend.RequestDetails(RedditWebSession.rewrite(url), null, false,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/158.0.0.0 Safari/537.36"))
        delegate.addHeader("Cookie", config.cookie)
        delegate.addHeader("Accept", "application/json")
        delegate.addHeader("Accept-Language", "en-US,en;q=0.9")
    }

    override fun executeInThisThread(listener: HTTPBackend.Listener) =
        delegate.executeInThisThread(listener)
    override fun cancel() = delegate.cancel()
    // Authentication belongs exclusively to this adapter.
    override fun addHeader(name: String, value: String) = Unit
}
