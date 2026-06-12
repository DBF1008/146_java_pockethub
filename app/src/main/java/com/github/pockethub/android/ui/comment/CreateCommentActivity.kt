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
package com.github.pockethub.android.ui.comment

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.github.pockethub.android.Intents.EXTRA_COMMENT
import com.github.pockethub.android.R
import com.github.pockethub.android.accounts.AccountUtils
import com.github.pockethub.android.persistence.CommentDraft
import com.github.pockethub.android.persistence.CommentDraftStore
import com.github.pockethub.android.ui.base.BaseActivity
import com.github.pockethub.android.ui.helpers.PagerHandler
import com.meisolsson.githubsdk.model.GitHubComment
import com.meisolsson.githubsdk.model.git.GitComment
import kotlinx.android.synthetic.main.pager_with_tabs.*

/**
 * Base activity for creating comments
 */
abstract class CreateCommentActivity : BaseActivity() {

    private var pagerHandler: PagerHandler<CommentPreviewPagerAdapter>? = null

    private val draftStore by lazy { CommentDraftStore(this) }

    private var commentSubmitted = false

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        configurePager()
        restoreDraft()
    }

    private fun configurePager() {
        val adapter = createAdapter()
        pagerHandler = PagerHandler(this, vp_pages, adapter)
        pagerHandler!!.onPagedChanged = this::onPageChanged
        lifecycle.addObserver(pagerHandler!!)
        pagerHandler!!.tabs = sliding_tabs_layout
    }

    private fun onPageChanged(position: Int) {
        pagerHandler!!.adapter.setCurrentItem(position)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycle.removeObserver(pagerHandler!!)
    }

    override fun onPause() {
        super.onPause()
        persistDraft()
    }

    /**
     * Key uniquely identifying the comment target (and reply target) a draft
     * belongs to. Returning null disables draft save/restore, which is the
     * default so that flows like editing an existing comment are unaffected.
     */
    protected open fun getDraftKey(): String? = null

    private fun restoreDraft() {
        val account = AccountUtils.getLogin(this) ?: return
        val key = getDraftKey() ?: return
        val draft = draftStore.loadDraft(account, key) ?: return
        if (draft.body.isBlank()) {
            return
        }

        pagerHandler!!.adapter.setCommentText(draft.body)
        if (draft.previewSelected) {
            // Post so the switch happens after the pager has laid out its pages.
            vp_pages.post { vp_pages.currentItem = 1 }
        }
    }

    private fun persistDraft() {
        // Never re-save after a successful submit cleared the draft.
        if (commentSubmitted) {
            return
        }

        val account = AccountUtils.getLogin(this) ?: return
        val key = getDraftKey() ?: return
        val body = pagerHandler?.adapter?.commentText ?: ""
        if (body.isBlank()) {
            draftStore.deleteDraft(account, key)
        } else {
            draftStore.saveDraft(
                account, key, CommentDraft(body, vp_pages.currentItem == 1)
            )
        }
    }

    private fun clearDraft() {
        commentSubmitted = true
        val account = AccountUtils.getLogin(this) ?: return
        val key = getDraftKey() ?: return
        draftStore.deleteDraft(account, key)
    }

    /**
     * Create comment
     *
     * @param comment
     */
    protected abstract fun createComment(comment: String)

    /**
     * Finish this activity passing back the created comment
     *
     * @param comment
     */
    protected fun finish(comment: GitHubComment) {
        clearDraft()
        val data = Intent()
        data.putExtra(EXTRA_COMMENT, comment)
        setResult(RESULT_OK, data)
        finish()
    }

    protected fun finish(comment: GitComment) {
        clearDraft()
        val data = Intent()
        data.putExtra(EXTRA_COMMENT, comment)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.m_apply -> {
                createComment(pagerHandler!!.adapter.commentText)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    protected open fun createAdapter(): CommentPreviewPagerAdapter {
        return CommentPreviewPagerAdapter(this, null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_comment, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val applyItem = menu?.findItem(R.id.m_apply)
        applyItem?.isEnabled =
            pagerHandler?.adapter != null && pagerHandler?.adapter!!.commentText.isNotEmpty()
        return true
    }
}
