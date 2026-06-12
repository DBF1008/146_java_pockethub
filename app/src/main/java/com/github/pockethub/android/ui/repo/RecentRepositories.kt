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
package com.github.pockethub.android.ui.repo

import android.content.Context
import android.os.AsyncTask
import android.util.Log
import com.github.pockethub.android.Database
import com.github.pockethub.android.RequestReader
import com.meisolsson.githubsdk.model.Repository
import com.squareup.sqldelight.executeAsOneOrNull
import java.io.File
import java.util.Comparator
import java.util.LinkedHashSet
import java.util.TreeMap

/**
 * Manages recently viewed and pinned repositories for a given organization.
 *
 * Backed by SQLDelight for reliable local persistence with account isolation.
 * Automatically migrates data from the legacy `.ser` file format on first
 * access and recovers gracefully from corrupted cache files.
 *
 * Sort order: pinned repos (by pin order) → recent repos (by last accessed
 * descending) → remaining repos (alphabetical). Within every group, repos
 * owned by the current organisation sort first, then by owner login.
 */
class RecentRepositories(
    private val context: Context,
    private val organization: com.meisolsson.githubsdk.model.User,
    private val database: Database
) : Comparator<Repository> {

    companion object {
        /** Maximum number of unpinned recent repos kept per organisation. */
        const val MAX_RECENT = 10

        /** Legacy serialisation version used by the old `.ser` files. */
        private const val OLD_VERSION = 2

        private const val TAG = "RecentRepositories"
    }

    private val accountLogin: String =
        com.github.pockethub.android.accounts.AccountUtils.getLogin(context) ?: ""
    private val orgId: Long = organization.id()!!
    private val orgIdInt: Int = organization.id()!!.toInt()

    /** Pinned repo IDs in pin-order. */
    private var pinnedIds: MutableList<Long> = mutableListOf()

    /** Unpinned recent repo IDs ordered most-recent-first. */
    private var recentIds: MutableList<Long> = mutableListOf()

    /** Position maps for O(1) lookups during sorting. */
    private var pinnedPos: MutableMap<Long, Int> = mutableMapOf()
    private var recentPos: MutableMap<Long, Int> = mutableMapOf()

    private var loaded = false

    // ------------------------------------------------------------------ load

    @Suppress("UNCHECKED_CAST")
    private fun load() {
        if (loaded) return

        try {
            loadFromDb()
            if (pinnedIds.isEmpty() && recentIds.isEmpty()) {
                migrateOldCache()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted recent-repos data for org=$orgId; recovering", e)
            recover()
        }
        loaded = true
    }

    private fun loadFromDb() {
        val queries = database.recent_reposQueries
        val rows = queries.selectRecent(accountLogin, orgId).executeAsList()

        pinnedIds = mutableListOf()
        recentIds = mutableListOf()
        pinnedPos = mutableMapOf()
        recentPos = mutableMapOf()

        var pIdx = 0
        var rIdx = 0
        for (row in rows) {
            if (row.pinned != 0L) {
                pinnedIds.add(row.repo_id)
                pinnedPos[row.repo_id] = pIdx++
            } else {
                recentIds.add(row.repo_id)
                recentPos[row.repo_id] = rIdx++
            }
        }
    }

    // ------------------------------------------------------------- migration

    private fun migrateOldCache() {
        val oldFile = getLegacyFile()
        if (!oldFile.exists() || oldFile.length() == 0L) return

        try {
            val oldIds: LinkedHashSet<Long>? =
                RequestReader(oldFile, OLD_VERSION).read()
            if (oldIds != null && oldIds.isNotEmpty()) {
                val queries = database.recent_reposQueries
                val now = System.currentTimeMillis()
                for ((index, repoId) in oldIds.withIndex()) {
                    queries.insertRecent(
                        accountLogin, repoId, orgId,
                        0L, null, now - (oldIds.size - index)
                    )
                }
                loadFromDb()
                Log.i(TAG, "Migrated ${oldIds.size} entries from legacy cache for org=$orgId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy cache migration failed for org=$orgId", e)
        }

        // Always remove the legacy file — even on failure — to avoid
        // retrying on every launch.
        oldFile.delete()
    }

    // -------------------------------------------------------------- recovery

    /**
     * Reset in-memory state and clear any partial database rows for this
     * account/org combination so we start with a clean slate.
     */
    private fun recover() {
        pinnedIds = mutableListOf()
        recentIds = mutableListOf()
        pinnedPos = mutableMapOf()
        recentPos = mutableMapOf()
        try {
            val all = database.recent_reposQueries
                .selectRecent(accountLogin, orgId).executeAsList()
            for (row in all) {
                database.recent_reposQueries
                    .removeRecent(accountLogin, row.repo_id)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Recovery cleanup failed", e)
        }
    }

    // ------------------------------------------------------------------- add

    /**
     * Record that [repo] was accessed, adding it to the recent list (or
     * bumping its last-accessed timestamp if it already exists). Unpinned
     * entries beyond [MAX_RECENT] are evicted oldest-first.
     */
    fun add(repo: Repository?): RecentRepositories {
        if (repo == null) return this
        return add(repo.id()!!)
    }

    /**
     * Record that the repo with [id] was accessed.
     */
    fun add(id: Long): RecentRepositories {
        if (!loaded) load()

        val queries = database.recent_reposQueries
        val now = System.currentTimeMillis()

        val existing = queries.selectByRepo(accountLogin, id)
            .executeAsList().firstOrNull()

        if (existing == null) {
            queries.insertRecent(accountLogin, id, orgId, 0L, null, now)
        } else {
            queries.updateLastAccessed(now, accountLogin, id)
        }

        trimUnpinned()
        loadFromDb()
        return this
    }

    // ---------------------------------------------------------------- remove

    /**
     * Remove [repo] from the recent/pinned list entirely.
     */
    fun remove(repo: Repository?): RecentRepositories {
        if (repo == null) return this
        return remove(repo.id()!!)
    }

    /**
     * Remove the repo with [id] from the recent/pinned list entirely.
     */
    fun remove(id: Long): RecentRepositories {
        if (!loaded) load()

        database.recent_reposQueries.removeRecent(accountLogin, id)
        pinnedIds.remove(id)
        recentIds.remove(id)
        pinnedPos.remove(id)
        recentPos.remove(id)
        return this
    }

    // ------------------------------------------------------------------- pin

    /**
     * Pin [repo] so it always appears at the top. Pinned repos are ordered
     * by the sequence in which they were pinned.
     */
    fun pin(repo: Repository?): RecentRepositories {
        if (repo == null) return this
        return pin(repo.id()!!)
    }

    /**
     * Pin the repo with [id].
     */
    fun pin(id: Long): RecentRepositories {
        if (!loaded) load()
        if (pinnedPos.containsKey(id)) return this

        val queries = database.recent_reposQueries
        val maxOrder = queries.maxPinOrder(accountLogin, orgId)
            .executeAsOneOrNull() ?: -1L

        val existing = queries.selectByRepo(accountLogin, id)
            .executeAsList().firstOrNull()
        val now = System.currentTimeMillis()

        if (existing == null) {
            queries.insertRecent(accountLogin, id, orgId, 1L, maxOrder + 1, now)
        } else {
            queries.updatePinned(1L, maxOrder + 1, accountLogin, id)
        }

        loadFromDb()
        return this
    }

    // ----------------------------------------------------------------- unpin

    /**
     * Unpin [repo] so it returns to the recent list.
     */
    fun unpin(repo: Repository?): RecentRepositories {
        if (repo == null) return this
        return unpin(repo.id()!!)
    }

    /**
     * Unpin the repo with [id].
     */
    fun unpin(id: Long): RecentRepositories {
        if (!loaded) load()
        if (!pinnedPos.containsKey(id)) return this

        database.recent_reposQueries.updatePinned(0L, null, accountLogin, id)
        loadFromDb()
        trimUnpinned()
        loadFromDb()
        return this
    }

    // -------------------------------------------------------------- contains

    /**
     * Is [repository] in the recent or pinned list?
     */
    fun contains(repository: Repository?): Boolean {
        if (repository == null) return false
        return contains(repository.id()!!)
    }

    /**
     * Is the repo with [id] in the recent or pinned list?
     */
    fun contains(id: Long): Boolean {
        if (!loaded) load()
        return pinnedPos.containsKey(id) || recentPos.containsKey(id)
    }

    // --------------------------------------------------------------- isPinned

    /**
     * Is [repository] currently pinned?
     */
    fun isPinned(repository: Repository?): Boolean {
        if (repository == null) return false
        return isPinned(repository.id()!!)
    }

    /**
     * Is the repo with [id] currently pinned?
     */
    fun isPinned(id: Long): Boolean {
        if (!loaded) load()
        return pinnedPos.containsKey(id)
    }

    // --------------------------------------------------------- save / legacy

    /**
     * Persist recent list asynchronously on a background thread.
     *
     * With SQLDelight every mutation is committed immediately, so this is
     * now a no-op kept for API compatibility.
     */
    fun saveAsync(): RecentRepositories = this

    /**
     * Persist recent list synchronously.
     *
     * No-op — see [saveAsync].
     */
    fun save(): RecentRepositories = this

    // ------------------------------------------------------------- comparator

    /**
     * Compare two repositories for display ordering.
     *
     * 1. Pinned repos sort first, ordered by pin order.
     * 2. Recent repos sort next, ordered by last accessed (most recent first).
     * 3. All remaining repos sort alphabetically by name.
     * 4. Within each group, ties are broken: repos owned by the current org
     *    first, then case-insensitive owner login.
     */
    override fun compare(lhs: Repository, rhs: Repository): Int {
        if (!loaded) load()

        val lId = lhs.id()!!
        val rId = rhs.id()!!

        val lPinned = pinnedPos.containsKey(lId)
        val rPinned = pinnedPos.containsKey(rId)

        // Pinned group
        if (lPinned && rPinned) {
            val pCmp = pinnedPos[lId]!!.compareTo(pinnedPos[rId]!!)
            if (pCmp != 0) return pCmp
            return tiebreakByName(lhs, rhs)
        }
        if (lPinned) return -1
        if (rPinned) return 1

        // Recent group
        val lRecent = recentPos.containsKey(lId)
        val rRecent = recentPos.containsKey(rId)
        if (lRecent && rRecent) {
            val rCmp = recentPos[lId]!!.compareTo(recentPos[rId]!!)
            if (rCmp != 0) return rCmp
            return tiebreakByName(lhs, rhs)
        }
        if (lRecent) return -1
        if (rRecent) return 1

        // Neither — alphabetical
        return tiebreakByName(lhs, rhs)
    }

    private fun tiebreakByName(lhs: Repository, rhs: Repository): Int {
        val order = String.CASE_INSENSITIVE_ORDER.compare(lhs.name(), rhs.name())
        if (order != 0) return order
        if (orgIdInt == lhs.owner()!!.id()!!.toInt()) return -1
        if (orgIdInt == rhs.owner()!!.id()!!.toInt()) return 1
        return String.CASE_INSENSITIVE_ORDER.compare(
            lhs.owner()!!.login(), rhs.owner()!!.login()
        )
    }

    // --------------------------------------------------------------- trimming

    /**
     * Evict the oldest unpinned entries that exceed [MAX_RECENT].
     */
    private fun trimUnpinned() {
        val queries = database.recent_reposQueries
        val count = queries.countUnpinnedForOrg(accountLogin, orgId)
            .executeAsOne()
        if (count <= MAX_RECENT.toLong()) return

        val oldest = queries.selectUnpinned(accountLogin, orgId).executeAsList()
        val excess = (count - MAX_RECENT).toInt()
        for (i in 0 until excess.coerceAtMost(oldest.size)) {
            queries.removeRecent(accountLogin, oldest[i].repo_id)
        }
    }

    // ----------------------------------------------------------------- files

    private fun getLegacyFile(): File {
        return File(context.filesDir, "recent-repos-$orgIdInt.ser")
    }
}
