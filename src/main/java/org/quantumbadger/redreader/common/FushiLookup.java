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

package org.quantumbadger.redreader.common;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import org.quantumbadger.redreader.R;
import java.lang.ref.WeakReference;

/** Uses the existing Fushi popup and its UTF-16 character-position contract. */
public final class FushiLookup {
	private static WeakReference<TextView> highlightedView = new WeakReference<>(null);
	private static WeakReference<ViewTreeObserver> focusObserver = new WeakReference<>(null);
	private static boolean lookupLostFocus;
	private static final ViewTreeObserver.OnWindowFocusChangeListener FOCUS_LISTENER = hasFocus -> {
		if(!hasFocus) {
			lookupLostFocus = true;
		} else if(lookupLostFocus) {
			clearHighlight();
		}
	};
	private static final View.OnAttachStateChangeListener ATTACH_LISTENER =
			new View.OnAttachStateChangeListener() {
				@Override
				public void onViewAttachedToWindow(final View view) {
					// Already registered on the active window when lookup starts.
				}

				@Override
				public void onViewDetachedFromWindow(final View view) {
					if(view == highlightedView.get()) {
						clearHighlight();
					}
				}
			};

	private static final class LookupHighlight extends BackgroundColorSpan {
		LookupHighlight(final int color) {
			super(color);
		}
	}

	private static boolean wordCharacter(final int cp) {
		return Character.isLetter(cp) && (cp <= 0x02af
				|| cp >= 0x0370 && cp <= 0x08ff
				|| cp >= 0x10a0 && cp <= 0x10ff
				|| cp >= 0x1e00 && cp <= 0x1fff)
				|| cp >= '0' && cp <= '9'
				|| Character.getType(cp) == Character.NON_SPACING_MARK;
	}

	public static int scanStart(final CharSequence text, final int offset) {
		if(offset < 0 || offset >= text.length()) {
			return -1;
		}
		int start = offset;
		if(start > 0 && Character.isLowSurrogate(text.charAt(start))) {
			start--;
		}
		if(wordCharacter(Character.codePointAt(text, start))) {
			while(start > 0) {
				final int previous = Character.offsetByCodePoints(text, start, -1);
				if(!wordCharacter(Character.codePointAt(text, previous))) {
					break;
				}
				start = previous;
			}
		}
		return start;
	}

	public static void clearHighlight() {
		final ViewTreeObserver observer = focusObserver.get();
		if(observer != null && observer.isAlive()) {
			observer.removeOnWindowFocusChangeListener(FOCUS_LISTENER);
		}
		focusObserver.clear();
		lookupLostFocus = false;
		final TextView previous = highlightedView.get();
		if(previous != null) {
			previous.removeOnAttachStateChangeListener(ATTACH_LISTENER);
		}
		if(previous != null && previous.getText() instanceof Spannable) {
			final Spannable text = (Spannable)previous.getText();
			for(final LookupHighlight span
					: text.getSpans(0, text.length(), LookupHighlight.class)) {
				text.removeSpan(span);
			}
		}
		highlightedView.clear();
	}

	private static void highlight(final TextView view, final int offset) {
		clearHighlight();
		final int start = scanStart(view.getText(), offset);
		int end = Character.offsetByCodePoints(view.getText(), start, 1);
		if(wordCharacter(Character.codePointAt(view.getText(), start))) {
			while(end < view.length()
					&& wordCharacter(Character.codePointAt(view.getText(), end))) {
				end = Character.offsetByCodePoints(view.getText(), end, 1);
			}
		}
		if(!(view.getText() instanceof Spannable)) {
			view.setText(new SpannableString(view.getText()), TextView.BufferType.SPANNABLE);
		}
		((Spannable)view.getText()).setSpan(new LookupHighlight(view.getHighlightColor()),
				start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		highlightedView = new WeakReference<>(view);
		final ViewTreeObserver observer = view.getViewTreeObserver();
		observer.addOnWindowFocusChangeListener(FOCUS_LISTENER);
		focusObserver = new WeakReference<>(observer);
		view.addOnAttachStateChangeListener(ATTACH_LISTENER);
	}

	@SuppressLint("ClickableViewAccessibility")
	public static void enableOnTitle(final Activity activity, final TextView view) {
		final int[] offset = {-1};
		final boolean[] gesture = {false};
		final GestureDetector detector = new GestureDetector(activity,
				new GestureDetector.SimpleOnGestureListener() {
					@Override
					public boolean onDown(final MotionEvent event) {
						return true;
					}

					@Override
					public boolean onSingleTapUp(final MotionEvent event) {
						if(offset[0] >= 0) {
							open(activity, view, offset[0]);
						}
						return true;
					}

					@Override
					public void onLongPress(final MotionEvent event) {
						offset[0] = -1;
						if(view.performLongClick()) {
							return;
						}
						ViewParent parent = view.getParent();
						while(parent instanceof View) {
							if(((View)parent).performLongClick()) {
								return;
							}
							parent = parent.getParent();
						}
					}
				});
		view.setOnTouchListener((target, event) -> {
			final int action = event.getActionMasked();
			if(action == MotionEvent.ACTION_DOWN) {
				clearHighlight();
				offset[0] = PrefsUtility.fushiTapLookupEnabled()
						? characterAt(view, event.getX(), event.getY()) : -1;
				gesture[0] = offset[0] >= 0;
			}
			if(!gesture[0]) {
				return false;
			}
			if(event.getPointerCount() > 1) {
				offset[0] = -1;
			}
			detector.onTouchEvent(event);
			if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
				gesture[0] = false;
				offset[0] = -1;
			}
			return true;
		});
	}

	private FushiLookup() {
	}

	public static int characterAt(final TextView view, final float x, final float y) {
		final Layout layout = view.getLayout();
		if(layout == null || view.getText().length() == 0) {
			return -1;
		}
		final float localX = x - view.getTotalPaddingLeft() + view.getScrollX();
		final int localY = (int)y - view.getTotalPaddingTop() + view.getScrollY();
		if(localY < 0 || localY >= layout.getHeight()) {
			return -1;
		}
		final int line = layout.getLineForVertical(localY);
		if(localX < layout.getLineLeft(line) || localX >= layout.getLineRight(line)) {
			return -1;
		}
		int offset = layout.getOffsetForHorizontal(line, localX);
		if(offset > layout.getLineStart(line)) {
			final int previous = Character.offsetByCodePoints(view.getText(), offset, -1);
			final float before = layout.getPrimaryHorizontal(previous);
			final float after = layout.getPrimaryHorizontal(offset);
			if(localX >= Math.min(before, after) && localX < Math.max(before, after)) {
				offset = previous;
			}
		}
		if(offset >= view.getText().length()) {
			return -1;
		}
		if(offset > 0 && Character.isLowSurrogate(view.getText().charAt(offset))) {
			offset--;
		}
		return Character.isLetterOrDigit(Character.codePointAt(view.getText(), offset))
				? offset : -1;
	}

	public static Intent createIntent(final CharSequence text, final int offset,
			final int left, final int top, final int right, final int bottom) {
		return new Intent(Intent.ACTION_PROCESS_TEXT)
				.setClassName("app.fushi.reader", "app.fushi.reader.PopupDictFlutterActivity")
				.setType("text/plain")
				.putExtra(Intent.EXTRA_PROCESS_TEXT, text.toString())
				.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
				.putExtra("charIndex", offset)
				.putExtra("sourceBlock", text.toString())
				.putExtra("sourceBlockOffset", 0)
				.putExtra("anchorLeft", left)
				.putExtra("anchorTop", top)
				.putExtra("anchorRight", right)
				.putExtra("anchorBottom", bottom);
	}

	public static void open(final Activity activity, final TextView view, final int offset) {
		final Layout layout = view.getLayout();
		if(layout == null || offset < 0 || offset >= view.getText().length()) {
			return;
		}
		final int line = layout.getLineForOffset(offset);
		final int[] screen = new int[2];
		view.getLocationOnScreen(screen);
		final int next = Character.offsetByCodePoints(view.getText(), offset, 1);
		final float x = layout.getPrimaryHorizontal(offset);
		final float nextX = layout.getLineForOffset(next) == line
				? layout.getPrimaryHorizontal(next) : layout.getLineRight(line);
		final int originX = screen[0] + view.getTotalPaddingLeft() - view.getScrollX();
		final int originY = screen[1] + view.getTotalPaddingTop() - view.getScrollY();
		final Intent intent = createIntent(view.getText(), offset,
				originX + (int)Math.min(x, nextX), originY + layout.getLineTop(line),
				originX + (int)Math.max(x, nextX), originY + layout.getLineBottom(line));
		try {
			highlight(view, offset);
			activity.startActivity(intent);
		} catch(final ActivityNotFoundException | SecurityException error) {
			clearHighlight();
			Toast.makeText(activity, R.string.fushi_lookup_unavailable, Toast.LENGTH_SHORT).show();
		}
	}
}
