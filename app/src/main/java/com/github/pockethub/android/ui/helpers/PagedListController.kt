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
package com.github.pockethub.android.ui.helpers

import com.github.pockethub.android.ui.item.LoadErrorItem
import com.github.pockethub.android.ui.item.LoadingItem
import com.xwray.groupie.Item
import com.xwray.groupie.Section

/**
 * The single state machine shared by every list in the app. First-screen refresh,
 * "load more" and weak-network retry are all expressed as transitions here, so the
 * three behaviours can never drift apart again.
 *
 * Responsibilities:
 *  - **Dedup:** the canonical data is kept in an insertion-ordered map keyed by
 *    [keyOf] (the Groupie item id by default). Re-loading an overlapping page (a
 *    common occurrence on GitHub when the underlying list shifts, or after a
 *    retry) simply overwrites the existing entry instead of appending a duplicate.
 *  - **Never wipe on error:** the data map is only ever cleared on a *successful*
 *    refresh. A failed refresh keeps what is on screen; a failed first load shows
 *    a retryable empty view; a failed "load more" keeps the list and shows a
 *    tap-to-retry footer.
 *  - **Grouping:** [transform] is applied to the *whole* data set on every render,
 *    so grouped lists (e.g. repositories, notifications) get correct section
 *    headers even while paging.
 *  - **Footer:** owns the single section footer slot, swapping between a spinner
 *    ([LoadingItem]) while loading more and a [LoadErrorItem] on failure.
 *
 * It is deliberately free of RxJava and Android view code (it only touches a
 * Groupie [Section] plus a handful of callbacks) so it can be unit tested on the
 * JVM. The trigger adapters [PagedListFetcher] (paged) and [ListFetcher]
 * (non-paged) own the actual data requests and drive this controller.
 *
 * @param section the Groupie section whose footer this controller manages.
 * @param render renders the (deduped, transformed) body items, e.g. via
 *   [ItemListHandler.update].
 * @param setRefreshing toggles the pull-to-refresh spinner.
 * @param showEmptyError shows a retryable error in place of the empty view; the
 *   supplied lambda must be wired to retry the first load.
 * @param showError surfaces a transient error (typically a toast).
 */
class PagedListController(
    private val section: Section,
    private val render: (List<Item<*>>) -> Unit,
    private val setRefreshing: (Boolean) -> Unit,
    private val showEmptyError: (onRetry: () -> Unit) -> Unit,
    private val showError: (Throwable) -> Unit
) {

    /** The footer currently occupying the section's single footer slot. */
    enum class Footer { NONE, LOADING, ERROR }

    /**
     * Canonical, deduped, insertion-ordered data. Only **data** items live here;
     * grouping headers are injected by [transform] at render time and are never
     * stored, so they cannot interfere with dedup.
     */
    private val store = LinkedHashMap<Long, Item<*>>()

    /** Derives the dedup key for an item. Defaults to the stable Groupie id. */
    var keyOf: (Item<*>) -> Long = { it.id }

    /**
     * Applied to the full deduped data set on every render. The grouping hook for
     * grouped lists; the identity transform for flat lists.
     */
    var transform: (List<Item<*>>) -> List<Item<*>> = { it }

    /**
     * Retries the last load. Set by the trigger adapter before each request so that
     * the error footer / empty-error view retries the *same* work (and, crucially,
     * the same page — no page is skipped).
     */
    var onRetry: () -> Unit = {}

    /** The current state of the machine. */
    var state: PagedListState = PagedListState.IDLE
        private set

    /** The footer currently shown. Exposed for testing and for the scroll listener. */
    var footer: Footer = Footer.NONE
        private set

    /** Whether there is any data currently held. */
    val isEmpty: Boolean
        get() = store.isEmpty()

    /** The deduped data items, before [transform]. */
    val items: List<Item<*>>
        get() = store.values.toList()

    /**
     * A load has started.
     *
     * @param replace true for a first load / refresh (page 1), false for "load
     *   more". Existing data is never cleared here — only on success — so a failed
     *   refresh leaves the current content untouched.
     */
    fun onLoadStarted(replace: Boolean) {
        if (replace) {
            state = if (store.isEmpty()) {
                PagedListState.LOADING_FIRST
            } else {
                PagedListState.REFRESHING
            }
            setFooter(Footer.NONE)
            setRefreshing(true)
        } else {
            state = PagedListState.LOADING_MORE
            setFooter(Footer.LOADING)
        }
    }

    /**
     * A load finished successfully.
     *
     * @param rawItems the items for the page that loaded (already mapped to Groupie
     *   items, before grouping).
     * @param replace true to replace the whole data set (refresh / page 1), false
     *   to append the next page. In both cases items are deduped by [keyOf].
     */
    fun onLoadSuccess(rawItems: List<Item<*>>, replace: Boolean) {
        if (replace) {
            store.clear()
        }
        // LinkedHashMap.put on an existing key keeps the original position while
        // refreshing the value -> stable order, latest content, never a duplicate.
        for (item in rawItems) {
            store[keyOf(item)] = item
        }

        setRefreshing(false)
        setFooter(Footer.NONE)
        state = PagedListState.LOADED
        renderBody()
    }

    /**
     * A load failed.
     *
     * @param error the failure.
     * @param replace true if the failed request was a first load / refresh.
     */
    fun onLoadError(error: Throwable, replace: Boolean) {
        setRefreshing(false)

        if (replace) {
            if (store.isEmpty()) {
                // Nothing to fall back to -> retryable empty state.
                state = PagedListState.ERROR_FIRST
                setFooter(Footer.NONE)
                showEmptyError(onRetry)
            } else {
                // Keep what is already on screen; just report the failure.
                state = PagedListState.LOADED
            }
        } else {
            // Load-more failure: keep the list, offer a tap-to-retry footer. The
            // page counter is owned by the adapter and is NOT advanced on failure,
            // so retry re-requests the same page.
            state = PagedListState.ERROR_MORE
            setFooter(Footer.ERROR)
        }

        showError(error)
    }

    private fun renderBody() {
        render(transform(store.values.toList()))
    }

    private fun setFooter(target: Footer) {
        if (footer == target) {
            return
        }
        when (target) {
            Footer.NONE -> section.removeFooter()
            Footer.LOADING -> section.setFooter(LoadingItem())
            Footer.ERROR -> section.setFooter(LoadErrorItem(onRetry))
        }
        footer = target
    }
}
