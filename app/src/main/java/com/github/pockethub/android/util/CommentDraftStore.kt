/*
 * Copyright (c) 2015 PocketHub
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.pockethub.android.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages comment draft persistence via SharedPreferences.
 *
 * Each draft is stored as two flat keys under a unique [draftKey]:
 * - `{draftKey}_body` — the comment text
 * - `{draftKey}_tab`  — the selected ViewPager tab position (0 = write, 1 = preview)
 */
object CommentDraftStore {

    private const val PREFS_NAME = "comment_drafts"
    private const val SUFFIX_BODY = "_body"
    private const val SUFFIX_TAB = "_tab"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Save a comment draft. Uses [SharedPreferences.Editor.apply] so the write
     * is guaranteed to complete before process death during lifecycle transitions.
     */
    fun saveDraft(context: Context, draftKey: String, body: String, tabPosition: Int) {
        prefs(context).edit()
            .putString(draftKey + SUFFIX_BODY, body)
            .putInt(draftKey + SUFFIX_TAB, tabPosition)
            .apply()
    }

    /**
     * Load the saved comment body for [draftKey], or null if no draft exists.
     */
    fun loadBody(context: Context, draftKey: String): String? =
        prefs(context).getString(draftKey + SUFFIX_BODY, null)

    /**
     * Load the saved tab position for [draftKey], defaulting to 0 (write tab).
     */
    fun loadTabPosition(context: Context, draftKey: String): Int =
        prefs(context).getInt(draftKey + SUFFIX_TAB, 0)

    /**
     * Clear a single draft identified by [draftKey].
     */
    fun clearDraft(context: Context, draftKey: String) {
        prefs(context).edit()
            .remove(draftKey + SUFFIX_BODY)
            .remove(draftKey + SUFFIX_TAB)
            .apply()
    }

    /**
     * Clear all saved comment drafts. Called on logout to prevent
     * cross-account draft leakage.
     */
    fun clearAllDrafts(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
