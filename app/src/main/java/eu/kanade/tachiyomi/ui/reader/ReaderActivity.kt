package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.GestureDetectorCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.ui.base.activity.BaseRxActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.reader.ReaderPresenter.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderPresenter.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderPresenter.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PageLayout
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.ThemeUtil
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.hasSideNavBar
import eu.kanade.tachiyomi.util.system.isBottomTappable
import eu.kanade.tachiyomi.util.system.isLTR
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsets
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.hide
import eu.kanade.tachiyomi.util.view.isCollapsed
import eu.kanade.tachiyomi.util.view.isExpanded
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.updateLayoutParams
import eu.kanade.tachiyomi.util.view.updatePaddingRelative
import eu.kanade.tachiyomi.util.view.visible
import eu.kanade.tachiyomi.widget.SimpleAnimationListener
import eu.kanade.tachiyomi.widget.SimpleSeekBarListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.systemuihelper.SystemUiHelper
import nucleus.factory.RequiresPresenter
import timber.log.Timber
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Activity containing the reader of Tachiyomi. This activity is mostly a container of the
 * viewers, to which calls from the presenter or UI events are delegated.
 */
@RequiresPresenter(ReaderPresenter::class)
class ReaderActivity :
    BaseRxActivity<ReaderPresenter>(),
    SystemUiHelper.OnVisibilityChangeListener {

    lateinit var binding: ReaderActivityBinding

    /**
     * Preferences helper.
     */
    private val preferences by injectLazy<PreferencesHelper>()

    /**
     * The maximum bitmap size supported by the device.
     */
    val maxBitmapSize by lazy { GLUtil.maxTextureSize }

    /**
     * Viewer used to display the pages (pager, webtoon, ...).
     */
    var viewer: BaseViewer? = null
        private set

    /**
     * Whether the menu is currently visible.
     */
    var menuVisible = false
        private set

    /**
     * Whether the menu should stay visible.
     */
    var menuStickyVisible = false
        private set

    private var coroutine: Job? = null

    var fromUrl = false

    /**
     * System UI helper to hide status & navigation bar on all different API levels.
     */
    private var systemUi: SystemUiHelper? = null

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    /**
     * Current Bottom Sheet on display, used to dismiss
     */
    private var bottomSheet: BottomSheetDialog? = null

    var sheetManageNavColor = false

    var lightStatusBar = false

    private var snackbar: Snackbar? = null

    var intentPageNumber: Int? = null

    var isLoading = false

    companion object {
        @Suppress("unused")
        const val LEFT_TO_RIGHT = 1
        const val RIGHT_TO_LEFT = 2
        const val VERTICAL = 3
        const val WEBTOON = 4
        const val VERTICAL_PLUS = 5

        fun newIntent(context: Context, manga: Manga, chapter: Chapter): Intent {
            val intent = Intent(context, ReaderActivity::class.java)
            intent.putExtra("manga", manga.id)
            intent.putExtra("chapter", chapter.id)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return intent
        }
    }

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(ThemeUtil.nightMode(preferences.theme()))
        setTheme(ThemeUtil.theme(preferences.theme()))
        super.onCreate(savedInstanceState)
        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.windowLightStatusBar))
        lightStatusBar = a.getBoolean(0, false)
        a.recycle()
        setNotchCutoutMode()

        var systemUiFlag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiFlag = systemUiFlag.or(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
        binding.readerLayout.systemUiVisibility = when (lightStatusBar) {
            true -> binding.readerLayout.systemUiVisibility.or(systemUiFlag)
            false -> binding.readerLayout.systemUiVisibility.rem(systemUiFlag)
        }

        if (presenter.needsInit()) {
            fromUrl = handleIntentAction(intent)
            if (!fromUrl) {
                val manga = intent.extras!!.getLong("manga", -1)
                val chapter = intent.extras!!.getLong("chapter", -1)
                if (manga == -1L || chapter == -1L) {
                    finish()
                    return
                }
                presenter.init(manga, chapter)
            } else {
                binding.pleaseWait.visible()
            }
        }

        if (savedInstanceState != null) {
            menuVisible = savedInstanceState.getBoolean(::menuVisible.name)
            binding.readerNav.root.isVisible = menuVisible
        } else {
            binding.readerNav.root.gone()
        }

        binding.chaptersSheet.chaptersBottomSheet.setup(this)
        if (ThemeUtil.isBlueTheme(preferences.theme())) {
            binding.chaptersSheet.chapterRecycler.setBackgroundColor(getResourceColor(android.R.attr.colorBackground))
        }
        config = ReaderConfig()
        initializeMenu()
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        super.onDestroy()
        viewer?.destroy()
        binding.chaptersSheet.chaptersBottomSheet.adapter = null
        viewer = null
        config = null
        bottomSheet?.dismiss()
        bottomSheet = null
        snackbar?.dismiss()
        snackbar = null
    }

    /**
     * Called when the activity is saving instance state. Current progress is persisted if this
     * activity isn't changing configurations.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(::menuVisible.name, menuVisible)
        if (!isChangingConfigurations) {
            presenter.onSaveInstanceStateNonConfigurationChange()
        }
        super.onSaveInstanceState(outState)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply again System UI (for immersive mode).
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            when (menuStickyVisible) {
                true -> setMenuVisibility(false)
                false -> setMenuVisibility(menuVisible, animate = false)
            }
        }
    }

    /**
     * Called when the options menu of the binding.toolbar is being created. It adds our custom menu.
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val splitItem = menu?.findItem(R.id.action_shift_double_page)
        splitItem?.isVisible = (viewer as? PagerViewer)?.config?.doublePages ?: false
        (viewer as? PagerViewer)?.config?.let { config ->
            splitItem?.icon = ContextCompat.getDrawable(
                this,
                if ((!config.shiftDoublePage).xor(viewer is R2LPagerViewer)) R.drawable.ic_page_previous_outline_24dp else R.drawable.ic_page_next_outline_24dp
            )
        }
        setBottomNavButtons(preferences.pageLayout().get())
        return super.onPrepareOptionsMenu(menu)
    }

    fun setBottomNavButtons(pageLayout: Int) {
        val isDoublePage = pageLayout == PageLayout.DOUBLE_PAGES ||
            (pageLayout == PageLayout.AUTOMATIC && (viewer as? PagerViewer)?.config?.doublePages ?: false)
        binding.chaptersSheet.doublePage.setImageDrawable(
            ContextCompat.getDrawable(
                this,
                if (!isDoublePage) R.drawable.ic_single_page_24dp
                else R.drawable.ic_book_open_variant_24dp
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.chaptersSheet.doublePage.tooltipText =
                getString(
                    if (isDoublePage) R.string.switch_to_single
                    else R.string.switch_to_double
                )
        }
    }

    /**
     * Called when an item of the options menu was clicked. Used to handle clicks on our menu
     * entries.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_shift_double_page -> {
                (viewer as? PagerViewer)?.config?.let { config ->
                    config.shiftDoublePage = !config.shiftDoublePage
                    presenter.viewerChapters?.let {
                        (viewer as? PagerViewer)?.setChaptersDoubleShift(it)
                        invalidateOptionsMenu()
                    }
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun popToMain() {
        presenter.onBackPressed()
        if (fromUrl) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finishAfterTransition()
        } else {
            finish()
        }
    }

    /**
     * Called when the user clicks the back key or the button on the binding.toolbar. The call is
     * delegated to the presenter.
     */
    override fun onBackPressed() {
        if (binding.chaptersSheet.chaptersBottomSheet.sheetBehavior.isExpanded()) {
            binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.collapse()
            return
        }
        presenter.onBackPressed()
        finish()
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            presenter.loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            presenter.loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    /**
     * Initializes the reader menu. It sets up click listeners and the initial visibility.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeMenu() {
        // Set binding.toolbar
        setSupportActionBar(binding.toolbar)
        val primaryColor = ColorUtils.setAlphaComponent(
            getResourceColor(R.attr.colorSecondary),
            200
        )
        binding.appBar.setBackgroundColor(primaryColor)
        window.statusBarColor = Color.TRANSPARENT
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            popToMain()
        }

        binding.toolbar.setOnClickListener {
            presenter.manga?.id?.let { id ->
                val intent = SearchActivity.openMangaIntent(this, id)
                startActivity(intent)
            }
        }

        binding.chaptersSheet.doublePage.setOnClickListener {
            if (preferences.pageLayout().get() == PageLayout.AUTOMATIC) {
                (viewer as? PagerViewer)?.config?.let { config ->
                    config.doublePages = !config.doublePages
                    reloadChapters(config.doublePages, true)
                }
            } else {
                preferences.pageLayout().set(1 - preferences.pageLayout().get())
            }
        }
        binding.readerNav.leftChapter.setOnClickListener {
            if (isLoading) {
                return@setOnClickListener
            }
            val result = if (viewer is R2LPagerViewer) {
                presenter.loadNextChapter()
            } else {
                presenter.loadPreviousChapter()
            }
            if (result) {
                binding.readerNav.leftChapter.gone()
                binding.readerNav.leftProgress.visible()
            }
        }

        binding.readerNav.rightChapter.setOnClickListener {
            if (isLoading) {
                return@setOnClickListener
            }
            val result = if (viewer !is R2LPagerViewer) {
                presenter.loadNextChapter()
            } else {
                presenter.loadPreviousChapter()
            }
            if (result) {
                binding.readerNav.rightChapter.gone()
                binding.readerNav.rightProgress.visible()
            }
        }

        val readerNavGestureDetector = ReaderNavGestureDetector(this)
        val gestureDetector = GestureDetectorCompat(this, readerNavGestureDetector)
        with(binding.readerNav) {
            listOf(root, leftChapter, rightChapter, pageSeekbar).forEach {
                it.setOnTouchListener { _, event ->
                    val result = gestureDetector.onTouchEvent(event)
                    if (event?.action == MotionEvent.ACTION_UP) {
                        if (!result) {
                            val sheetBehavior = binding.chaptersSheet.root.sheetBehavior
                            binding.chaptersSheet.root.dispatchTouchEvent(event)
                            if (sheetBehavior?.state != BottomSheetBehavior.STATE_SETTLING && !sheetBehavior.isCollapsed()) {
                                sheetBehavior?.collapse()
                            }
                        }
                        if (readerNavGestureDetector.lockVertical) {
                            // event.action = MotionEvent.ACTION_CANCEL
                            return@setOnTouchListener true
                        }
                    } else if ((event?.action != MotionEvent.ACTION_UP || event.action != MotionEvent.ACTION_DOWN) && result) {
                        event.action = MotionEvent.ACTION_CANCEL
                        return@setOnTouchListener false
                    }
                    if (it == pageSeekbar) {
                        readerNavGestureDetector.lockVertical || (!readerNavGestureDetector.hasScrollHorizontal && event?.action != MotionEvent.ACTION_UP)
                    } else {
                        result
                    }
                }
            }
        }

        // Init listeners on bottom menu
        binding.readerNav.pageSeekbar.setOnSeekBarChangeListener(
            object : SimpleSeekBarListener() {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    if (viewer != null && fromUser) {
                        moveToPageIndex(value)
                    }
                }
            }
        )

        // Set initial visibility
        setMenuVisibility(menuVisible)
        binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.isHideable = !menuVisible
        if (!menuVisible) binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.hide()
        // val peek = binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.peekHeight ?: 30.dpToPx
        binding.readerLayout.doOnApplyWindowInsets { v, insets, _ ->
            sheetManageNavColor = when {
                insets.isBottomTappable() -> {
                    window.navigationBarColor = Color.TRANSPARENT
                    false
                }
                insets.hasSideNavBar() -> {
                    window.navigationBarColor = getResourceColor(R.attr.colorSecondary)
                    false
                }
                // if in portrait with 2/3 button mode, translucent nav bar
                else -> {
                    true
                }
            }

            binding.appBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.systemWindowInsetLeft
                rightMargin = insets.systemWindowInsetRight
            }
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.systemWindowInsetTop
            }
            binding.chaptersSheet.chaptersBottomSheet.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.systemWindowInsetLeft
                rightMargin = insets.systemWindowInsetRight
                height = 280.dpToPx + insets.systemWindowInsetBottom
            }
            binding.navLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = 12.dpToPx + insets.systemWindowInsetLeft
                rightMargin = 12.dpToPx + insets.systemWindowInsetRight
            }
            binding.chaptersSheet.chapterRecycler.updatePaddingRelative(bottom = insets.systemWindowInsetBottom)
            binding.viewerContainer.requestLayout()
        }
    }

    /**
     * Sets the visibility of the menu according to [visible] and with an optional parameter to
     * [animate] the views.
     */
    private fun setMenuVisibility(visible: Boolean, animate: Boolean = true) {
        menuVisible = visible
        if (visible) coroutine?.cancel()
        binding.viewerContainer.requestLayout()
        if (visible) {
            snackbar?.dismiss()
            systemUi?.show()
            binding.readerMenu.visible()

            if (binding.chaptersSheet.chaptersBottomSheet.sheetBehavior.isExpanded()) {
                binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.isHideable = false
            }
            if (!binding.chaptersSheet.chaptersBottomSheet.sheetBehavior.isExpanded() && sheetManageNavColor) {
                window.navigationBarColor = Color.TRANSPARENT
            }
            if (animate) {
                if (!menuStickyVisible) {
                    val toolbarAnimation = AnimationUtils.loadAnimation(this, R.anim.enter_from_top)
                    toolbarAnimation.setAnimationListener(
                        object : SimpleAnimationListener() {
                            override fun onAnimationStart(animation: Animation) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                            }
                        }
                    )
                    binding.appBar.startAnimation(toolbarAnimation)
                }
                binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.collapse()
            }
        } else {
            systemUi?.hide()

            if (animate) {
                val toolbarAnimation = AnimationUtils.loadAnimation(this, R.anim.exit_to_top)
                toolbarAnimation.setAnimationListener(
                    object : SimpleAnimationListener() {
                        override fun onAnimationEnd(animation: Animation) {
                            binding.readerMenu.gone()
                        }
                    }
                )
                binding.appBar.startAnimation(toolbarAnimation)
                BottomSheetBehavior.from(binding.chaptersSheet.chaptersBottomSheet).isHideable = true
                binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.hide()
            } else {
                binding.readerMenu.gone()
            }
        }
        menuStickyVisible = false
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer
     * and the binding.toolbar title.
     */
    fun setManga(manga: Manga) {
        val prevViewer = viewer
        val noDefault = manga.viewer == -1
        val mangaViewer = presenter.getMangaViewer()
        invalidateOptionsMenu()
        val newViewer = when (mangaViewer) {
            RIGHT_TO_LEFT -> R2LPagerViewer(this)
            VERTICAL -> VerticalPagerViewer(this)
            WEBTOON -> WebtoonViewer(this)
            VERTICAL_PLUS -> WebtoonViewer(this, hasMargins = true)
            else -> L2RPagerViewer(this)
        }

        if (noDefault && presenter.manga?.viewer!! > 0) {
            snackbar = binding.readerLayout.snack(
                getString(
                    R.string.reading_,
                    getString(
                        when (mangaViewer) {
                            RIGHT_TO_LEFT -> R.string.right_to_left_viewer
                            VERTICAL -> R.string.vertical_viewer
                            WEBTOON -> R.string.webtoon_style
                            else -> R.string.left_to_right_viewer
                        }
                    ).toLowerCase(Locale.getDefault())
                ),
                4000
            ) {
                if (mangaViewer != WEBTOON) setAction(R.string.use_default) {
                    presenter.setMangaViewer(0)
                }
            }
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            binding.viewerContainer.removeAllViews()
        }
        viewer = newViewer
        binding.chaptersSheet.doublePage.isVisible = viewer is PagerViewer
        binding.viewerContainer.addView(newViewer.getView())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (newViewer is R2LPagerViewer) {
                binding.readerNav.leftChapter.tooltipText = getString(R.string.next_chapter)
                binding.readerNav.rightChapter.tooltipText = getString(R.string.previous_chapter)
            } else {
                binding.readerNav.leftChapter.tooltipText = getString(R.string.previous_chapter)
                binding.readerNav.rightChapter.tooltipText = getString(R.string.next_chapter)
            }
        }

        if (newViewer is PagerViewer && preferences.pageLayout().get() == PageLayout.AUTOMATIC) {
            setDoublePageMode(newViewer)
        }

        binding.navigationOverlay.isLTR = !(viewer is L2RPagerViewer)
        binding.viewerContainer.setBackgroundColor(
            if (viewer is WebtoonViewer) {
                Color.BLACK
            } else {
                getResourceColor(android.R.attr.colorBackground)
            }
        )

        binding.toolbar.title = manga.title

        binding.readerNav.pageSeekbar.isRTL = newViewer is R2LPagerViewer

        binding.pleaseWait.visible()
        binding.pleaseWait.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in_long))
        invalidateOptionsMenu()
    }

    override fun onPause() {
        presenter.saveProgress()
        super.onPause()
    }

    fun reloadChapters(doublePages: Boolean, force: Boolean = false) {
        val pViewer = viewer as? PagerViewer ?: return
        if (!force && pViewer.config.autoDoublePages) {
            setDoublePageMode(pViewer)
        } else {
            pViewer.config.doublePages = doublePages
        }
        val currentChapter = presenter.getCurrentChapter()
        if (doublePages) {
            // If we're moving from singe to double, we want the current page to be the first page
            pViewer.config.shiftDoublePage = (
                binding.readerNav.pageSeekbar.progress +
                    (
                        currentChapter?.pages?.subList(0, binding.readerNav.pageSeekbar.progress)
                            ?.count { it.fullPage || it.isolatedPage } ?: 0
                        )
                ) % 2 != 0
        }
        presenter.viewerChapters?.let {
            pViewer.setChaptersDoubleShift(it)
        }
        invalidateOptionsMenu()
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the binding.toolbar.
     */
    fun setChapters(viewerChapters: ViewerChapters) {
        binding.pleaseWait.gone()
        viewer?.setChapters(viewerChapters)
        intentPageNumber?.let { moveToPageIndex(it) }
        intentPageNumber = null
        binding.toolbar.subtitle = viewerChapters.currChapter.chapter.name
        if (viewer is R2LPagerViewer) {
            binding.readerNav.leftChapter.alpha = if (viewerChapters.nextChapter != null) 1f else 0.5f
            binding.readerNav.rightChapter.alpha = if (viewerChapters.prevChapter != null) 1f else 0.5f
        } else {
            binding.readerNav.rightChapter.alpha = if (viewerChapters.nextChapter != null) 1f else 0.5f
            binding.readerNav.leftChapter.alpha = if (viewerChapters.prevChapter != null) 1f else 0.5f
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    fun setInitialChapterError(error: Throwable) {
        Timber.e(error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the binding.toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    fun setProgressDialog(show: Boolean) {
        if (!show) {
            binding.readerNav.leftChapter.visible()
            binding.readerNav.rightChapter.visible()

            binding.readerNav.leftProgress.gone()
            binding.readerNav.rightProgress.gone()
            binding.chaptersSheet.root.resetChapter()
        }
        if (show) {
            isLoading = show
        } else {
            scope.launchIO {
                delay(100)
                isLoading = show
            }
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    fun moveToPageIndex(index: Int) {
        val viewer = viewer ?: return
        val currentChapter = presenter.getCurrentChapter() ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    fun refreshChapters() {
        binding.chaptersSheet.chaptersBottomSheet.refreshList()
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    @SuppressLint("SetTextI18n")
    fun onPageSelected(page: ReaderPage, hasExtraPage: Boolean) {
        val newChapter = presenter.onPageSelected(page, hasExtraPage)
        val pages = page.chapter.pages ?: return

        val currentPage = if (hasExtraPage) {
            if (resources.isLTR) "${page.number}-${page.number + 1}" else "${page.number + 1}-${page.number}"
        } else {
            "${page.number}"
        }

        val totalPages = pages.size.toString()
        binding.pageNumber.text = if (resources.isLTR) "$currentPage/$totalPages" else "$totalPages/$currentPage"
        if (viewer is R2LPagerViewer) {
            binding.readerNav.rightPageText.text = currentPage
            binding.readerNav.leftPageText.text = totalPages
        } else {
            binding.readerNav.leftPageText.text = currentPage
            binding.readerNav.rightPageText.text = totalPages
        }
        if (!newChapter && binding.chaptersSheet.chaptersBottomSheet.shouldCollapse && binding.chaptersSheet.chaptersBottomSheet.sheetBehavior.isExpanded()) {
            binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.collapse()
        }
        if (binding.chaptersSheet.chaptersBottomSheet.selectedChapterId != page.chapter.chapter.id) {
            binding.chaptersSheet.chaptersBottomSheet.refreshList()
        }
        binding.chaptersSheet.chaptersBottomSheet.shouldCollapse = true

        // Set seekbar progress
        binding.readerNav.pageSeekbar.max = pages.lastIndex
        val progress = page.index + if (hasExtraPage) 1 else 0
        // For a double page, show the last 2 pages as if it was the final part of the seekbar
        binding.readerNav.pageSeekbar.progress = if (progress == pages.lastIndex) progress else page.index
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage, extraPage: ReaderPage? = null) {
        val items = if (extraPage != null) {
            listOf(
                MaterialMenuSheet.MenuSheetItem(
                    3,
                    R.drawable.ic_outline_share_24dp,
                    R.string.share_second_page
                ),
                MaterialMenuSheet.MenuSheetItem(
                    4,
                    R.drawable.ic_outline_save_24dp,
                    R.string.save_second_page
                ),
                MaterialMenuSheet.MenuSheetItem(
                    5,
                    R.drawable.ic_outline_photo_24dp,
                    R.string.set_second_page_as_cover
                ),
                MaterialMenuSheet.MenuSheetItem(
                    0,
                    R.drawable.ic_share_24dp,
                    R.string.share_first_page
                ),
                MaterialMenuSheet.MenuSheetItem(
                    1,
                    R.drawable.ic_save_24dp,
                    R.string.save_first_page
                ),
                MaterialMenuSheet.MenuSheetItem(
                    2,
                    R.drawable.ic_photo_24dp,
                    R.string.set_first_page_as_cover
                )
            )
        } else {
            listOf(
                MaterialMenuSheet.MenuSheetItem(
                    0,
                    R.drawable.ic_share_24dp,
                    R.string.share
                ),
                MaterialMenuSheet.MenuSheetItem(
                    1,
                    R.drawable.ic_save_24dp,
                    R.string.save
                ),
                MaterialMenuSheet.MenuSheetItem(
                    2,
                    R.drawable.ic_photo_24dp,
                    R.string.set_as_cover
                )
            )
        }
        MaterialMenuSheet(this, items) { _, item ->
            when (item) {
                0 -> shareImage(page)
                1 -> saveImage(page)
                2 -> showSetCoverPrompt(page)
                3 -> extraPage?.let { shareImage(it) }
                4 -> extraPage?.let { saveImage(it) }
                5 -> extraPage?.let { showSetCoverPrompt(it) }
            }
            true
        }.show()
        if (binding.chaptersSheet.root.sheetBehavior.isExpanded()) {
            binding.chaptersSheet.root.sheetBehavior?.collapse()
        }
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        presenter.preloadChapter(chapter)
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the page sheet. It delegates the call to the presenter to do some IO, which
     * will call [onShareImageResult] with the path the image was saved on when it's ready.
     */
    fun shareImage(page: ReaderPage) {
        presenter.shareImage(page)
    }

    fun showSetCoverPrompt(page: ReaderPage) {
        if (page.status != Page.READY) return

        MaterialDialog(this).title(R.string.use_image_as_cover)
            .positiveButton(android.R.string.yes) {
                setAsCover(page)
            }.negativeButton(android.R.string.no).show()
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    fun onShareImageResult(file: File, page: ReaderPage) {
        val manga = presenter.manga ?: return
        val chapter = page.chapter.chapter

        val decimalFormat =
            DecimalFormat("#.###", DecimalFormatSymbols().apply { decimalSeparator = '.' })

        val text = "${manga.title}: ${getString(
            R.string.chapter_,
            decimalFormat.format(chapter.chapter_number)
        )}, ${getString(R.string.page_, page.number)}"

        val stream = file.getUriCompat(this)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_STREAM, stream)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            clipData = ClipData.newRawUri(null, stream)
            type = "image/*"
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    /**
     * Called from the page sheet. It delegates saving the image of the given [page] on external
     * storage to the presenter.
     */
    fun saveImage(page: ReaderPage) {
        presenter.saveImage(page)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    fun onSaveImageResult(result: ReaderPresenter.SaveImageResult) {
        when (result) {
            is ReaderPresenter.SaveImageResult.Success -> {
                toast(R.string.picture_saved)
            }
            is ReaderPresenter.SaveImageResult.Error -> {
                Timber.e(result.error)
            }
        }
    }

    /**
     * Called from the page sheet. It delegates setting the image of the given [page] as the
     * cover to the presenter.
     */
    fun setAsCover(page: ReaderPage) {
        presenter.setAsCover(page)
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    fun onSetAsCoverResult(result: ReaderPresenter.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> R.string.cover_updated
                AddToLibraryFirst -> R.string.must_be_in_library_to_edit
                Error -> R.string.failed_to_update_cover
            }
        )
    }

    override fun onVisibilityChange(visible: Boolean) {
        if (visible && !menuStickyVisible && !menuVisible) {
            menuStickyVisible = visible
            if (visible) {
                coroutine = launchUI {
                    delay(2000)
                    if (systemUi?.isShowing == true) {
                        menuStickyVisible = false
                        setMenuVisibility(false)
                    }
                }
                if (sheetManageNavColor) window.navigationBarColor =
                    getResourceColor(R.attr.colorSecondary)
                binding.readerMenu.visible()
                val toolbarAnimation = AnimationUtils.loadAnimation(this, R.anim.enter_from_top)
                toolbarAnimation.setAnimationListener(
                    object : SimpleAnimationListener() {
                        override fun onAnimationStart(animation: Animation) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                        }
                    }
                )
                binding.appBar.startAnimation(toolbarAnimation)
            }
        } else {
            if (menuStickyVisible && !menuVisible) {
                setMenuVisibility(false, animate = false)
            }
            coroutine?.cancel()
        }
    }

    /**
     * Sets notch cutout mode to "NEVER", if mobile is in a landscape view
     */
    private fun setNotchCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val currentOrientation = resources.configuration.orientation

            val params = window.attributes
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            } else {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun setDoublePageMode(viewer: PagerViewer) {
        val currentOrientation = resources.configuration.orientation
        viewer.config.doublePages = (currentOrientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun handleIntentAction(intent: Intent): Boolean {
        val uri = intent.data ?: return false
        if (!presenter.canLoadUrl(uri)) {
            openInBrowser(intent.data!!.toString(), true)
            finishAfterTransition()
            return true
        }
        setMenuVisibility(visible = false, animate = true)
        scope.launch(Dispatchers.IO) {
            try {
                intentPageNumber = presenter.intentPageNumber(uri)
                presenter.loadChapterURL(uri)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setInitialChapterError(e)
                }
            }
        }
        return true
    }

    fun openMangaInBrowser() {
        val source = presenter.getSource() ?: return
        val url = try {
            source.mangaDetailsRequest(presenter.manga!!).url.toString()
        } catch (e: Exception) {
            return
        }

        val intent = WebViewActivity.newIntent(
            applicationContext,
            source.id,
            url,
            presenter.manga!!.title
        )
        startActivity(intent)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        var showNewChapter = false

        /**
         * Initializes the reader subscriptions.
         */
        init {
            setOrientation(preferences.rotation().get())
            preferences.rotation().asFlow()
                .drop(1)
                .onEach {
                    delay(250)
                    setOrientation(it)
                }
                .launchIn(scope)

            preferences.showPageNumber().asFlow()
                .onEach { setPageNumberVisibility(it) }
                .launchIn(scope)

            preferences.trueColor().asFlow()
                .onEach { setTrueColor(it) }
                .launchIn(scope)

            preferences.fullscreen().asFlow()
                .onEach { setFullscreen(it) }
                .launchIn(scope)

            preferences.keepScreenOn().asFlow()
                .onEach { setKeepScreenOn(it) }
                .launchIn(scope)

            preferences.customBrightness().asFlow()
                .onEach { setCustomBrightness(it) }
                .launchIn(scope)

            preferences.colorFilter().asFlow()
                .onEach { setColorFilter(it) }
                .launchIn(scope)

            preferences.colorFilterMode().asFlow()
                .onEach { setColorFilter(preferences.colorFilter().get()) }
                .launchIn(scope)

            preferences.alwaysShowChapterTransition().asFlow()
                .onEach { showNewChapter = it }
                .launchIn(scope)

            preferences.pageLayout().asFlow()
                .onEach {
                    setBottomNavButtons(it)
                }
                .launchIn(scope)
            preferences.pageLayout().asFlow()
                .drop(1)
                .onEach {
                    reloadChapters(it == PageLayout.DOUBLE_PAGES)
                }
                .launchIn(scope)
        }

        /**
         * Forces the user preferred [orientation] on the activity.
         */
        private fun setOrientation(orientation: Int) {
            val newOrientation = when (orientation) {
                // Lock in current orientation
                2 -> {
                    val currentOrientation = resources.configuration.orientation
                    if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
                // Lock in portrait
                3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                // Lock in landscape
                4 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                // Rotation free
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            if (newOrientation != requestedOrientation) {
                requestedOrientation = newOrientation
            }
        }

        /**
         * Sets the visibility of the bottom page indicator according to [visible].
         */
        private fun setPageNumberVisibility(visible: Boolean) {
            binding.pageNumber.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }

        /**
         * Sets the 32-bit color mode according to [enabled].
         */
        private fun setTrueColor(enabled: Boolean) {
            if (enabled) {
                SubsamplingScaleImageView.setPreferredBitmapConfig(Bitmap.Config.ARGB_8888)
            } else {
                SubsamplingScaleImageView.setPreferredBitmapConfig(Bitmap.Config.RGB_565)
            }
        }

        /**
         * Sets the fullscreen reading mode (immersive) according to [enabled].
         */
        private fun setFullscreen(enabled: Boolean) {
            systemUi = if (enabled) {
                val level = SystemUiHelper.LEVEL_IMMERSIVE
                val flags = SystemUiHelper.FLAG_LAYOUT_IN_SCREEN_OLDER_DEVICES

                SystemUiHelper(this@ReaderActivity, level, flags, this@ReaderActivity)
            } else {
                null
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                preferences.customBrightnessValue().asFlow()
                    .sample(100)
                    .onEach { setCustomBrightnessValue(it) }
                    .launchIn(scope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the color filter overlay according to [enabled].
         */
        private fun setColorFilter(enabled: Boolean) {
            if (enabled) {
                preferences.colorFilterValue().asFlow()
                    .sample(100)
                    .onEach { setColorFilterValue(it) }
                    .launchIn(scope)
            } else {
                binding.colorOverlay.gone()
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }

            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            // Set black overlay visibility.
            if (value < 0) {
                binding.brightnessOverlay.visible()
                val alpha = (abs(value) * 2.56).toInt()
                binding.brightnessOverlay.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
            } else {
                binding.brightnessOverlay.gone()
            }
        }

        /**
         * Sets the color filter [value].
         */
        private fun setColorFilterValue(value: Int) {
            binding.colorOverlay.visible()
            binding.colorOverlay.setFilterColor(value, preferences.colorFilterMode().get())
        }
    }
}
