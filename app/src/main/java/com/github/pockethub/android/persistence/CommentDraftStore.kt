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
package com.github.pockethub.android.persistence

import android.content.Context
import org.json.JSONObject

/**
 * A saved, unsent comment: the typed [body] and whether the preview tab was
 * selected when it was saved.
 */
data class CommentDraft(
    val body: String,
    val previewSelected: Boolean
)

/**
 * Stores unsent comment drafts so the body and preview state survive
 * configuration changes, process death and re-authentication.
 *
 * Each draft is keyed by the comment target (see the `getDraftKey` of the
 * comment activities) and scoped by the owning account login, so a draft is
 * never restored for the wrong target or a different account.
 */
class CommentDraftStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Load the draft for the given [account] and target [key], or null if none
     * is stored (or either argument is missing).
     */
    fun loadDraft(account: String?, key: String?): CommentDraft? {
        val storageKey = storageKey(account, key) ?: return null
        val raw = prefs.getString(storageKey, null) ?: return null
        return try {
            val json = JSONObject(raw)
            CommentDraft(
                json.optString(KEY_BODY, ""),
                json.optBoolean(KEY_PREVIEW, false)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Persist [draft] for the given [account] and target [key]. No-op if either
     * argument is missing.
     */
    fun saveDraft(account: String?, key: String?, draft: CommentDraft) {
        val storageKey = storageKey(account, key) ?: return
        val json = JSONObject()
            .put(KEY_BODY, draft.body)
            .put(KEY_PREVIEW, draft.previewSelected)
        prefs.edit().putString(storageKey, json.toString()).apply()
    }

    /**
     * Remove the draft for the given [account] and target [key]. No-op if either
     * argument is missing.
     */
    fun deleteDraft(account: String?, key: String?) {
        val storageKey = storageKey(account, key) ?: return
        prefs.edit().remove(storageKey).apply()
    }

    /**
     * Remove every stored draft. Used when the user logs out / switches account.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun storageKey(account: String?, key: String?): String? {
        if (account.isNullOrEmpty() || key.isNullOrEmpty()) {
            return null
        }
        // \u0000 cannot appear in a GitHub login, so it safely separates the
        // account scope from the target key.
        return "$account\u0000$key"
    }

    companion object {
        private const val PREFS_NAME = "comment_drafts"
        private const val KEY_BODY = "body"
        private const val KEY_PREVIEW = "previewSelected"
    }
}
