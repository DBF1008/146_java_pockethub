package com.github.pockethub.android.ui.helpers

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.pockethub.android.R
import com.github.pockethub.android.rx.AutoDisposeUtils
import com.meisolsson.githubsdk.model.Page
import com.xwray.groupie.Item
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import io.reactivex.schedulers.Schedulers
import retrofit2.Response

class PagedListFetcher<E>(
    private val swipeRefreshLayout: SwipeRefreshLayout?,
    private val lifecycle: Lifecycle,
    private val itemListHandler: ItemListHandler,
    private val showError: (Throwable) -> Unit,
    private val loadPage: (page: Int) -> Single<Response<Page<E>>>,
    private val createItem: (item: E) -> Item<*>
): LifecycleObserver {

    /**
     * Disposable for data load request.
     */
    private var dataLoadDisposable: Disposable = Disposables.disposed()

    private var isLoading = false

    var hasMore: Boolean = true

    var onPageLoaded: (MutableList<Item<*>>) -> MutableList<Item<*>> = { items -> items }

    /**
     * The current page.
     */
    private var page: Int = 1

    /**
     * Whether state was restored from a saved instance (rotation / process death).
     * When true, [onStart] skips the automatic refresh so the restored page and
     * scroll position are preserved.
     */
    private var restored: Boolean = false

    init {
        lifecycle.addObserver(this)
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(this::refresh)
            swipeRefreshLayout.setColorSchemeResources(
                R.color.pager_title_background_top_start,
                R.color.pager_title_background_end,
                R.color.text_link,
                R.color.pager_title_background_end)
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    private fun onStart() {
        // After a config-change restore the fragment recreates with its items
        // already populated; skip the refresh so the saved page / scroll
        // position survive.  Returning from a detail screen fires ON_START
        // without the restored flag, so a normal refresh runs and the list
        // picks up any changes made while the user was away.
        if (restored && !itemListHandler.isEmpty()) {
            return
        }
        refresh()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private fun onPause() {

        isLoading = false
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.isRefreshing = false
        }
    }

    fun refresh() {
        page = 1
        hasMore = true
        isLoading = false
        restored = false

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.isRefreshing = true
        }

        fetchPage()
    }

    fun fetchNext() {
        if (!isLoading) {
            page++
            fetchPage()
        }
    }

    private fun fetchPage() {
        if (isLoading) {
            return
        }

        if (!dataLoadDisposable.isDisposed) {
            dataLoadDisposable.dispose()
        }

        isLoading = true

        dataLoadDisposable = loadPage(page)
            .map { it.body() }
            .map { page ->
                hasMore = page.next() != null
                page
            }
            .flatMap { page ->
                Observable.fromIterable<E>(page.items())
                    .map<Item<*>> { this.createItem(it) }
                    .toList()
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .`as`(AutoDisposeUtils.bindToLifecycle<MutableList<Item<*>>>(lifecycle))
            .subscribe(
                { this.onDataLoaded(it) },
                { this.onDataLoadError(it) }
            )
    }

    private fun onDataLoaded(newItems: MutableList<Item<*>>) {
        isLoading = false
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.isRefreshing = false
        }

        val items = onPageLoaded(newItems)
        if (page == 1) {
            itemListHandler.update(items)
            // A non-restored page-1 load is the result of an explicit user
            // action (new search, pull-to-refresh, return-from-detail): always
            // show the top of the fresh result set.
            if (!restored) {
                itemListHandler.scrollToTop()
            }
        } else {
            itemListHandler.addItems(items)
        }
    }

    private fun onDataLoadError(throwable: Throwable) {
        isLoading = false
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.isRefreshing = false
        }

        showError(throwable)
    }

    /**
     * Persist the current pagination cursor ([page]) and [hasMore] flag into
     * [outState] so they can be recovered via [restoreState] after a
     * configuration change or process death.
     */
    fun saveState(outState: Bundle, keyPrefix: String) {
        outState.putInt("${keyPrefix}_page", page)
        outState.putBoolean("${keyPrefix}_has_more", hasMore)
    }

    /**
     * Recover pagination state previously written by [saveState].  Sets the
     * internal `restored` flag so that the next [onStart] event does **not**
     * trigger a redundant refresh.
     */
    fun restoreState(savedInstanceState: Bundle, keyPrefix: String) {
        page = savedInstanceState.getInt("${keyPrefix}_page", 1)
        hasMore = savedInstanceState.getBoolean("${keyPrefix}_has_more", true)
        restored = true
    }
}