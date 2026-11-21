package org.quantumbadger.redreader.test;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.quantumbadger.redreader.common.FushiLookup;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.views.LinkifiedTextView;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = android.app.Application.class, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FushiLookupTest {
	private LookupActivity activity;

	public static class LookupActivity extends AppCompatActivity {
		@Override
		protected void onCreate(final Bundle savedInstanceState) {
			setTheme(androidx.appcompat.R.style.Theme_AppCompat);
			super.onCreate(savedInstanceState);
		}
	}

	private LinkifiedTextView lookupView() {
		activity = Robolectric.buildActivity(LookupActivity.class).setup().get();
		final android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
        info.packageName = activity.getPackageName();
        info.versionName = "test";
        info.signatures = new android.content.pm.Signature[0];
        Shadows.shadowOf(activity.getPackageManager()).installPackage(info);
        PrefsUtility.init(activity);
		General.getSharedPrefs(activity).edit().putBoolean(
				activity.getString(R.string.pref_fushi_tap_lookup_key), true).apply();
		final LinkifiedTextView view = new LinkifiedTextView(activity);
		view.setText("memory practice", TextView.BufferType.SPANNABLE);
		final FrameLayout parent = new FrameLayout(activity);
		parent.addView(view);
		activity.setContentView(parent);
		view.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
		view.layout(0, 0, 500, 100);
		return view;
	}

	private void touch(final LinkifiedTextView view, final long start, final long time,
			final int action, final float x, final float y) {
		final MotionEvent event = MotionEvent.obtain(start, time, action, x, y, 0);
		view.dispatchTouchEvent(event);
		event.recycle();
	}

	@Test
	public void tappingBodyTextStartsFushiWithoutSelectingText() {
		final LinkifiedTextView view = lookupView();
		final long start = SystemClock.uptimeMillis();
		final float x = view.getLayout().getPrimaryHorizontal(1) * .3f;
		final float y = view.getLayout().getLineBaseline(0) / 2f;
		touch(view, start, start, MotionEvent.ACTION_DOWN, x, y);
		touch(view, start, start + 30, MotionEvent.ACTION_UP, x, y);
		final Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
		assertEquals("app.fushi.reader", intent.getComponent().getPackageName());
		assertEquals(0, intent.getIntExtra("charIndex", -1));
	}

	@Test
	public void draggingBodyTextDoesNotStartFushi() {
		final LinkifiedTextView view = lookupView();
		final long start = SystemClock.uptimeMillis();
		touch(view, start, start, MotionEvent.ACTION_DOWN, 2, 5);
		touch(view, start, start + 20, MotionEvent.ACTION_MOVE, 60, 50);
		touch(view, start, start + 40, MotionEvent.ACTION_UP, 60, 50);
		assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
	}

    @Test
    public void tappingLinkKeepsExistingLinkAction() {
        final LinkifiedTextView view = lookupView();
        final java.util.concurrent.atomic.AtomicInteger clicks =
                new java.util.concurrent.atomic.AtomicInteger();
        final android.text.Spannable text = (android.text.Spannable)view.getText();
        text.setSpan(new android.text.style.ClickableSpan() {
            @Override
            public void onClick(final View widget) {
                clicks.incrementAndGet();
            }
        }, 0, 6, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        final long start = SystemClock.uptimeMillis();
        touch(view, start, start, MotionEvent.ACTION_DOWN, 2, 5);
        touch(view, start, start + 30, MotionEvent.ACTION_UP, 2, 5);
        assertEquals(1, clicks.get());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void disabledLookupDoesNotOpenFushi() {
        final LinkifiedTextView view = lookupView();
        General.getSharedPrefs(activity).edit().putBoolean(
                activity.getString(R.string.pref_fushi_tap_lookup_key), false).apply();
        final long start = SystemClock.uptimeMillis();
        touch(view, start, start, MotionEvent.ACTION_DOWN, 2, 5);
        touch(view, start, start + 30, MotionEvent.ACTION_UP, 2, 5);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

	@Test
	public void intentCarriesFullContextForFushiSentenceMining() {
		final String text = "Read \uD83D\uDE00 memory practice.";
		final Intent intent = FushiLookup.createIntent(text, text.indexOf("memory"), 10, 20, 30, 40);
		assertEquals("app.fushi.reader.PopupDictFlutterActivity", intent.getComponent().getClassName());
		assertEquals(text, intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT));
		assertEquals(text.indexOf("memory"), intent.getIntExtra("charIndex", -1));
		assertEquals(text, intent.getStringExtra("sourceBlock"));
		assertEquals(0, intent.getIntExtra("sourceBlockOffset", -1));
		assertEquals(10, intent.getIntExtra("anchorLeft", -1));
		assertEquals(40, intent.getIntExtra("anchorBottom", -1));
	}


	@Test
	public void lookupHighlightsWordAndNextTapReplacesIt() {
		final LinkifiedTextView view = lookupView();
		FushiLookup.open(activity, view, 3);
		android.text.Spannable text = (android.text.Spannable)view.getText();
		android.text.style.BackgroundColorSpan[] spans = text.getSpans(
				0, text.length(), android.text.style.BackgroundColorSpan.class);
		assertEquals(1, spans.length);
		assertEquals(0, text.getSpanStart(spans[0]));
		assertEquals(6, text.getSpanEnd(spans[0]));
		FushiLookup.open(activity, view, 10);
		spans = text.getSpans(0, text.length(), android.text.style.BackgroundColorSpan.class);
		assertEquals(1, spans.length);
		assertEquals(7, text.getSpanStart(spans[0]));
		assertEquals(15, text.getSpanEnd(spans[0]));
		FushiLookup.clearHighlight();
		assertEquals(0, text.getSpans(0, text.length(),
				android.text.style.BackgroundColorSpan.class).length);
	}

	@Test
	public void highlightSurvivesPopupAndClearsAsSoonAsReaderRegainsFocus() {
		final LinkifiedTextView view = lookupView();
		FushiLookup.open(activity, view, 3);
		final android.text.Spannable text = (android.text.Spannable)view.getText();
		final android.view.ViewTreeObserver observer = view.getViewTreeObserver();
		org.robolectric.util.ReflectionHelpers.callInstanceMethod(observer,
				"dispatchOnWindowFocusChange",
				org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, true));
		assertEquals(1, text.getSpans(0, text.length(),
				android.text.style.BackgroundColorSpan.class).length);
		org.robolectric.util.ReflectionHelpers.callInstanceMethod(observer,
				"dispatchOnWindowFocusChange",
				org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, false));
		assertEquals(1, text.getSpans(0, text.length(),
				android.text.style.BackgroundColorSpan.class).length);
		org.robolectric.util.ReflectionHelpers.callInstanceMethod(observer,
				"dispatchOnWindowFocusChange",
				org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, true));
		assertEquals(0, text.getSpans(0, text.length(),
				android.text.style.BackgroundColorSpan.class).length);
	}

	@Test
	public void removingLookedUpViewClearsHighlight() {
		final LinkifiedTextView view = lookupView();
		FushiLookup.open(activity, view, 3);
		final android.text.Spannable text = (android.text.Spannable)view.getText();
		activity.setContentView(new TextView(activity));
		assertEquals(0, text.getSpans(0, text.length(),
				android.text.style.BackgroundColorSpan.class).length);
	}

	@Test
	public void titleTextLooksUpInsteadOfNavigatingAndDisabledModeStillNavigates() {
		lookupView();
		final TextView title = new TextView(activity);
		title.setText("memory practice");
		title.setTextSize(20);
		final java.util.concurrent.atomic.AtomicInteger navigation =
				new java.util.concurrent.atomic.AtomicInteger();
		title.setOnClickListener(v -> navigation.incrementAndGet());
		FushiLookup.enableOnTitle(activity, title);
		activity.setContentView(title);
		title.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
		title.layout(0, 0, 500, 100);
		final float x = title.getLayout().getPrimaryHorizontal(3) + 1;
		final float y = title.getLayout().getLineBaseline(0) / 2f;
		long start = SystemClock.uptimeMillis();
		MotionEvent down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0);
		MotionEvent up = MotionEvent.obtain(start, start + 20, MotionEvent.ACTION_UP, x, y, 0);
		title.dispatchTouchEvent(down);
		title.dispatchTouchEvent(up);
		down.recycle();
		up.recycle();
		assertEquals("memory practice", Shadows.shadowOf(activity).getNextStartedActivity()
				.getStringExtra(Intent.EXTRA_PROCESS_TEXT));
		assertEquals(0, navigation.get());
		General.getSharedPrefs(activity).edit().putBoolean(
				activity.getString(R.string.pref_fushi_tap_lookup_key), false).apply();
		start += 100;
		down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0);
		up = MotionEvent.obtain(start, start + 20, MotionEvent.ACTION_UP, x, y, 0);
		title.dispatchTouchEvent(down);
		title.dispatchTouchEvent(up);
		down.recycle();
		up.recycle();
		Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
		assertEquals(1, navigation.get());
		assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
	}

	@Test
	public void characterHitRejectsPaddingAndFindsBothHalvesOfLastLetter() {
		final TextView view = new TextView(RuntimeEnvironment.getApplication());
		view.setText("memory ");
		view.setTextSize(20);
		view.setPadding(12, 10, 12, 10);
		view.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
		view.layout(0, 0, 500, 100);
		final float start = view.getLayout().getPrimaryHorizontal(5);
		final float end = view.getLayout().getPrimaryHorizontal(6);
		final float y = view.getTotalPaddingTop() + view.getLayout().getLineBaseline(0) / 2f;
		assertEquals(5, FushiLookup.characterAt(view, 12 + start + (end - start) * .25f, y));
		assertEquals(5, FushiLookup.characterAt(view, 12 + start + (end - start) * .75f, y));
		assertEquals(-1, FushiLookup.characterAt(view, 2, y));
		assertEquals(-1, FushiLookup.characterAt(view, 400, y));
	}
}
