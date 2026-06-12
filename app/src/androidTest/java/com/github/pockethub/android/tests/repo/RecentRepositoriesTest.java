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
package com.github.pockethub.android.tests.repo;

import android.content.Context;

import androidx.test.filters.SmallTest;

import com.github.pockethub.android.RequestWriter;
import com.github.pockethub.android.ui.repo.RecentRepositories;
import com.meisolsson.githubsdk.model.User;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static androidx.test.InstrumentationRegistry.getTargetContext;
import static com.github.pockethub.android.ui.repo.RecentRepositories.MAX_SIZE;
import static junit.framework.Assert.assertFalse;
import static junit.framework.TestCase.assertTrue;

/**
 * Unit tests of {@link RecentRepositories}
 */
@SmallTest
public class RecentRepositoriesTest {

    /**
     * Legacy organization-only cache format version.
     */
    private static final int LEGACY_VERSION = 2;

    private static User org(long id) {
        return User.builder()
                .id(id)
                .build();
    }

    private static File legacyFile(Context context, User org) {
        return new File(context.getFilesDir(), "recent-repos-" + org.id() + ".ser");
    }

    /**
     * Verify bad input
     */
    @Test
    public void testBadInput() {
        User org = User.builder()
                .id(20L)
                .build();

        RecentRepositories recent = new RecentRepositories(getTargetContext(), org);
        assertFalse(recent.contains(null));
        assertFalse(recent.contains(-1));
    }

    /**
     * Verify eviction
     */
    @Test
    public void testMaxReached() {
        User org = User.builder()
                .id(20L)
                .build();

        RecentRepositories recent = new RecentRepositories(getTargetContext(), org);

        for (int i = 0; i < MAX_SIZE; i++) {
            recent.add(i);
            assertTrue(recent.contains(i));
        }

        recent.add(MAX_SIZE + 1);
        assertTrue(recent.contains(MAX_SIZE + 1));
        assertFalse(recent.contains(0));

        for (int i = 1; i < MAX_SIZE; i++) {
            assertTrue(recent.contains(i));
        }
    }

    /**
     * Verify input/output to disk of {@link RecentRepositories} state
     */
    @Test
    public void testIO() {
        User org = User.builder()
                .id(20L)
                .build();

        RecentRepositories recent1 = new RecentRepositories(getTargetContext(), org);
        long id = 1234;
        recent1.add(id);
        assertTrue(recent1.contains(id));
        recent1.save();
        RecentRepositories recent2 = new RecentRepositories(getTargetContext(), org);
        assertTrue(recent2.contains(id));
    }

    /**
     * Verify repositories are scoped to organization
     */
    @Test
    public void testScopedStorage() {
        User org1 = User.builder()
                .id(20L)
                .build();

        RecentRepositories recent1 = new RecentRepositories(getTargetContext(), org1);
        long id1 = 1234;
        recent1.add(id1);
        assertTrue(recent1.contains(id1));

        User org2 = User.builder()
                .id(40L)
                .build();

        RecentRepositories recent2 = new RecentRepositories(getTargetContext(), org2);
        assertFalse(recent2.contains(id1));
        long id2 = 2345;
        recent2.add(id2);
        assertTrue(recent2.contains(id2));

        recent2.save();
        recent1 = new RecentRepositories(getTargetContext(), org1);
        assertFalse(recent1.contains(id2));
    }

    /**
     * Verify pinned repositories persist and are not subject to recent eviction
     */
    @Test
    public void testPinPersistsAndIsNotEvicted() {
        Context context = getTargetContext();
        String account = "pinUser";
        User org = org(1001L);
        RecentRepositories.getFile(context, account, org).delete();

        RecentRepositories recent = new RecentRepositories(context, account, org);

        // More pins than the recent cap must all be retained
        for (int i = 0; i < MAX_SIZE + 5; i++) {
            recent.pin(1000L + i);
        }

        // Recent eviction must not affect pins
        for (int i = 0; i < MAX_SIZE + 3; i++) {
            recent.add((long) i);
        }

        for (int i = 0; i < MAX_SIZE + 5; i++) {
            assertTrue(recent.isPinned(1000L + i));
        }

        recent.save();

        RecentRepositories reloaded = new RecentRepositories(context, account, org);
        for (int i = 0; i < MAX_SIZE + 5; i++) {
            assertTrue(reloaded.isPinned(1000L + i));
            assertTrue(reloaded.contains(1000L + i));
        }
    }

    /**
     * Verify unpinning returns a repository to the recently-viewed list
     */
    @Test
    public void testUnpinReturnsToRecent() {
        Context context = getTargetContext();
        String account = "pinUser";
        User org = org(1002L);
        RecentRepositories.getFile(context, account, org).delete();

        RecentRepositories recent = new RecentRepositories(context, account, org);
        recent.pin(7L);
        assertTrue(recent.isPinned(7L));
        assertFalse(recent.isRecent(7L));

        recent.unpin(7L);
        assertFalse(recent.isPinned(7L));
        assertTrue(recent.isRecent(7L));
        assertTrue(recent.contains(7L));
    }

    /**
     * Verify the list is isolated per account for the same organization
     */
    @Test
    public void testAccountIsolation() {
        Context context = getTargetContext();
        User org = org(1003L);
        String alice = "alice";
        String bob = "bob";
        RecentRepositories.getFile(context, alice, org).delete();
        RecentRepositories.getFile(context, bob, org).delete();

        RecentRepositories aliceRepos = new RecentRepositories(context, alice, org);
        aliceRepos.add(111L);
        aliceRepos.pin(222L);
        aliceRepos.save();

        RecentRepositories bobRepos = new RecentRepositories(context, bob, org);
        assertFalse(bobRepos.contains(111L));
        assertFalse(bobRepos.isPinned(222L));
        bobRepos.add(333L);
        bobRepos.save();

        RecentRepositories aliceAgain = new RecentRepositories(context, alice, org);
        assertTrue(aliceAgain.contains(111L));
        assertTrue(aliceAgain.isPinned(222L));
        assertFalse(aliceAgain.contains(333L));
    }

    /**
     * Verify stale entries are dropped when reconciling against the live list
     */
    @Test
    public void testReconcileDropsStaleEntries() {
        Context context = getTargetContext();
        String account = "recUser";
        User org = org(1004L);
        RecentRepositories.getFile(context, account, org).delete();

        RecentRepositories recent = new RecentRepositories(context, account, org);
        recent.add(10L);
        recent.add(20L);
        recent.pin(30L);
        recent.pin(40L);

        // Live list no longer contains 20 (recent) or 40 (pinned)
        assertTrue(recent.reconcile(Arrays.asList(10L, 30L)));
        assertTrue(recent.contains(10L));
        assertTrue(recent.isPinned(30L));
        assertFalse(recent.contains(20L));
        assertFalse(recent.isPinned(40L));
        assertFalse(recent.contains(40L));

        // Reconciling again with the same live set is a no-op
        assertFalse(recent.reconcile(Arrays.asList(10L, 30L)));
    }

    /**
     * Verify the legacy organization-only cache is migrated and removed
     */
    @Test
    public void testMigratesLegacyCache() {
        Context context = getTargetContext();
        String account = "migUser";
        User org = org(1005L);
        File newFile = RecentRepositories.getFile(context, account, org);
        File legacy = legacyFile(context, org);
        newFile.delete();
        legacy.delete();

        LinkedHashSet<Long> legacyIds = new LinkedHashSet<>();
        legacyIds.add(501L);
        legacyIds.add(502L);
        new RequestWriter(legacy, LEGACY_VERSION).write(legacyIds);
        assertTrue(legacy.exists());
        assertTrue(legacy.length() > 0);

        RecentRepositories recent = new RecentRepositories(context, account, org);
        // Triggers load + migration
        assertTrue(recent.contains(501L));
        assertTrue(recent.contains(502L));
        assertTrue(recent.isRecent(501L));

        // Legacy file is removed so it cannot be re-claimed by another account
        assertFalse(legacy.exists());

        // Migrated data is persisted in the new account-scoped file
        assertTrue(newFile.exists());
        RecentRepositories reloaded = new RecentRepositories(context, account, org);
        assertTrue(reloaded.contains(501L));
        assertTrue(reloaded.contains(502L));
    }

    /**
     * Verify a corrupt cache file is recovered from rather than crashing
     */
    @Test
    public void testRecoversFromCorruptFile() throws Exception {
        Context context = getTargetContext();
        String account = "corruptUser";
        User org = org(1006L);
        File newFile = RecentRepositories.getFile(context, account, org);
        newFile.delete();

        // Write non-empty garbage that is not a valid stream
        FileOutputStream out = new FileOutputStream(newFile);
        out.write(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        out.close();
        assertTrue(newFile.exists());
        assertTrue(newFile.length() > 0);

        RecentRepositories recent = new RecentRepositories(context, account, org);
        // Does not crash and behaves as an empty list
        assertFalse(recent.contains(123L));
        // The corrupt file is removed so it self-heals
        assertFalse(newFile.exists());

        // The list is usable and persists afterwards
        recent.add(123L);
        recent.save();
        RecentRepositories reloaded = new RecentRepositories(context, account, org);
        assertTrue(reloaded.contains(123L));
    }
}
