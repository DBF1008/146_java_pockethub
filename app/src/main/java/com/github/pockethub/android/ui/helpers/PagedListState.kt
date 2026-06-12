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

/**
 * The lifecycle states a paged/grouped list goes through. A single
 * [PagedListController] owns this state so first-screen refresh, load-more and
 * weak-network retry all flow through the same machine.
 */
enum class PagedListState {

    /** Nothing has been requested yet. */
    IDLE,

    /** First load (or a refresh while the list is still empty) is in flight. */
    LOADING_FIRST,

    /** A refresh is in flight while data is already on screen. */
    REFRESHING,

    /** A "load more" (next page) request is in flight. */
    LOADING_MORE,

    /** The first load failed and there is no data to fall back to. */
    ERROR_FIRST,

    /** A "load more" request failed; existing data is kept and retry is offered. */
    ERROR_MORE,

    /** Data is present and no request is in flight. */
    LOADED;

    /**
     * Whether the scroll listener is allowed to trigger a new "load more". This
     * is only true when we are settled with data shown; it is intentionally
     * false during [LOADING_MORE] (avoid duplicate requests) and during
     * [ERROR_MORE] (retry must be an explicit tap, not an accidental scroll).
     */
    val canLoadMore: Boolean
        get() = this == IDLE || this == LOADED
}
