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
package com.github.pockethub.android.ui.item

import com.github.pockethub.android.R
import com.xwray.groupie.kotlinandroidextensions.Item
import com.xwray.groupie.kotlinandroidextensions.ViewHolder

/**
 * Footer shown at the bottom of a list when loading the next page failed. Tapping
 * the row invokes [onRetry], which re-requests the same page (no page is skipped).
 *
 * This is the error counterpart of [LoadingItem]; the [PagedListController] swaps
 * between the two depending on the current [com.github.pockethub.android.ui.helpers.PagedListState].
 */
class LoadErrorItem(private val onRetry: () -> Unit) : Item(ID) {

    override fun getLayout() = R.layout.load_error_item

    override fun bind(holder: ViewHolder, position: Int) {
        holder.itemView.setOnClickListener { onRetry() }
    }

    companion object {
        // Stable id so DiffUtil keeps the footer steady across renders. Negative
        // to stay clear of the positive content ids derived from model objects.
        private const val ID = -42L
    }
}
