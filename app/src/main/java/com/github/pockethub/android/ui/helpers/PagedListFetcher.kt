package com.github.pockethub.android.ui.helpers

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

    /**
     * When true (the default) the list is refreshed from the first page on every
     * [Lifecycle.Event.ON_START]. Set to false to keep an already loaded list
     * (and a restored session) instead of reloading on each start, for example
     * after returning from a detail screen or a configuration change.
     */
    var refreshOnStart: Boolean = true

    /**
     * Whether at least one page has been loaded (or restored) since the current
     * data set was set up. Used to still perform the very first load when
     * [refreshOnStart] is disabled.
     */
    private var hasLoadedOnce = false

    var onPageLoaded: (MutableList<Item<*>>) -> MutableList<Item<*>> = { items -> items }

    /**
     * The current page.
     */
    private var page: Int = 1

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
        if (refreshOnStart || !hasLoadedOnce) {
            refresh()
        }
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

    /**
     * The next page that will be requested. Exposed so the paging cursor can be
     * captured in a [com.github.pockethub.android.ui.issue.SearchSession] and
     * restored later.
     */
    val currentPage: Int
        get() = page

    /**
     * Restore the paging state from a saved session without issuing a network
     * request. Marks the list as already loaded so the automatic [onStart]
     * refresh is skipped when [refreshOnStart] is disabled.
     */
    fun restoreState(page: Int, hasMore: Boolean) {
        this.page = page
        this.hasMore = hasMore
        this.hasLoadedOnce = true
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
        hasLoadedOnce = true
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.isRefreshing = false
        }

        val items = onPageLoaded(newItems)
        if (page == 1) {
            itemListHandler.update(items)
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
}