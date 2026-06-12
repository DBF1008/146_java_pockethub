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
package com.github.pockethub.android.accounts;

import android.content.Context;
import android.util.Log;

import com.github.pockethub.android.Database;
import com.github.pockethub.android.core.commit.CommitStore;
import com.github.pockethub.android.core.gist.GistStore;
import com.github.pockethub.android.core.issue.IssueStore;
import com.meisolsson.githubsdk.core.TokenStore;

import java.io.File;
import java.io.FilenameFilter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages account session transitions during logout/login cycles.
 * <p>
 * Ensures that all local data (DB caches, in-memory stores, file caches, tokens)
 * is fully purged before any UI can display data for the new account, preventing
 * stale data from the previous account from flashing on screen.
 * <p>
 * The transition lifecycle:
 * <ol>
 *   <li>{@link #beginSwitch()} - sets transitioning flag, purges all caches</li>
 *   <li>New account login + sync occurs</li>
 *   <li>{@link #completeSwitch(String)} - clears transitioning flag with new login</li>
 * </ol>
 * <p>
 * During the transition window, UI components should check {@link #isTransitioning()}
 * and avoid loading or displaying data.
 */
@Singleton
public class AccountSessionManager {

    private static final String TAG = "AccountSessionManager";

    private static final String RECENT_REPOS_PREFIX = "recent-repos-";
    private static final String RECENT_REPOS_SUFFIX = ".ser";

    private final Context context;
    private final Database database;
    private final CommitStore commitStore;
    private final IssueStore issueStore;
    private final GistStore gistStore;

    private volatile boolean transitioning = false;

    private volatile String currentLogin;

    @Inject
    public AccountSessionManager(Context context,
                                 Database database,
                                 CommitStore commitStore,
                                 IssueStore issueStore,
                                 GistStore gistStore) {
        this.context = context;
        this.database = database;
        this.commitStore = commitStore;
        this.issueStore = issueStore;
        this.gistStore = gistStore;
    }

    /**
     * Begin an account switch transition.
     * <p>
     * This method:
     * <ol>
     *   <li>Sets the transitioning flag to prevent UI data loading</li>
     *   <li>Clears all database tables (repos, orgs, users, issue filters, labels, milestones)</li>
     *   <li>Clears all in-memory stores (commits, issues, gists)</li>
     *   <li>Deletes all recent-repos file caches</li>
     *   <li>Clears the TokenStore</li>
     *   <li>Clears the current login</li>
     * </ol>
     * <p>
     * This method is safe to call multiple times in succession.
     */
    public void beginSwitch() {
        Log.d(TAG, "Beginning account switch transition");
        transitioning = true;

        // 1. Clear all database tables
        clearDatabase();

        // 2. Clear all in-memory stores
        clearInMemoryStores();

        // 3. Delete all recent-repos files
        clearRecentReposFiles();

        // 4. Clear TokenStore
        clearTokenStore();

        // 5. Clear current login
        currentLogin = null;

        Log.d(TAG, "Account switch transition: all caches purged");
    }

    /**
     * Complete an account switch transition.
     * <p>
     * Called after the new account's orgs have been successfully loaded and
     * the initial sync is underway. Clears the transitioning flag so UI
     * components can resume normal data loading.
     *
     * @param newLogin the login name of the new active account
     */
    public void completeSwitch(String newLogin) {
        Log.d(TAG, "Completing account switch transition to: " + newLogin);
        currentLogin = newLogin;
        transitioning = false;
    }

    /**
     * Whether an account switch transition is currently in progress.
     * <p>
     * UI components should check this before loading or displaying data.
     * When true, they should show empty/loading states instead of potentially
     * stale data from the previous account.
     *
     * @return true if transitioning, false otherwise
     */
    public boolean isTransitioning() {
        return transitioning;
    }

    /**
     * Get the login name of the current active account.
     *
     * @return current login name, or null if no account is active
     */
    public String getCurrentLogin() {
        return currentLogin;
    }

    /**
     * Check whether the session is valid for the given login.
     * <p>
     * Returns true only when the given login matches the current active
     * account and no transition is in progress.
     *
     * @param login the login name to check
     * @return true if session is valid for the given login
     */
    public boolean isSessionValidFor(String login) {
        return !transitioning && login != null && login.equals(currentLogin);
    }

    private void clearDatabase() {
        try {
            database.transaction(() -> {
                // Clear repositories
                database.getRepositoriesQueries().clearRepositories();
                // Clear organizations and users
                database.getOrganizationsQueries().clearOrgs();
                database.getOrganizationsQueries().clearUsers();
                // Clear issue filters, labels, milestones, and filter labels
                database.getIssue_filterQueries().clearIssueFilters();
                database.getIssue_filterQueries().clearLabels();
                database.getIssue_filterQueries().clearIssueFilterLabels();
                database.getIssue_filterQueries().clearAllMilestones();
            });
            Log.d(TAG, "Database tables cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing database", e);
        }
    }

    private void clearInMemoryStores() {
        commitStore.clear();
        issueStore.clear();
        gistStore.clear();
        Log.d(TAG, "In-memory stores cleared");
    }

    private void clearRecentReposFiles() {
        File filesDir = context.getFilesDir();
        if (filesDir == null) {
            return;
        }

        File[] recentReposFiles = filesDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(RECENT_REPOS_PREFIX)
                        && name.endsWith(RECENT_REPOS_SUFFIX);
            }
        });

        if (recentReposFiles != null) {
            int deleted = 0;
            for (File file : recentReposFiles) {
                if (file.delete()) {
                    deleted++;
                }
            }
            Log.d(TAG, "Deleted " + deleted + " recent-repos files");
        }
    }

    private void clearTokenStore() {
        try {
            TokenStore tokenStore = TokenStore.getInstance(context);
            tokenStore.saveToken(null);
            Log.d(TAG, "TokenStore cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing TokenStore", e);
        }
    }
}
