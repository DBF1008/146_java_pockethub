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
package com.github.pockethub.android.ui.issue

import android.os.Bundle
import com.meisolsson.githubsdk.model.Issue
import com.meisolsson.githubsdk.model.Repository

/**
 * Recoverable state for an issue search.
 *
 * Bundles together everything needed to restore an in-progress search after a
 * configuration change (e.g. screen rotation), navigation to and from a detail
 * screen, or process death: the query, the repository scope, the paging cursor
 * and the scroll position. The already loaded results are kept as well so the
 * list and the exact scroll offset can be rebuilt without re-fetching.
 */
class SearchSession(
    val query: String?,
    val repository: Repository?,
    val page: Int,
    val hasMore: Boolean,
    val scrollPosition: Int,
    val scrollOffset: Int,
    val issues: List<Issue>
) {

    /**
     * Serialize the whole session into a single [Bundle] so the four pieces of
     * context are always saved and restored together.
     */
    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putString(KEY_QUERY, query)
        bundle.putParcelable(KEY_REPOSITORY, repository)
        bundle.putInt(KEY_PAGE, page)
        bundle.putBoolean(KEY_HAS_MORE, hasMore)
        bundle.putInt(KEY_SCROLL_POSITION, scrollPosition)
        bundle.putInt(KEY_SCROLL_OFFSET, scrollOffset)
        bundle.putParcelableArrayList(KEY_ISSUES, ArrayList(issues))
        return bundle
    }

    companion object {

        /**
         * Key under which the session bundle is stored in the saved instance
         * state.
         */
        const val KEY = "issue_search_session"

        private const val KEY_QUERY = "query"
        private const val KEY_REPOSITORY = "repository"
        private const val KEY_PAGE = "page"
        private const val KEY_HAS_MORE = "has_more"
        private const val KEY_SCROLL_POSITION = "scroll_position"
        private const val KEY_SCROLL_OFFSET = "scroll_offset"
        private const val KEY_ISSUES = "issues"

        /**
         * Rebuild a session from a bundle previously produced by [toBundle], or
         * `null` when there is nothing to restore.
         */
        fun fromBundle(bundle: Bundle?): SearchSession? {
            if (bundle == null) {
                return null
            }

            val issues = bundle.getParcelableArrayList<Issue>(KEY_ISSUES) ?: ArrayList()
            return SearchSession(
                bundle.getString(KEY_QUERY),
                bundle.getParcelable(KEY_REPOSITORY),
                bundle.getInt(KEY_PAGE, 1),
                bundle.getBoolean(KEY_HAS_MORE, true),
                bundle.getInt(KEY_SCROLL_POSITION, -1),
                bundle.getInt(KEY_SCROLL_OFFSET, 0),
                issues
            )
        }
    }
}
