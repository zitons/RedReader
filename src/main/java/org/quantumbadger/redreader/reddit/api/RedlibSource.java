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

package org.quantumbadger.redreader.reddit.api;

import android.net.Uri;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.common.UriString;

/** Connects anonymous Reddit reads to a redlib-api gateway. */
public final class RedlibSource {

	private final Uri base;
	private final String key;

	public RedlibSource(final String address, final String apiKey) {
		base = Uri.parse(address.trim()).normalizeScheme();
		key = apiKey == null ? "" : apiKey;
		if(!("https".equals(base.getScheme()) || "http".equals(base.getScheme()))
				|| base.getHost() == null || base.getUserInfo() != null
				|| base.getQuery() != null || base.getFragment() != null) {
			throw new IllegalArgumentException("Enter an HTTP or HTTPS Redlib server address");
		}
	}

	public static RedlibSource configured() {
		final String address = PrefsUtility.getString(R.string.pref_redlib_url_key, "");
		if(address == null || address.trim().isEmpty()) {
			return null;
		}
		return new RedlibSource(address,
				PrefsUtility.getString(R.string.pref_redlib_api_key_key, ""));
	}

	public UriString rewrite(final UriString original) {
		final Uri reddit = original.toUri();
		if(!"oauth.reddit.com".equals(reddit.getHost())) {
			throw new IllegalArgumentException("Expected a Reddit API URL");
		}
		String path = reddit.getEncodedPath();
		if(reddit.getEncodedQuery() != null) {
			path += "?" + reddit.getEncodedQuery();
		}
		return UriString.from(base.buildUpon()
				.appendPath("v1")
				.appendPath("reddit")
				.appendQueryParameter("path", path));
	}

	public String apiKey() {
		return key;
	}
}
