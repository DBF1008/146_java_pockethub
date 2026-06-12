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
package com.github.pockethub.android.accounts

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import com.github.pockethub.android.BuildConfig
import com.github.pockethub.android.Database
import com.meisolsson.githubsdk.core.TokenStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the local-data isolation and ordered refresh that must happen when the active
 * account changes.
 *
 * The cache backing PocketHub (the `cache.db` database, the `recent-repos-*.ser` files and the
 * `cache` directory) is shared by every account, so without this coordination the previous
 * account's data keeps rendering until a slow background sync overwrites it. This manager makes
 * the ordering explicit and consistent:
 *
 *  1. **clear** every per-account local cache (and, on logout, the in-memory auth token),
 *  2. **trigger** an expedited sync for the account that should own the data next,
 *  3. let the UI **backfill** from the now-empty cache, which forces a fresh network load.
 *
 * It also keeps a lightweight tag of which account the local cache currently belongs to so the
 * isolation can self-heal on any entry point ([enforceIsolation]).
 */
@Singleton
class AccountSwitchManager @Inject constructor(
    private val context: Context,
    private val database: Database
) {

    /**
     * Clear every piece of per-account local state, including the auth token. Use this when the
     * current account is being removed (logout / switch away) so nothing leaks into the next
     * account.
     */
    fun clearAllLocalData() {
        clearAuthToken()
        clearLocalCaches()
    }

    /**
     * Drop the in-memory/persisted auth token so no request runs against the old account while a
     * new one is being configured.
     */
    fun clearAuthToken() {
        TokenStore.getInstance(context).saveToken(null)
    }

    /**
     * Clear the cached account data (database rows, recent-repository files and the cache
     * directory) without touching the auth token. Used by the self-healing guard so the current
     * account's token survives the immediate reload that follows.
     */
    fun clearLocalCaches() {
        clearDatabase()
        clearRecentRepositoryFiles()
        clearCacheDirectory()
        clearCacheOwner()
    }

    /**
     * Request an immediate, expedited sync for the given account instead of waiting for the
     * periodic sync, so the freshly-cleared cache is repopulated promptly.
     */
    fun requestFullSync(account: Account) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(account, BuildConfig.PROVIDER_AUTHORITY_SYNC, extras)
    }

    /**
     * Mark the local cache as owned by [account] and kick off an expedited sync. Call this right
     * after a new account has been added so [enforceIsolation] does not wrongly wipe the data
     * that this sync is about to produce.
     */
    fun prepareForNewAccount(account: Account) {
        markCacheOwner(account)
        requestFullSync(account)
    }

    /**
     * Ensure the local cache belongs to [account]. If it was populated for a different account the
     * caches are cleared (the token is left intact for the immediate reload) and the ownership tag
     * is updated.
     *
     * @return true if stale data was cleared, false if the cache already belonged to [account]
     */
    fun enforceIsolation(account: Account): Boolean {
        if (cacheOwner() == account.name) {
            return false
        }
        clearLocalCaches()
        markCacheOwner(account)
        return true
    }

    /**
     * Record which account the current local cache belongs to. Refresh this after a successful
     * load so a benign account rename does not look like an account switch on the next entry.
     */
    fun markCacheOwner(account: Account) {
        preferences().edit()
            .putString(KEY_CACHE_OWNER, account.name)
            .apply()
    }

    private fun clearDatabase() {
        database.transaction {
            database.issue_filterQueries.clearIssueFilterLabels()
            database.issue_filterQueries.clearIssueFilters()
            database.issue_filterQueries.clearLabels()
            database.issue_filterQueries.clearMilestones()
            database.repositoriesQueries.clearRepositories()
            database.organizationsQueries.clearOrgs()
            database.organizationsQueries.clearUsers()
        }
    }

    private fun clearRecentRepositoryFiles() {
        context.filesDir
            ?.listFiles { _, name -> name.startsWith(RECENT_REPOS_PREFIX) }
            ?.forEach { it.delete() }
    }

    private fun clearCacheDirectory() {
        File(context.filesDir, CACHE_DIR_NAME).deleteRecursively()
    }

    private fun cacheOwner(): String? =
        preferences().getString(KEY_CACHE_OWNER, null)

    private fun clearCacheOwner() {
        preferences().edit()
            .remove(KEY_CACHE_OWNER)
            .apply()
    }

    private fun preferences() =
        PreferenceManager.getDefaultSharedPreferences(context)

    companion object {

        /**
         * Prefix of the per-organization recent-repository files written by
         * [com.github.pockethub.android.ui.repo.RecentRepositories].
         */
        private const val RECENT_REPOS_PREFIX = "recent-repos-"

        /**
         * Name of the cache directory provided as `cacheDir` by
         * [com.github.pockethub.android.PocketHubModule].
         */
        private const val CACHE_DIR_NAME = "cache"

        /**
         * Preferences key holding the name of the account the local cache currently belongs to.
         */
        private const val KEY_CACHE_OWNER = "cached_account"
    }
}
