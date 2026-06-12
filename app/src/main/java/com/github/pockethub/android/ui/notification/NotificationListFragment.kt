package com.github.pockethub.android.ui.notification

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import com.github.pockethub.android.ui.helpers.ItemListHandler
import com.github.pockethub.android.ui.helpers.ListFetcher
import com.github.pockethub.android.R
import com.github.pockethub.android.core.issue.IssueUriMatcher
import com.github.pockethub.android.rx.AutoDisposeUtils
import com.github.pockethub.android.ui.base.BaseFragment
import com.github.pockethub.android.ui.issue.IssuesViewActivity
import com.github.pockethub.android.ui.item.notification.NotificationHeaderItem
import com.github.pockethub.android.ui.item.notification.NotificationItem
import com.github.pockethub.android.util.ToastUtils
import com.meisolsson.githubsdk.model.NotificationThread
import com.meisolsson.githubsdk.model.Page
import com.meisolsson.githubsdk.model.Repository
import com.meisolsson.githubsdk.model.request.NotificationReadRequest
import com.meisolsson.githubsdk.service.activity.NotificationService
import com.xwray.groupie.Item
import com.xwray.groupie.OnItemClickListener
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.fragment_item_list.view.*
import java.util.HashMap
import javax.inject.Inject

// NotificationThread
class NotificationListFragment : BaseFragment(), NotificationReadListener {

    @Inject
    protected lateinit var notificationService: NotificationService

    private lateinit var listFetcher: ListFetcher<NotificationThread>

    private lateinit var itemListHandler: ItemListHandler

    /**
     * Current notifications backing the list. Single source of truth that all
     * optimistic mutations operate on; [buildItems] renders it (re-deriving the
     * repository group headers each time so they stay consistent).
     */
    private val threads = mutableListOf<NotificationThread>()

    /**
     * Filters for the request to GitHub.
     */
    private val filters = HashMap<String, Any>()

    protected val errorMessage: Int
        get() = R.string.error_notifications_load

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (arguments!!.containsKey(EXTRA_FILTER)) {
            filters[arguments!!.getString(EXTRA_FILTER)] = true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_item_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)

        itemListHandler = ItemListHandler(
            view.list,
            view.empty,
            lifecycle,
            activity,
            OnItemClickListener(this::onItemClick))

        listFetcher = ListFetcher(
            view.swipe_item,
            lifecycle,
            itemListHandler,
            { t -> ToastUtils.show(activity, errorMessage) },
            this::loadData,
            this::createItem
        )

        listFetcher.onDataLoaded = this::onDataLoaded
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.fragment_notifications, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.m_mark_all_read -> {
                markAllVisibleRead()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadData(forceRefresh: Boolean): Single<List<NotificationThread>> {
        return getPageAndNext(1)
            .flatMap { page -> Observable.fromIterable(page.items()) }
            .toList()
    }

    private fun onDataLoaded(newItems: MutableList<Item<*>>): MutableList<Item<*>> {
        threads.clear()
        threads.addAll(newItems.map { (it as NotificationItem).notificationThread })
        return buildItems()
    }

    /**
     * Render the current [threads] into a flat Groupie list, inserting one
     * [NotificationHeaderItem] before each contiguous repository group. Re-deriving
     * the headers from the threads on every render guarantees there are never
     * orphan, duplicate, or missing group headers after an optimistic mutation or
     * a rollback.
     */
    private fun buildItems(): MutableList<Item<*>> {
        val sorted = threads.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.repository()!!.fullName()!! })

        val result = mutableListOf<Item<*>>()
        var lastRepo: Repository? = null
        for (thread in sorted) {
            val repo = thread.repository()!!
            if (lastRepo == null || repo.fullName() != lastRepo.fullName()) {
                result.add(NotificationHeaderItem(repo, this))
            }
            result.add(NotificationItem(thread, this))
            lastRepo = repo
        }
        return result
    }

    /**
     * Push the current [threads] to the adapter. Groupie diffs against the
     * previous list using the stable item ids, so only the changed rows animate
     * and the scroll/group position of untouched rows is preserved.
     */
    private fun render() {
        itemListHandler.update(buildItems())
    }

    private fun restore(backup: List<NotificationThread>) {
        threads.clear()
        threads.addAll(backup)
        render()
    }

    private fun createItem(item: NotificationThread): Item<*> {
        return NotificationItem(item, this)
    }

    private fun getPageAndNext(i: Int): Observable<Page<NotificationThread>> {
        return notificationService
            .getNotifications(filters, i.toLong())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .flatMapObservable { response ->
                val page = response.body()!!
                if (page.next() == null) {
                    return@flatMapObservable notificationService
                        .getNotifications(filters, i.toLong())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .flatMapObservable { Observable.just(page) }
                }

                return@flatMapObservable Observable.just(page)
                    .concatWith(getPageAndNext(page.next()!!))
            }
    }

    override fun readNotification(thread: NotificationThread) {
        val backup = ArrayList(threads)
        threads.removeAll { it.id() == thread.id() }
        render()

        notificationService
            .markNotificationRead(thread.id())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .`as`(AutoDisposeUtils.bindToLifecycle(this))
            .subscribe({ }, {
                restore(backup)
                ToastUtils.show(activity, R.string.error_marking_notification_read)
            })
    }

    override fun readNotifications(repository: Repository?) {
        if (repository == null) {
            return
        }

        val backup = ArrayList(threads)
        threads.removeAll { it.repository()?.id() == repository.id() }
        render()

        notificationService
            .markAllRepositoryNotificationsRead(repository.owner()!!.login(),
                repository.name(), NotificationReadRequest.builder().build())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .`as`(AutoDisposeUtils.bindToLifecycle(this))
            .subscribe({ }, {
                restore(backup)
                ToastUtils.show(activity, R.string.error_marking_notifications_read)
            })
    }

    /**
     * Batch mark every notification currently visible in this tab (i.e. matching
     * the active filter) as read. Optimistically clears the list, then fans out a
     * per-repository request. Rollback is per repository: repos whose request
     * succeeds stay read, while a repo whose request fails has its group restored
     * (with its header re-derived). A single toast is shown if anything failed.
     */
    private fun markAllVisibleRead() {
        if (threads.isEmpty()) {
            return
        }

        val backup = ArrayList(threads)
        val repos = threads.mapNotNull { it.repository() }.distinctBy { it.id() }

        threads.clear()
        render()

        var anyFailure = false
        Observable.fromIterable(repos)
            .flatMapSingle { repo ->
                notificationService
                    .markAllRepositoryNotificationsRead(repo.owner()!!.login(),
                        repo.name(), NotificationReadRequest.builder().build())
                    .subscribeOn(Schedulers.io())
                    .map { Pair(repo, true) }
                    .onErrorReturn { Pair(repo, false) }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .`as`(AutoDisposeUtils.bindToLifecycle(this))
            .subscribe({ (repo, success) ->
                if (!success) {
                    anyFailure = true
                    threads.addAll(backup.filter { it.repository()?.id() == repo.id() })
                    render()
                }
            }, { }, {
                if (anyFailure) {
                    ToastUtils.show(activity, R.string.error_marking_some_notifications_read)
                }
            })
    }

    fun onItemClick(item: Item<*>, view: View) {
        if (item is NotificationItem) {
            val thread = item.notificationThread
            val url = thread.subject()!!.url()

            val issue = IssueUriMatcher.getApiIssue(url)
            if (issue != null) {
                val intent = IssuesViewActivity.createIntent(issue, thread.repository()!!)
                startActivity(intent)
            } else {
                ToastUtils.show(activity, R.string.releases_not_yet_in_app)
            }
        }
    }

    companion object {
        const val EXTRA_FILTER = "filter"
    }
}
