package tachiyomi.ui.deeplink

import com.freeletics.rxredux.StateAccessor
import com.freeletics.rxredux.reduxStore
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Observable
import tachiyomi.core.rx.RxSchedulers
import tachiyomi.core.rx.addTo
import tachiyomi.domain.manga.interactor.GetOrAddMangaFromSource
import tachiyomi.source.model.MangaInfo
import tachiyomi.ui.base.BasePresenter
import javax.inject.Inject
import tachiyomi.ui.deeplink.MangaDeepLinkAction as Action
import tachiyomi.ui.deeplink.MangaDeepLinkViewState as ViewState

class MangaDeepLinkPresenter @Inject constructor(
  private val params: MangaDeepLinkParams,
  private val getOrAddMangaFromSource: GetOrAddMangaFromSource,
  private val schedulers: RxSchedulers
) : BasePresenter() {

  private val actions = PublishRelay.create<Action>()

  private val state = BehaviorRelay.create<ViewState>()

  val stateObserver: Observable<ViewState> = state

  init {
    actions
      .observeOn(schedulers.io)
      .reduxStore(
        initialState = ViewState(),
        sideEffects = listOf(::findMangaSideEffect),
        reducer = { state, action -> action.reduce(state) }
      )
      .distinctUntilChanged()
      .observeOn(schedulers.main)
      .subscribe(state::accept)
      .addTo(disposables)
  }

  @Suppress("unused_parameter")
  private fun findMangaSideEffect(
    actions: Observable<Action>,
    stateFn: StateAccessor<ViewState>
  ): Observable<Action> {
    if (params.sourceId == null || params.mangaKey == null || params.mangaKey.isEmpty()) {
      return Observable.just(Action.Error(
        Exception("Invalid input data: sourceId=${params.sourceId}, mangaKey=${params.mangaKey}"))
      )
    }

    val mangaInfo = MangaInfo(key = params.mangaKey, title = "")

    return getOrAddMangaFromSource.interact(mangaInfo, params.sourceId)
      .toObservable()
      .subscribeOn(schedulers.io)
      .map<Action> { Action.MangaReady(it.id) }
      .onErrorReturn(Action::Error)
  }

}
