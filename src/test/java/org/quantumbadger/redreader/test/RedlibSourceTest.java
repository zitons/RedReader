package org.quantumbadger.redreader.test;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.quantumbadger.redreader.common.UriString;
import org.quantumbadger.redreader.reddit.api.RedlibSource;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = android.app.Application.class, sdk = 28)
public class RedlibSourceTest {

	@Test
	public void preservesRedditQueryAndPaginationWithServerPrefix() {
		final RedlibSource source = new RedlibSource("https://reader.example/api/", "secret");
		final String path = "/r/Anki/new.json?after=t3_abc&q=a%2Bb&limit=25";
		final Uri result = source.rewrite(new UriString("https://oauth.reddit.com" + path)).toUri();
		assertEquals("/api/v1/reddit", result.getPath());
		assertEquals(path, result.getQueryParameter("path"));
		assertFalse(result.toString().contains("secret"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsCredentialsInServerUrl() {
		new RedlibSource("https://user:password@example.com", "");
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesToForwardExternalMediaRequests() {
		new RedlibSource("https://reader.example", "secret")
				.rewrite(new UriString("https://images.example/photo.jpg"));
	}
}
