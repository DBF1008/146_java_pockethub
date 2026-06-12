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
import android.content.Context
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.github.pockethub.android.Database
import com.github.pockethub.android.Milestones
import com.meisolsson.githubsdk.core.ServiceGenerator
import com.meisolsson.githubsdk.core.TokenStore
import com.meisolsson.githubsdk.model.IssueState
import com.squareup.sqldelight.ColumnAdapter
import com.squareup.sqldelight.android.AndroidSqliteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Verifies the local-data isolation mechanism that runs when the active account changes:
 * a full clear on logout, the self-healing guard that only wipes when the account actually
 * switches, and a multi-account round trip that must leave no leftovers in either direction.
 */
@RunWith(RobolectricTestRunner::class)
class AccountSwitchManagerTest {

    private lateinit var context: Context
    private lateinit var database: Database
    private lateinit var manager: AccountSwitchManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = createInMemoryDatabase(context)
        manager = AccountSwitchManager(context, database)

        // Start from a known-clean tag/token so each test controls its own state.
        clearCacheOwnerTag()
        TokenStore.getInstance(context).saveToken(null)
    }

    @Test
    fun clearAllLocalData_removesEverythingForAccount() {
        seedAccountData()
        val recentRepos = writeRecentReposFile()
        val cacheEntry = writeCacheFile()
        TokenStore.getInstance(context).saveToken("token-123")
        manager.markCacheOwner(Account("octocat", ACCOUNT_TYPE))

        // Pre-conditions: everything is populated.
        assertEquals(Triple(1, 1, 1), dataCounts())
        assertNotNull(database.organizationsQueries.selectUser(ORG_ID).executeAsOneOrNull())
        assertTrue(recentRepos.exists())
        assertTrue(cacheEntry.exists())
        assertNotNull(TokenStore.getInstance(context).token)
        assertEquals("octocat", cacheOwnerTag())

        manager.clearAllLocalData()

        // Database rows gone.
        assertEquals(Triple(0, 0, 0), dataCounts())
        assertNull(database.organizationsQueries.selectUser(ORG_ID).executeAsOneOrNull())
        // Recent-repository files and the cache directory gone.
        assertFalse(recentRepos.exists())
        assertFalse(File(context.filesDir, CACHE_DIR).exists())
        // Auth token cleared so no request can run against the old account.
        assertNull(TokenStore.getInstance(context).token)
        // Ownership tag cleared.
        assertNull(cacheOwnerTag())
    }

    @Test
    fun enforceIsolation_clearsOnlyWhenAccountChanges() {
        val accountA = Account("userA", ACCOUNT_TYPE)
        val accountB = Account("userB", ACCOUNT_TYPE)

        manager.markCacheOwner(accountA)
        seedAccountData()

        // Switching to a different account clears the stale cache and re-tags it.
        val clearedOnSwitch = manager.enforceIsolation(accountB)
        assertTrue(clearedOnSwitch)
        assertEquals(Triple(0, 0, 0), dataCounts())
        assertEquals("userB", cacheOwnerTag())

        // Re-populate, then a no-op guard for the same account must keep the data intact.
        seedAccountData()
        val clearedOnSame = manager.enforceIsolation(accountB)
        assertFalse(clearedOnSame)
        assertEquals(Triple(1, 1, 1), dataCounts())
        assertEquals("userB", cacheOwnerTag())
    }

    @Test
    fun multiAccountRoundTrip_leavesNoLeftoversInEitherDirection() {
        val accountA = Account("userA", ACCOUNT_TYPE)
        val accountB = Account("userB", ACCOUNT_TYPE)

        // Account A populates the shared cache.
        manager.enforceIsolation(accountA)
        seedAccountData()
        assertEquals(Triple(1, 1, 1), dataCounts())

        // Switch A -> B: none of A's rows may survive.
        assertTrue(manager.enforceIsolation(accountB))
        assertEquals(Triple(0, 0, 0), dataCounts())
        assertEquals("userB", cacheOwnerTag())

        // B populates its own cache.
        seedAccountData()
        assertEquals(Triple(1, 1, 1), dataCounts())

        // Switch B -> A: none of B's rows may survive.
        assertTrue(manager.enforceIsolation(accountA))
        assertEquals(Triple(0, 0, 0), dataCounts())
        assertEquals("userA", cacheOwnerTag())
    }

    private fun seedAccountData() {
        database.organizationsQueries.insertOrg(ORG_ID)
        database.organizationsQueries.replaceUser(ORG_ID, "octocat", "The Octocat", "http://avatar")
        database.repositoriesQueries.replaceRepo(
            1L, "repo", ORG_ID, ORG_ID,
            false, false, "desc", 0, 0, "Kotlin",
            true, null, false, true, false
        )
        database.issue_filterQueries.insertOrReplaceIssueFilter(
            "filter-1", null, null, null, null, "asc", "created"
        )
    }

    /** (#users-and-orgs, #user-repositories, #issue-filters) currently in the database. */
    private fun dataCounts(): Triple<Int, Int, Int> = Triple(
        database.organizationsQueries.selectUserAndOrgs().executeAsList().size,
        database.repositoriesQueries.selectUserRepositories(ORG_ID).executeAsList().size,
        database.issue_filterQueries.getFilters().executeAsList().size
    )

    private fun writeRecentReposFile(): File =
        File(context.filesDir, "recent-repos-$ORG_ID.ser").apply { writeText("stale") }

    private fun writeCacheFile(): File {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        return File(dir, "entry").apply { writeText("stale") }
    }

    private fun cacheOwnerTag(): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_CACHE_OWNER, null)

    private fun clearCacheOwnerTag() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(KEY_CACHE_OWNER).apply()
    }

    private fun createInMemoryDatabase(context: Context): Database {
        // Mirrors ApplicationModule.provideDatabase, but with a null name for an isolated
        // in-memory database. The IssueState adapter is required by the constructor even though
        // these tests never touch the milestones table.
        val driver = AndroidSqliteDriver(Database.Schema, context, null)
        return Database(
            driver,
            Milestones.Adapter(object : ColumnAdapter<IssueState, String> {
                private val adapter = ServiceGenerator.moshi.adapter(IssueState::class.java)
                override fun decode(databaseValue: String): IssueState =
                    adapter.fromJsonValue(databaseValue)!!

                override fun encode(value: IssueState): String =
                    adapter.toJsonValue(value) as String
            })
        )
    }

    companion object {
        private const val ORG_ID = 10L
        private const val ACCOUNT_TYPE = "com.github.pockethub"
        private const val CACHE_DIR = "cache"
        private const val KEY_CACHE_OWNER = "cached_account"
    }
}
