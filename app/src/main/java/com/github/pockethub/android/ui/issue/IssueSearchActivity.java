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
package com.github.pockethub.android.ui.issue;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuItemCompat;
import com.github.pockethub.android.R;
import com.github.pockethub.android.ui.base.BaseActivity;
import com.github.pockethub.android.ui.repo.RepositoryViewActivity;
import com.github.pockethub.android.util.InfoUtils;
import com.github.pockethub.android.util.ToastUtils;
import com.meisolsson.githubsdk.model.Repository;

import static android.app.SearchManager.APP_DATA;
import static android.app.SearchManager.QUERY;
import static android.content.Intent.*;
import static com.github.pockethub.android.Intents.EXTRA_REPOSITORY;

/**
 * Activity to search issues
 */
public class IssueSearchActivity extends BaseActivity {

    private static final String STATE_LAST_QUERY = "last_query";

    private Repository repository;

    private SearchIssueListFragment issueFragment;

    private String lastQuery;

    private SearchView searchView;

    @Override
    public boolean onCreateOptionsMenu(Menu options) {
        getMenuInflater().inflate(R.menu.activity_search, options);

        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        MenuItem searchItem = options.findItem(R.id.m_search);
        searchView = (SearchView) MenuItemCompat.getActionView(searchItem);
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));

        Bundle args = new Bundle();
        args.putParcelable(EXTRA_REPOSITORY, repository);
        searchView.setAppSearchData(args);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.m_search:
                searchView.post(() -> searchView.setQuery(lastQuery, false));
                return true;
            case R.id.m_clear:
                IssueSearchSuggestionsProvider.clear(this);
                ToastUtils.show(this, R.string.search_history_cleared);
                return true;
            case android.R.id.home:
                Intent intent = RepositoryViewActivity.Companion.createIntent(repository);
                intent.addFlags(FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_issue_search);

        ActionBar actionBar = getSupportActionBar();
        Bundle appData = getIntent().getBundleExtra(APP_DATA);
        if (appData != null) {
            repository = appData.getParcelable(EXTRA_REPOSITORY);
            if (repository != null) {
                actionBar.setSubtitle(InfoUtils.createRepoId(repository));
                actionBar.setDisplayHomeAsUpEnabled(true);
            }
        }

        issueFragment = (SearchIssueListFragment) getSupportFragmentManager()
            .findFragmentById(R.id.list);

        if (savedInstanceState != null) {
            lastQuery = savedInstanceState.getString(STATE_LAST_QUERY);
        }

        handleIntent(getIntent());

        // When the activity is recreated without an ACTION_SEARCH intent
        // (e.g. after rotation), handleIntent won't call search(), so the
        // title would be lost.  Restore it from the saved lastQuery.
        if (lastQuery != null) {
            getSupportActionBar().setTitle(lastQuery);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_LAST_QUERY, lastQuery);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        // Compare before handleIntent mutates lastQuery so we can tell whether
        // the user actually submitted a different search term.
        String newQuery = intent.getStringExtra(QUERY);
        boolean queryChanged = newQuery != null && !newQuery.equals(lastQuery);
        handleIntent(intent);
        // Only force a refresh when the user actually submitted a different
        // query.  After a configuration change the same intent is re-delivered
        // and the fragment's own lifecycle refresh already covers it; calling
        // refresh() again here would race with pull-to-refresh and waste a
        // network round-trip.
        if (queryChanged) {
            issueFragment.pagedListFetcher.refresh();
        }
    }

    private void handleIntent(Intent intent) {
        if (ACTION_SEARCH.equals(intent.getAction())) {
            search(intent.getStringExtra(QUERY));
        }
    }

    private void search(final String query) {
        lastQuery = query;
        getSupportActionBar().setTitle(query);
        IssueSearchSuggestionsProvider.save(this, query);
        issueFragment.setQuery(query);
    }
}
