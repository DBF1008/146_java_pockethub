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
package com.github.pockethub.android.ui.repo;

import android.content.Context;
import android.os.AsyncTask;

import androidx.annotation.VisibleForTesting;

import com.github.pockethub.android.RequestReader;
import com.github.pockethub.android.RequestWriter;
import com.github.pockethub.android.accounts.AccountUtils;
import com.meisolsson.githubsdk.model.Repository;
import com.meisolsson.githubsdk.model.User;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;

import static java.lang.String.CASE_INSENSITIVE_ORDER;

/**
 * Model class for the repositories recently viewed and pinned under an
 * organization.
 * <p>
 * The list is scoped to both the signed-in account and the organization so that
 * switching accounts never exposes another account's history. State is persisted
 * locally and self-corrects against the live repository list, migrates the legacy
 * recent-only cache, and recovers from corrupt files.
 */
public class RecentRepositories implements Comparator<Repository>, Serializable {

    /**
     * Number of recently-viewed repositories retained per organization. Pinned
     * repositories are kept separately and are not subject to this limit.
     */
    public static final int MAX_SIZE = 5;

    private static final long serialVersionUID = 580345177644233739L;

    /**
     * On-disk format of the legacy recent-only cache (a {@link LinkedHashSet} of
     * repository ids keyed by organization only).
     */
    private static final int LEGACY_VERSION = 2;

    /**
     * On-disk format of the current {@link State} payload.
     */
    private static final int VERSION = 3;

    /**
     * Token used in place of an account login when no account is configured.
     */
    private static final String ANONYMOUS_ACCOUNT = "anon";

    /**
     * Persisted state for a single account + organization pair.
     */
    private static class State implements Serializable {

        private static final long serialVersionUID = 1L;

        String account;

        long orgId;

        LinkedHashSet<Long> recent = new LinkedHashSet<>();

        LinkedHashSet<Long> pinned = new LinkedHashSet<>();
    }

    private static String accountToken(final String account) {
        if (account == null || account.length() == 0) {
            return ANONYMOUS_ACCOUNT;
        }
        return Integer.toHexString(account.hashCode());
    }

    /**
     * File backing the current account + organization scoped state.
     */
    @VisibleForTesting
    public static File getFile(final Context context, final String account,
            final User organization) {
        return new File(context.getFilesDir(), "recent-repos-v3-"
                + accountToken(account) + "-" + organization.id() + ".ser");
    }

    /**
     * File backing the legacy recent-only cache (organization scoped only).
     */
    private static File getLegacyFile(final Context context,
            final User organization) {
        return new File(context.getFilesDir(), "recent-repos-"
                + organization.id() + ".ser");
    }

    private final File file;

    private final File legacyFile;

    private final String account;

    private final long orgId;

    private State state;

    /**
     * Create a recent repositories list for the given organization scoped to the
     * currently configured account.
     *
     * @param context
     * @param organization
     */
    public RecentRepositories(final Context context, final User organization) {
        this(context, AccountUtils.getLogin(context), organization);
    }

    /**
     * Create a recent repositories list for an explicit account. Primarily a
     * seam for tests; production code should use
     * {@link #RecentRepositories(Context, User)}.
     *
     * @param context
     * @param account
     * @param organization
     */
    @VisibleForTesting
    public RecentRepositories(final Context context, final String account,
            final User organization) {
        this.account = account != null ? account : ANONYMOUS_ACCOUNT;
        this.orgId = organization.id();
        this.file = getFile(context, this.account, organization);
        this.legacyFile = getLegacyFile(context, organization);
    }

    private State newState() {
        State created = new State();
        created.account = account;
        created.orgId = orgId;
        return created;
    }

    private static void ensureSets(final State target) {
        if (target.recent == null) {
            target.recent = new LinkedHashSet<>();
        }
        if (target.pinned == null) {
            target.pinned = new LinkedHashSet<>();
        }
    }

    private void load() {
        Object raw = new RequestReader(file, VERSION).read();
        State loaded = raw instanceof State ? (State) raw : null;
        if (loaded != null && account.equals(loaded.account)
                && orgId == loaded.orgId) {
            ensureSets(loaded);
            state = loaded;
            trim();
            return;
        }

        // A non-empty file that failed to deserialize is corrupt, and one that
        // deserializes but belongs to a different account/organization is stale
        // (e.g. an account switch or an id-token collision). Either way, drop it
        // so it self-heals and isolation is restored.
        if (file.exists() && file.length() > 0) {
            file.delete();
        }

        State migrated = migrateLegacy();
        if (migrated != null) {
            state = migrated;
            trim();
            save();
            // The legacy cache had no account scoping, so it can only be claimed
            // once. Remove it after migration to prevent another account from
            // inheriting this account's history.
            legacyFile.delete();
            return;
        }

        state = newState();
    }

    @SuppressWarnings("unchecked")
    private State migrateLegacy() {
        Object raw = new RequestReader(legacyFile, LEGACY_VERSION).read();
        if (!(raw instanceof LinkedHashSet)) {
            return null;
        }
        LinkedHashSet<Long> legacy = (LinkedHashSet<Long>) raw;
        if (legacy.isEmpty()) {
            return null;
        }
        State created = newState();
        created.recent = new LinkedHashSet<>(legacy);
        return created;
    }

    private void ensureLoaded() {
        if (state == null) {
            load();
        }
    }

    private void trim() {
        Iterator<Long> iterator = state.recent.iterator();
        while (iterator.hasNext() && state.recent.size() > MAX_SIZE) {
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * Add repository to the recently-viewed list.
     *
     * @param repo
     * @return this recent list
     */
    public RecentRepositories add(final Repository repo) {
        return repo != null ? add(repo.id()) : this;
    }

    /**
     * Add id to the recently-viewed list. Pinned repositories are left pinned.
     *
     * @param id
     * @return this recent list
     */
    public RecentRepositories add(final long id) {
        ensureLoaded();
        if (state.pinned.contains(id)) {
            return this;
        }
        state.recent.remove(id);
        state.recent.add(id);
        trim();
        return this;
    }

    /**
     * Remove repository from the recently-viewed list (pins are untouched).
     *
     * @param repo
     * @return this recent list
     */
    public RecentRepositories remove(final Repository repo) {
        return repo != null ? remove(repo.id()) : this;
    }

    /**
     * Remove id from the recently-viewed list (pins are untouched).
     *
     * @param id
     * @return this recent list
     */
    public RecentRepositories remove(final long id) {
        ensureLoaded();
        state.recent.remove(id);
        return this;
    }

    /**
     * Pin repository so it is always shown at the top of the list.
     *
     * @param repo
     * @return this recent list
     */
    public RecentRepositories pin(final Repository repo) {
        return repo != null ? pin(repo.id()) : this;
    }

    /**
     * Pin id so it is always shown at the top of the list.
     *
     * @param id
     * @return this recent list
     */
    public RecentRepositories pin(final long id) {
        ensureLoaded();
        state.recent.remove(id);
        state.pinned.add(id);
        return this;
    }

    /**
     * Unpin repository, returning it to the recently-viewed list.
     *
     * @param repo
     * @return this recent list
     */
    public RecentRepositories unpin(final Repository repo) {
        return repo != null ? unpin(repo.id()) : this;
    }

    /**
     * Unpin id, returning it to the recently-viewed list.
     *
     * @param id
     * @return this recent list
     */
    public RecentRepositories unpin(final long id) {
        ensureLoaded();
        state.pinned.remove(id);
        state.recent.remove(id);
        state.recent.add(id);
        trim();
        return this;
    }

    /**
     * Drop any recent or pinned entries whose id is not present in the given live
     * set of repository ids. Used to self-correct after repositories are renamed,
     * transferred, or deleted.
     *
     * @param liveIds ids of the repositories currently known for the organization
     * @return true if any entry was removed, false otherwise
     */
    public boolean reconcile(final Collection<Long> liveIds) {
        ensureLoaded();
        boolean changed = state.pinned.retainAll(liveIds);
        changed |= state.recent.retainAll(liveIds);
        return changed;
    }

    /**
     * Persist the list asynchronously on a background thread.
     *
     * @return this recent list
     */
    public RecentRepositories saveAsync() {
        if (state != null) {
            new AsyncTask<Void, Void, Void>() {

                @Override
                protected Void doInBackground(Void... params) {
                    save();
                    return null;
                }
            }.execute();
        }
        return this;
    }

    /**
     * Persist the list.
     *
     * @return this recent list
     */
    public RecentRepositories save() {
        final State current = state;
        if (current != null) {
            new RequestWriter(file, VERSION).write(current);
        }
        return this;
    }

    /**
     * Is the given repository in the list (recent or pinned)?
     *
     * @param repository
     * @return true if recent or pinned, false otherwise
     */
    public boolean contains(Repository repository) {
        return repository != null && contains(repository.id());
    }

    /**
     * Is the given repository id in the list (recent or pinned)?
     *
     * @param id
     * @return true if recent or pinned, false otherwise
     */
    public boolean contains(long id) {
        ensureLoaded();
        return state.pinned.contains(id) || state.recent.contains(id);
    }

    /**
     * Is the given repository pinned?
     *
     * @param repository
     * @return true if pinned, false otherwise
     */
    public boolean isPinned(Repository repository) {
        return repository != null && isPinned(repository.id());
    }

    /**
     * Is the given repository id pinned?
     *
     * @param id
     * @return true if pinned, false otherwise
     */
    public boolean isPinned(long id) {
        ensureLoaded();
        return state.pinned.contains(id);
    }

    /**
     * Is the given repository in the recently-viewed list (and not pinned)?
     *
     * @param repository
     * @return true if recently viewed, false otherwise
     */
    public boolean isRecent(Repository repository) {
        return repository != null && isRecent(repository.id());
    }

    /**
     * Is the given repository id in the recently-viewed list (and not pinned)?
     *
     * @param id
     * @return true if recently viewed, false otherwise
     */
    public boolean isRecent(long id) {
        ensureLoaded();
        return state.recent.contains(id);
    }

    private int rank(final Repository repo) {
        final Long id = repo.id();
        if (id == null) {
            return 2;
        }
        if (isPinned(id)) {
            return 0;
        }
        if (isRecent(id)) {
            return 1;
        }
        return 2;
    }

    @Override
    public int compare(final Repository lhs, final Repository rhs) {
        final int lRank = rank(lhs);
        final int rRank = rank(rhs);
        if (lRank != rRank) {
            return lRank < rRank ? -1 : 1;
        }

        final int order = CASE_INSENSITIVE_ORDER.compare(lhs.name(),
                rhs.name());
        if (order == 0) {
            if (orgId == lhs.owner().id()) {
                return -1;
            } else if (orgId == rhs.owner().id()) {
                return 1;
            } else {
                return CASE_INSENSITIVE_ORDER.compare(
                        lhs.owner().login(), rhs.owner().login());
            }
        } else {
            return order;
        }
    }
}
