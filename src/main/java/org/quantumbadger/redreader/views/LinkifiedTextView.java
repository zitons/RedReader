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

package org.quantumbadger.redreader.views;

import android.annotation.SuppressLint;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.GestureDetector;
import android.view.View;
import android.view.ViewParent;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatTextView;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.common.FushiLookup;

public class LinkifiedTextView extends AppCompatTextView {

	private final AppCompatActivity mActivity;
	private int mLookupOffset = -1;
	private boolean mLookupGesture;
	private final GestureDetector mLookupDetector;

	public LinkifiedTextView(final AppCompatActivity activity) {
		super(activity);
		mActivity = activity;
		mLookupDetector = new GestureDetector(activity,
				new GestureDetector.SimpleOnGestureListener() {
					@Override
					public boolean onDown(final MotionEvent event) {
						return true;
					}

					@Override
					public boolean onSingleTapUp(final MotionEvent event) {
						if(mLookupOffset >= 0) {
							performClick();
							FushiLookup.open(mActivity, LinkifiedTextView.this, mLookupOffset);
						}
						return true;
					}

					@Override
					public void onLongPress(final MotionEvent event) {
						mLookupOffset = -1;
						if(performLongClick()) {
							return;
						}
						ViewParent parent = getParent();
						while(parent instanceof View) {
							if(((View)parent).performLongClick()) {
								return;
							}
							parent = parent.getParent();
						}
					}
				});
	}

	public AppCompatActivity getActivity() {
		return mActivity;
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(final MotionEvent event) {
		final CharSequence text = getText();
		final int action = event.getActionMasked();
		if(action == MotionEvent.ACTION_DOWN) {
			FushiLookup.clearHighlight();
			mLookupGesture = false;
			mLookupOffset = PrefsUtility.fushiTapLookupEnabled()
					? FushiLookup.characterAt(this, event.getX(), event.getY()) : -1;
			if(mLookupOffset >= 0 && text instanceof Spannable
					&& ((Spannable)text).getSpans(mLookupOffset, mLookupOffset,
							ClickableSpan.class).length > 0) {
				mLookupOffset = -1;
			}
			mLookupGesture = mLookupOffset >= 0;
		}
		if(mLookupGesture) {
			if(event.getPointerCount() > 1) {
				mLookupOffset = -1;
			}
			mLookupDetector.onTouchEvent(event);
			if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
				mLookupGesture = false;
				mLookupOffset = -1;
			}
			return true;
		}

		if(!(text instanceof Spannable)) {
			return false;
		}

		if(!PrefsUtility.pref_appearance_link_text_clickable()) {

			return false;
		}

		final Spannable buffer = (Spannable)text;

		if(action == MotionEvent.ACTION_UP ||
				action == MotionEvent.ACTION_DOWN) {
			int x = (int)event.getX();
			int y = (int)event.getY();

			x -= getTotalPaddingLeft();
			y -= getTotalPaddingTop();

			x += getScrollX();
			y += getScrollY();

			final Layout layout = getLayout();
			if(layout == null) {
				return false;
			}
			final int line = layout.getLineForVertical(y);
			final int off = layout.getOffsetForHorizontal(line, x);

			final ClickableSpan[] links = buffer.getSpans(off, off, ClickableSpan.class);

			if(links.length != 0) {
				if(action == MotionEvent.ACTION_UP) {
					links[0].onClick(this);
				} else if(action == MotionEvent.ACTION_DOWN) {
					Selection.setSelection(
							buffer,
							buffer.getSpanStart(links[0]),
							buffer.getSpanEnd(links[0]));
				}

				return true;

			} else {
				Selection.removeSelection(buffer);
			}
		}

		return false;
	}
}
