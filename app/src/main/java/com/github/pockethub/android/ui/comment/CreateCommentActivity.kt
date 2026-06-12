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
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import com.afollestad.materialdialogs.MaterialDialog
import com.github.pockethub.android.Intents.EXTRA_COMMENT
import com.github.pockethub.android.R
import com.github.pockethub.android.ui.base.BaseActivity
import com.github.pockethub.android.ui.helpers.PagerHandler
import com.github.pockethub.android.util.CommentDraftStore
import com.meisolsson.githubsdk.model.GitHubComment
import com.meisolsson.githubsdk.model.git.GitComment
import kotlinx.android.synthetic.main.pager_with_tabs.*

/**
 * Base activity for creating comments
 */
abstract class CreateCommentActivity : BaseActivity() {

    companion object {
        private const val STATE_DRAFT_BODY = "state_draft_body"
        private const val STATE_DRAFT_TAB = "state_draft_tab"
    }

    private var pagerHandler: PagerHandler<CommentPreviewPagerAdapter>? = null

    private var draftSubmitted = false

    /**
     * Provide a unique key that identifies this comment scenario.
     * Used for draft auto-save and restore.
     */
    protected abstract fun getDraftKey(): String

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        configurePager()
        restoreDraft(savedInstanceState)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val adapter = pagerHandler?.adapter ?: return
        outState.putString(STATE_DRAFT_BODY, adapter.commentText)
        outState.putInt(STATE_DRAFT_TAB, vp_pages.currentItem)
    }

    override fun onPause() {
        super.onPause()
        if (!draftSubmitted) {
            val adapter = pagerHandler?.adapter ?: return
            val body = adapter.commentText
            if (body != null) {
                CommentDraftStore.saveDraft(this, getDraftKey(), body, vp_pages.currentItem)
            }
        }
    }

    /**
     * Restore a previously saved draft. Checks the Bundle first (rotation path),
     * then falls back to SharedPreferences (process-death path).
     */
    private fun restoreDraft(savedInstanceState: Bundle?) {
        val adapter = pagerHandler?.adapter ?: return

        var body: String? = null
        var tab = 0

        if (savedInstanceState != null) {
            body = savedInstanceState.getString(STATE_DRAFT_BODY)
            tab = savedInstanceState.getInt(STATE_DRAFT_TAB, 0)
        }

        if (body == null) {
            body = CommentDraftStore.loadBody(this, getDraftKey())
            tab = CommentDraftStore.loadTabPosition(this, getDraftKey())
        }

        if (!TextUtils.isEmpty(body)) {
            adapter.setCommentText(body)
            if (tab > 0) {
                vp_pages.currentItem = tab
                adapter.setCurrentItem(tab)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycle.removeObserver(pagerHandler!!)
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
        draftSubmitted = true
        CommentDraftStore.clearDraft(this, getDraftKey())
        val data = Intent()
        data.putExtra(EXTRA_COMMENT, comment)
        setResult(RESULT_OK, data)
        finish()
    }

    protected fun finish(comment: GitComment) {
        draftSubmitted = true
        CommentDraftStore.clearDraft(this, getDraftKey())
        val data = Intent()
        data.putExtra(EXTRA_COMMENT, comment)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onBackPressed() {
        confirmDiscardAndFinish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                confirmDiscardAndFinish()
                true
            }
            R.id.m_apply -> {
                createComment(pagerHandler!!.adapter.commentText)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * If the comment body is non-empty and the draft has not been submitted,
     * show a confirmation dialog before discarding and finishing.
     */
    private fun confirmDiscardAndFinish() {
        if (draftSubmitted) {
            finish()
            return
        }
        val adapter = pagerHandler?.adapter
        val body = adapter?.commentText
        if (TextUtils.isEmpty(body)) {
            finish()
            return
        }
        MaterialDialog.Builder(this)
            .title(R.string.discard_draft_title)
            .content(R.string.discard_draft_message)
            .positiveText(R.string.discard)
            .negativeText(android.R.string.cancel)
            .onPositive { _, _ ->
                draftSubmitted = true
                CommentDraftStore.clearDraft(this, getDraftKey())
                finish()
            }
            .show()
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
