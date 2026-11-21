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

package org.quantumbadger.redreader.settings

import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.reddit.api.RedditWebSession

object WebSessionSettings {
    @JvmStatic fun bind(fragment: PreferenceFragmentCompat) {
        val preference = fragment.findPreference<Preference>("pref_web_session_configure") ?: return
        val context = fragment.requireContext()
        fun updateSummary() {
            preference.setSummary(if (RedditWebSession.configured(context).cookie.isEmpty())
                R.string.pref_web_session_summary else R.string.web_session_configured)
        }
        updateSummary()
        preference.setOnPreferenceClickListener {
            val input = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "reddit_session=…"
                isSaveEnabled = false
                setSingleLine(true)
            }
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.pref_web_session_title)
                .setMessage(R.string.web_session_instructions)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.web_session_clear) { _, _ ->
                    RedditWebSession.clear(context)
                    updateSummary()
                }.create()
            dialog.setOnShowListener {
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener {
                        try {
                            RedditWebSession.save(context, input.text.toString())
                            input.text.clear()
                            updateSummary()
                            dialog.dismiss()
                        } catch (_: IllegalArgumentException) {
                            input.error = context.getString(R.string.web_session_invalid)
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.web_session_save_failed,
                                Toast.LENGTH_LONG).show()
                        }
                    }
            }
            dialog.setOnDismissListener { input.text.clear() }
            dialog.show()
            true
        }
    }
}
