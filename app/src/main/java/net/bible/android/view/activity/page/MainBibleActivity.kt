/*
 * Copyright (c) 2018 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
 *
 * This file is part of And Bible (http://github.com/AndBible/and-bible).
 *
 * And Bible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * And Bible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with And Bible.
 * If not, see http://www.gnu.org/licenses/.
 *
 */

package net.bible.android.view.activity.page

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.appcompat.view.ActionMode
import androidx.core.view.GravityCompat
import kotlinx.android.synthetic.main.main_bible_view.*

import net.bible.android.BibleApplication
import net.bible.android.activity.R
import net.bible.android.control.BibleContentManager
import net.bible.android.control.PassageChangeMediator
import net.bible.android.control.backup.BackupControl
import net.bible.android.control.event.ABEventBus
import net.bible.android.control.document.DocumentControl
import net.bible.android.control.event.apptobackground.AppToBackgroundEvent
import net.bible.android.control.event.passage.*
import net.bible.android.control.event.window.CurrentWindowChangedEvent
import net.bible.android.control.navigation.NavigationControl
import net.bible.android.control.page.window.WindowControl
import net.bible.android.control.search.SearchControl
import net.bible.android.control.speak.SpeakControl
import net.bible.android.view.activity.DaggerMainBibleActivityComponent
import net.bible.android.view.activity.MainBibleActivityModule
import net.bible.android.view.activity.base.ActivityBase
import net.bible.android.view.activity.base.CustomTitlebarActivityBase
import net.bible.android.view.activity.base.Dialogs
import net.bible.android.view.activity.bookmark.Bookmarks
import net.bible.android.view.activity.navigation.ChooseDocument
import net.bible.android.view.activity.navigation.GridChoosePassageBook
import net.bible.android.view.activity.page.actionmode.VerseActionModeMediator
import net.bible.android.view.activity.page.screen.DocumentViewManager
import net.bible.android.view.activity.speak.BibleSpeakActivity
import net.bible.android.view.activity.speak.GeneralSpeakActivity
import net.bible.service.common.CommonUtils
import net.bible.service.common.TitleSplitter
import net.bible.service.device.ScreenSettings
import net.bible.service.device.speak.event.SpeakEvent
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseFactory
import org.jetbrains.anko.itemsSequence

import javax.inject.Inject


/** The main activity screen showing Bible text
 *
 * @author Martin Denham [mjdenham at gmail dot com]
 */

class MainBibleActivity : CustomTitlebarActivityBase(), VerseActionModeMediator.ActionModeMenuDisplay {
    private var mWholeAppWasInBackground = false

    // We need to have this here in order to initialize BibleContentManager early enough.
    @Inject lateinit var bibleContentManager: BibleContentManager
    @Inject lateinit var documentViewManager: DocumentViewManager
    @Inject lateinit var windowControl: WindowControl
    @Inject lateinit var speakControl: SpeakControl

    // handle requests from main menu
    @Inject lateinit var mainMenuCommandHandler: MenuCommandHandler
    @Inject lateinit var bibleKeyHandler: BibleKeyHandler
    @Inject lateinit var backupControl: BackupControl
    @Inject lateinit var searchControl: SearchControl
    @Inject lateinit var documentControl: DocumentControl
    @Inject lateinit var navigationControl: NavigationControl

    override var nightTheme = R.style.MainBibleViewNightTheme
    override var dayTheme = R.style.MainBibleViewTheme

    /**
     * return percentage scrolled down page
     */
    private val currentPosition: Float
        get() = documentViewManager.documentView.currentPosition

    /**
     * Called when the activity is first created.
     */
    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "Creating MainBibleActivity")
        super.onCreate(savedInstanceState, true)

        setContentView(R.layout.main_bible_view)
        setSupportActionBar(toolbar)
        toolbar.setContentInsetsAbsolute(0, 0)

        navigationView.setNavigationItemSelectedListener { menuItem ->
            drawerLayout.closeDrawers()
            mainMenuCommandHandler.handleMenuRequest(menuItem)
        }

        DaggerMainBibleActivityComponent.builder()
                .applicationComponent(BibleApplication.application.applicationComponent)
                .mainBibleActivityModule(MainBibleActivityModule(this))
                .build()
                .inject(this)

        // create related objects
        documentViewManager.buildView()

        // register for passage change and appToBackground events
        ABEventBus.getDefault().register(this)

        // force the screen to be populated
        PassageChangeMediator.getInstance().forcePageUpdate()
        refreshScreenKeepOn()
        requestSdcardPermission()
        setupToolbarButtons()

    }

    private fun setupToolbarButtons() {
        updateSpeakTransportVisibility()

        homeButton.setOnClickListener {
            if(drawerLayout.isDrawerVisible(GravityCompat.START)) {
                drawerLayout.closeDrawers()
            }
            else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        pageTitleContainer.setOnClickListener {
            val intent = Intent(this, pageControl.currentPageManager.currentPage.keyChooserActivity)
            startActivityForResult(intent, ActivityBase.STD_REQUEST_CODE)
        }
        pageTitleContainer.setOnLongClickListener {
            startActivityForResult(Intent(this, ChooseDocument::class.java), ActivityBase.STD_REQUEST_CODE)
            true
        }

        speakButton.setOnClickListener {
            val isBible = windowControl.activeWindowPageManager.currentPage.bookCategory == BookCategory.BIBLE
            val intent = Intent(this, if (isBible) BibleSpeakActivity::class.java else GeneralSpeakActivity::class.java)
            startActivity(intent)
        }
        searchButton.setOnClickListener { startActivityForResult( searchControl.getSearchIntent(documentControl.currentDocument), ActivityBase.STD_REQUEST_CODE)   }
        bibleButton.setOnClickListener { setCurrentDocument(documentControl.suggestedBible) }
        commentaryButton.setOnClickListener { setCurrentDocument(documentControl.suggestedCommentary) }
        bookmarkButton.setOnClickListener { startActivity( Intent(this, Bookmarks::class.java))  }
        dictionaryButton.setOnClickListener { setCurrentDocument(documentControl.suggestedDictionary) }
    }

    data class ItemOptions (val name: String, val default: Boolean = true, val onlyBibles: Boolean = false)

    private fun getPreferenceName(itemId: Int) =  when(itemId) {
        R.id.showBookmarksOption -> ItemOptions("show_bookmarks_pref", true, true)
        R.id.redLettersOption -> ItemOptions("red_letter_pref", false, true)
        R.id.sectionTitlesOption -> ItemOptions("section_title_pref", true, true)
        R.id.showStrongsOption -> ItemOptions("show_strongs_pref", false, true)
        R.id.verseNumbersOption -> ItemOptions("show_verseno_pref", true, true)
        R.id.versePerLineOption -> ItemOptions("verse_per_line_pref", false, true)
        R.id.footnoteOption -> ItemOptions("show_notes_pref", false, true)
        R.id.myNotesOption -> ItemOptions("show_mynotes_pref", true)
        R.id.morphologyOption -> ItemOptions("show_morphology_pref", false, true)
        else -> null
    }

    private val preferences = CommonUtils.getSharedPreferences()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_bible_options_menu, menu)
        for(item in menu.itemsSequence()) {
            val itmOptions = getPreferenceName(item.itemId)
            if(itmOptions != null) {
                item.isChecked = preferences.getBoolean(itmOptions.name, itmOptions.default)
                if(itmOptions.onlyBibles) {
                    item.isVisible = documentControl.isBibleBook
                }
                else {
                    item.isVisible = true
                }
            }
        }
        return true
    }

    private fun handlePrefItem(name: String, item: MenuItem, default: Boolean) {
        val oldValue = preferences.getBoolean(name, default)
        preferences.edit().putBoolean(name, !oldValue).apply()
        PassageChangeMediator.getInstance().forcePageUpdate()
        item.isChecked = !oldValue
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefNamePair = getPreferenceName(item.itemId)
        if(prefNamePair != null) {
            handlePrefItem(prefNamePair.name, item, prefNamePair.default)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private val documentTitleText: String
        get() = pageControl.currentPageManager.currentPassageDocument.name

    private val pageTitleText: String
        get() {
            var ver = pageControl.currentBibleVerse
            if(ver.verse == 0){
                ver = Verse(ver.versification, ver.book, ver.chapter, 1)
            }
            return ver.name
        }

    private fun updateTitle() {
        pageTitle.text = pageTitleText
        documentTitle.text = documentTitleText
    }

    private val titleSplitter = TitleSplitter()
    private val actionButtonMaxChars = CommonUtils.getResourceInteger(R.integer.action_button_max_chars)

    override fun updateActionBarButtons() {
        updateTitle()

        val suggestedBible = documentControl.suggestedBible
        val suggestedCommentary = documentControl.suggestedCommentary
        val suggestedDictionary = documentControl.suggestedDictionary

        var visibleButtonCount = 0
        val maxWidth = toolbarLayout.width / 2
        val approximateSize = homeButton.width

        val maxButtons: Int = maxWidth / approximateSize

        bibleButton.visibility = if(visibleButtonCount < maxButtons && suggestedBible != null) {
            bibleButton.text = titleSplitter.shorten(suggestedBible.abbreviation, actionButtonMaxChars)
            bibleButton.setOnLongClickListener { menuForDocs(it, documentControl.biblesForVerse) }
            visibleButtonCount += 1
            View.VISIBLE
        } else View.GONE

        commentaryButton.visibility = if(suggestedCommentary != null && visibleButtonCount < maxButtons) {
            commentaryButton.text = titleSplitter.shorten(suggestedCommentary.abbreviation, actionButtonMaxChars)
            commentaryButton.setOnLongClickListener { menuForDocs(it, documentControl.commentariesForVerse) }
            visibleButtonCount += 1
            View.VISIBLE
        } else View.GONE

        speakButton.visibility = if(visibleButtonCount< maxButtons && speakControl.isStopped) {
            visibleButtonCount += 1
            View.VISIBLE
        } else View.GONE

        searchButton.visibility = if(visibleButtonCount< maxButtons) {
            visibleButtonCount += 1
            View.VISIBLE
        } else View.GONE

        bookmarkButton.visibility = if(visibleButtonCount< maxButtons) {
            visibleButtonCount += 1
            View.VISIBLE
        } else View.GONE

        dictionaryButton.visibility = if(suggestedDictionary != null && visibleButtonCount < maxButtons) {
            dictionaryButton.text = titleSplitter.shorten(suggestedDictionary.abbreviation, actionButtonMaxChars)
            dictionaryButton.setOnLongClickListener { menuForDocs(it, swordDocumentFacade.getBooks(BookCategory.DICTIONARY)) }
            visibleButtonCount += 1
            View.VISIBLE
        } else View.GONE
        invalidateOptionsMenu()
    }

    fun onEventMainThread(passageEvent: CurrentVerseChangedEvent) {
        updateTitle()
    }

    fun onEventMainThread(speakEvent: SpeakEvent) {
        updateSpeakTransportVisibility()
        updateActionBarButtons()
    }

    private fun menuForDocs(v: View, documents: List<Book>): Boolean {
        val menu = PopupMenu(this, v)
        val docs = documents.sortedWith(compareBy({it.language.code}, {it.abbreviation}))
        docs.forEachIndexed { i, book ->
            if(windowControl.activeWindow.pageManager.currentPage.currentDocument != book) {
                menu.menu.add(Menu.NONE, i, Menu.NONE, "${book.abbreviation} (${book.language.code})")
            }
        }

        menu.setOnMenuItemClickListener { item ->
            windowControl.activeWindow.pageManager.setCurrentDocument(docs[item.itemId])
        true
        }
        menu.show()
        return true
    }

    private fun setCurrentDocument(book: Book?) {
        windowControl.activeWindow.pageManager.setCurrentDocument(book)
    }

    override fun toggleFullScreen() {
        super.toggleFullScreen()
        updateSpeakTransportVisibility()
    }

    private fun updateSpeakTransportVisibility() {
        speakTransport.visibility = if(isFullScreen || speakControl.isStopped) View.GONE else View.VISIBLE
    }

    private fun refreshScreenKeepOn() {
        val keepOn = preferences.getBoolean(SCREEN_KEEP_ON_PREF, false)
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ABEventBus.getDefault().unregister(this)
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        if (menuInfo != null) {
            val inflater = menuInflater
            inflater.inflate(R.menu.link_context_menu, menu)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as BibleView.BibleViewContextMenuInfo?
        if (info != null) {
            info.activate(item.itemId)
            return true
        }
        return false
    }

    /**
     * called if the app is re-entered after returning from another app.
     * Trigger redisplay in case mobile has gone from light to dark or vice-versa
     */
    override fun onRestart() {
        super.onRestart()
        refreshScreenKeepOn()
        if (mWholeAppWasInBackground) {
            mWholeAppWasInBackground = false
            refreshIfNightModeChange()
        }
    }

    /**
     * Need to know when app is returned to foreground to check the screen colours
     */
    fun onEvent(event: AppToBackgroundEvent) {
        if (event.isMovedToBackground) {
            mWholeAppWasInBackground = true
        }
        else {
            updateActionBarButtons()
        }
    }

    override fun onScreenTurnedOff() {
        super.onScreenTurnedOff()
        documentViewManager.documentView.onScreenTurnedOff()
    }

    override fun onScreenTurnedOn() {
        super.onScreenTurnedOn()
        refreshIfNightModeChange()
        documentViewManager.documentView.onScreenTurnedOn()
    }

    /**
     * if using auto night mode then may need to refresh
     */
    private fun refreshIfNightModeChange() {
        // colour may need to change which affects View colour and html
        // first refresh the night mode setting using light meter if appropriate
        if (ScreenSettings.isNightModeChanged()) {
            // then update text if colour changed
            documentViewManager.documentView.changeBackgroundColour()
            PassageChangeMediator.getInstance().forcePageUpdate()
        }
    }

    /**
     * adding android:configChanges to manifest causes this method to be called on flip, etc instead of a new instance and onCreate, which would cause a new observer -> duplicated threads
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // essentially if the current page is Bible then we need to recalculate verse offsets
        // if not then don't redisplay because it would force the page to the top which would be annoying if you are half way down a gen book page
        if (!windowControl.activeWindowPageManager.currentPage.isSingleKey) {
            // force a recalculation of verse offsets
            PassageChangeMediator.getInstance().forcePageUpdate()
        } else if (windowControl.isMultiWindow) {
            // need to layout multiple windows differently
            windowControl.orientationChange()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        Log.d(TAG, "Keycode:$keyCode")
        // common key handling i.e. KEYCODE_DPAD_RIGHT & KEYCODE_DPAD_LEFT
        if (bibleKeyHandler.onKeyUp(keyCode, event)) {
            return true
        } else if (keyCode == KeyEvent.KEYCODE_SEARCH && windowControl.activeWindowPageManager.currentPage.isSearchable) {
            val intent = searchControl.getSearchIntent(windowControl.activeWindowPageManager.currentPage.currentDocument)
            if (intent != null) {
                startActivityForResult(intent, ActivityBase.STD_REQUEST_CODE)
            }
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "Activity result:$resultCode")
        if(GridChoosePassageBook::class.java.name == data?.component?.className) {
            val verseStr = data?.extras!!.getString("verse")
            val verse = VerseFactory.fromString(navigationControl.versification, verseStr)
            windowControl.activeWindowPageManager.currentPage.key = verse
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
        when {
            mainMenuCommandHandler.restartIfRequiredOnReturn(requestCode) -> {
                // restart done in above
            }
            mainMenuCommandHandler.isDisplayRefreshRequired(requestCode) -> {
                preferenceSettingsChanged()
                ABEventBus.getDefault().post(SynchronizeWindowsEvent())
            }
            mainMenuCommandHandler.isDocumentChanged(requestCode) -> updateActionBarButtons()
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            BACKUP_SAVE_REQUEST -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                backupControl.backupDatabase()
            } else {
                Dialogs.getInstance().showMsg(R.string.error_occurred)
            }
            BACKUP_RESTORE_REQUEST -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                backupControl.restoreDatabase()
            } else {
                Dialogs.getInstance().showMsg(R.string.error_occurred)
            }
            SDCARD_READ_REQUEST -> if (grantResults.isNotEmpty()) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    documentControl.enableManualInstallFolder()
                } else {
                    documentControl.turnOffManualInstallFolderSetting()
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun preferenceSettingsChanged() {
        documentViewManager.documentView.applyPreferenceSettings()
        PassageChangeMediator.getInstance().forcePageUpdate()
        requestSdcardPermission()
    }

    private fun requestSdcardPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val requestSdCardPermission = preferences.getBoolean(REQUEST_SDCARD_PERMISSION_PREF, false)
            if (requestSdCardPermission && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), SDCARD_READ_REQUEST)
            }
        }
    }

    /**
     * allow current page to save any settings or data before being changed
     */
    fun onEvent(event: PreBeforeCurrentPageChangeEvent) {
        val currentPage = windowControl.activeWindowPageManager.currentPage
        if (currentPage != null) {
            // save current scroll position so history can return to correct place in document
            val screenPosn = currentPosition
            currentPage.currentYOffsetRatio = screenPosn
        }
    }

    fun onEvent(event: CurrentWindowChangedEvent) {
        updateActionBarButtons()
    }

    /**
     * called just before starting work to change the current passage
     */
    fun onEventMainThread(event: PassageChangeStartedEvent) {
        documentViewManager.buildView()
    }

    /**
     * called by PassageChangeMediator after a new passage has been changed and displayed
     */
    fun onEventMainThread(event: PassageChangedEvent) {
        updateActionBarButtons()
    }

    override fun onResume() {
        super.onResume()

        // allow webView to start monitoring tilt by setting focus which causes tilt-scroll to resume
        documentViewManager.documentView.asView().requestFocus()
    }

    /**
     * Some menu items must be hidden for certain document types
     */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // construct the options menu
        super.onPrepareOptionsMenu(menu)

        // disable some options depending on document type
        windowControl.activeWindowPageManager.currentPage.updateOptionsMenu(menu)

        // if there is no backup file then disable the restore menu item
        backupControl.updateOptionsMenu(menu)

        // must return true for menu to be displayed
        return true
    }

    override fun isVerseActionModeAllowed(): Boolean {
        return !drawerLayout.isDrawerVisible(navigationView)
    }

    override fun showVerseActionModeMenu(actionModeCallbackHandler: ActionMode.Callback) {
        Log.d(TAG, "showVerseActionModeMenu")

        runOnUiThread {
            val actionMode = startSupportActionMode(actionModeCallbackHandler)

            // Fix for onPrepareActionMode not being called: https://code.google.com/p/android/issues/detail?id=159527
            actionMode?.invalidate()
        }
    }

    override fun clearVerseActionMode(actionMode: ActionMode) {
        runOnUiThread { actionMode.finish() }
    }

    /**
     * user swiped right
     */
    operator fun next() {
        if (documentViewManager.documentView.isPageNextOkay) {
            windowControl.activeWindowPageManager.currentPage.next()
        }
    }

    /**
     * user swiped left
     */
    fun previous() {
        if (documentViewManager.documentView.isPagePreviousOkay) {
            windowControl.activeWindowPageManager.currentPage.previous()
        }
    }


    companion object {
        internal const val BACKUP_SAVE_REQUEST = 0
        internal const val BACKUP_RESTORE_REQUEST = 1
        private const val SDCARD_READ_REQUEST = 2

        private const val SCREEN_KEEP_ON_PREF = "screen_keep_on_pref"
        private const val REQUEST_SDCARD_PERMISSION_PREF = "request_sdcard_permission_pref"

        private const val TAG = "MainBibleActivity"
    }
}

