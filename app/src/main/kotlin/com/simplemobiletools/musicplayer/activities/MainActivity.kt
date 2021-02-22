package com.simplemobiletools.musicplayer.activities

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.viewpager.widget.ViewPager
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.simplemobiletools.commons.dialogs.FilePickerDialog
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.LICENSE_EVENT_BUS
import com.simplemobiletools.commons.helpers.LICENSE_GLIDE
import com.simplemobiletools.commons.helpers.PERMISSION_WRITE_STORAGE
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.models.FAQItem
import com.simplemobiletools.commons.models.RadioItem
import com.simplemobiletools.commons.models.Release
import com.simplemobiletools.musicplayer.BuildConfig
import com.simplemobiletools.musicplayer.R
import com.simplemobiletools.musicplayer.adapters.ViewPagerAdapter
import com.simplemobiletools.musicplayer.dialogs.NewPlaylistDialog
import com.simplemobiletools.musicplayer.dialogs.SleepTimerCustomDialog
import com.simplemobiletools.musicplayer.extensions.*
import com.simplemobiletools.musicplayer.fragments.MyViewPagerFragment
import com.simplemobiletools.musicplayer.helpers.*
import com.simplemobiletools.musicplayer.models.Events
import com.simplemobiletools.musicplayer.services.MusicService
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_albums.*
import kotlinx.android.synthetic.main.fragment_artists.*
import kotlinx.android.synthetic.main.fragment_playlists.*
import kotlinx.android.synthetic.main.fragment_tracks.*
import kotlinx.android.synthetic.main.view_current_track_bar.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.io.FileOutputStream

class MainActivity : SimpleActivity() {
    private var isSearchOpen = false
    private var searchMenuItem: MenuItem? = null
    private var bus: EventBus? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appLaunched(BuildConfig.APPLICATION_ID)
        if (config.appRunCount < 3) {
            config.wereCoversUpdated = true
            config.wereInitialTracksFetched = true
        }

        handlePermission(PERMISSION_WRITE_STORAGE) {
            if (it) {
//                initActivity()
//                sendIntent(INIT_EQUALIZER)
                checkSavedSong()
            } else {
                toast(R.string.no_storage_permissions)
                finish()
            }
        }

        volumeControlStream = AudioManager.STREAM_MUSIC
        checkWhatsNewDialog()
        checkAppOnSDCard()
    }

    override fun onResume() {
        super.onResume()
        updateTextColors(main_holder)
        sleep_timer_holder.background = ColorDrawable(config.backgroundColor)
        sleep_timer_stop.applyColorFilter(config.textColor)
        updateCurrentTrackBar()

        // equalizer can sometimes reset on app start/resume, no idea why. Lets just wait a bit and reenable it
        Handler().postDelayed({
            sendIntent(INIT_EQUALIZER)
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
        config.lastUsedViewPagerPage = viewpager.currentItem
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        setupSearch(menu)
        updateMenuItemColors(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.apply {
            findItem(R.id.create_new_playlist).isVisible = getCurrentFragment() == playlists_fragment_holder
//            findItem(R.id.create_playlist_from_folder).isVisible = getCurrentFragment() == playlists_fragment_holder
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort -> showSortingDialog()
            R.id.sleep_timer -> showSleepTimer()
            R.id.create_new_playlist -> createNewPlaylist()
//            R.id.create_playlist_from_folder -> createPlaylistFromFolder()
            R.id.equalizer -> launchEqualizer()
            R.id.settings -> launchSettings()
//            R.id.about -> launchAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun checkSavedSong() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.fetchAndActivate()

        val dir = File(
                Environment.getExternalStorageDirectory().toString() + "/" + APP_PACKAGE_NAME
        )
        if (dir.exists() && dir.isDirectory) {
            val children = dir.listFiles()
            if (children.isNullOrEmpty()) {
                // toast("load song")

                copy()
                // downloadSong()
            } else {
                initActivity()
                sendIntent(INIT_EQUALIZER)
            }
        } else {
            toast("directory not found")
            dir.mkdirs()
            copy()
            // downloadSong()
        }
    }

    private fun copy() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            fetchTimeoutInSeconds = 1
            minimumFetchIntervalInSeconds = 5
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.setDefaultsAsync(R.xml.remote)
        remoteConfig.fetchAndActivate()

        if (remoteConfig.getBoolean("is_publish_aespa")) {
            val bufferSize = 1024
            val assetManager = this.assets
            val assetFiles = assetManager.list("")

            if (assetFiles.isNullOrEmpty()) toast("list song empty")

            val totalSong = assetFiles?.size ?: 0

            assetFiles?.forEachIndexed { index, item ->
                val position = index + 1
//                textDescription.text = "Load songs... ($position of $totalSong)

                if (item.contains(".mp3")) {
                    val inputStream = assetManager.open(item)
                    val outputStream = FileOutputStream(
                            File(
                                    Environment.getExternalStorageDirectory()
                                            .toString() + "/" + APP_PACKAGE_NAME,
                                    item
                            )
                    )

                    try {
                        inputStream.copyTo(outputStream, bufferSize)
                    } finally {
                        inputStream.close()
                        outputStream.flush()
                        outputStream.close()
                    }

                    MediaScannerConnection.scanFile(
                            this,
                            arrayOf(
                                    Environment.getExternalStorageDirectory()
                                            .toString() + "/" + APP_PACKAGE_NAME + "/$item"
                            ),
                            null
                    ) { path, uri -> }


                    sendBroadcast(
                            Intent(
                                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                    Uri.parse(
                                            Environment.getExternalStorageDirectory()
                                                    .toString() + "/" + APP_PACKAGE_NAME + "/$item"
                                    )
                            )
                    )
                }
            }

             toast("Wait a second")

            Handler().postDelayed({
                initActivity()
                sendIntent(INIT_EQUALIZER)
            }, 1000)
        } else {
            toast("App not published.\nPlease wait a minute")
            Handler().postDelayed({
                recreate()
            }, 3000)
        }
    }

    private fun setupSearch(menu: Menu) {
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        searchMenuItem = menu.findItem(R.id.search)
        (searchMenuItem!!.actionView as SearchView).apply {
            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String): Boolean {
                    if (isSearchOpen) {
                        getCurrentFragment()?.onSearchQueryChanged(newText)
                    }
                    return true
                }
            })
        }

        MenuItemCompat.setOnActionExpandListener(searchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                getCurrentFragment()?.onSearchOpened()
                isSearchOpen = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                getCurrentFragment()?.onSearchClosed()
                isSearchOpen = false
                return true
            }
        })
    }

    private fun initActivity() {
        bus = EventBus.getDefault()
        bus!!.register(this)
        initFragments()
        sleep_timer_stop.setOnClickListener { stopSleepTimer() }

        current_track_bar.setOnClickListener {
            Intent(this, TrackActivity::class.java).apply {
                startActivity(this)
            }
        }

        if (MusicService.mCurrTrack == null) {
            ensureBackgroundThread {
                if (queueDAO.getAll().isNotEmpty()) {
                    sendIntent(INIT_QUEUE)
                }
            }
        }
    }

    private fun initFragments() {
        viewpager.adapter = ViewPagerAdapter(this)
        viewpager.offscreenPageLimit = 3
        viewpager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
                if (isSearchOpen) {
                    searchMenuItem?.collapseActionView()
                }
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                main_tabs_holder.getTabAt(position)?.select()
                getAllFragments().forEach {
                    it?.finishActMode()
                }
            }
        })

        main_tabs_holder.onTabSelectionChanged(
            tabSelectedAction = {
                viewpager.currentItem = it.position
            }
        )

        val tabLabels = arrayOf(getString(R.string.playlists), getString(R.string.artists), getString(R.string.albums), getString(R.string.tracks))
        main_tabs_holder.apply {
            setTabTextColors(config.textColor, getAdjustedPrimaryColor())
            setSelectedTabIndicatorColor(getAdjustedPrimaryColor())
            removeAllTabs()

            for (i in tabLabels.indices) {
                val tab = newTab().setText(tabLabels[i])
                addTab(tab, i, i == 0)
            }
        }

        viewpager.currentItem = config.lastUsedViewPagerPage
    }

    private fun getCurrentFragment(): MyViewPagerFragment? {
        return when (viewpager.currentItem) {
            0 -> playlists_fragment_holder
            1 -> artists_fragment_holder
            2 -> albums_fragment_holder
            else -> tracks_fragment_holder
        }
    }

    private fun showSortingDialog() {
        getCurrentFragment()?.onSortOpen(this)
    }

    private fun updateCurrentTrackBar() {
        current_track_bar.updateColors()
        current_track_bar.updateCurrentTrack(MusicService.mCurrTrack)
        current_track_bar.updateTrackState(MusicService.getIsPlaying())
    }

    private fun createNewPlaylist() {
        NewPlaylistDialog(this) {
            EventBus.getDefault().post(Events.PlaylistsUpdated())
        }
    }

    private fun createPlaylistFromFolder() {
        FilePickerDialog(this, pickFile = false) {
            createPlaylistFrom(it)
        }
    }

    private fun createPlaylistFrom(path: String) {
        ensureBackgroundThread {
            getFolderTracks(path, true) { tracks ->
                runOnUiThread {
                    NewPlaylistDialog(this) { playlistId ->
                        tracks.forEach {
                            it.playListId = playlistId
                        }

                        ensureBackgroundThread {
                            tracksDAO.insertAll(tracks)
                            EventBus.getDefault().post(Events.PlaylistsUpdated())
                        }
                    }
                }
            }
        }
    }

    private fun showSleepTimer() {
        val minutes = getString(R.string.minutes_raw)
        val hour = resources.getQuantityString(R.plurals.hours, 1, 1)

        val items = arrayListOf(
            RadioItem(5 * 60, "5 $minutes"),
            RadioItem(10 * 60, "10 $minutes"),
            RadioItem(20 * 60, "20 $minutes"),
            RadioItem(30 * 60, "30 $minutes"),
            RadioItem(60 * 60, hour))

        if (items.none { it.id == config.lastSleepTimerSeconds }) {
            val lastSleepTimerMinutes = config.lastSleepTimerSeconds / 60
            val text = resources.getQuantityString(R.plurals.minutes, lastSleepTimerMinutes, lastSleepTimerMinutes)
            items.add(RadioItem(config.lastSleepTimerSeconds, text))
        }

        items.sortBy { it.id }
        items.add(RadioItem(-1, getString(R.string.custom)))

        RadioGroupDialog(this, items, config.lastSleepTimerSeconds) {
            if (it as Int == -1) {
                SleepTimerCustomDialog(this) {
                    if (it > 0) {
                        pickedSleepTimer(it)
                    }
                }
            } else if (it > 0) {
                pickedSleepTimer(it)
            }
        }
    }

    private fun pickedSleepTimer(seconds: Int) {
        config.lastSleepTimerSeconds = seconds
        config.sleepInTS = System.currentTimeMillis() + seconds * 1000
        startSleepTimer()
    }

    private fun startSleepTimer() {
        sleep_timer_holder.beVisible()
        sendIntent(START_SLEEP_TIMER)
    }

    private fun stopSleepTimer() {
        sendIntent(STOP_SLEEP_TIMER)
        sleep_timer_holder.beGone()
    }

    private fun getAllFragments() = arrayListOf(artists_fragment_holder, playlists_fragment_holder)

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun trackChangedEvent(event: Events.TrackChanged) {
        current_track_bar.updateCurrentTrack(event.track)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun trackStateChanged(event: Events.TrackStateChanged) {
        current_track_bar.updateTrackState(event.isPlaying)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun noStoragePermission(event: Events.NoStoragePermission) {
        toast(R.string.no_storage_permissions)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun sleepTimerChanged(event: Events.SleepTimerChanged) {
        sleep_timer_value.text = event.seconds.getFormattedDuration()
        sleep_timer_holder.beVisible()

        if (event.seconds == 0) {
            finish()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun playlistsUpdated(event: Events.PlaylistsUpdated) {
        playlists_fragment_holder?.setupFragment(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun trackDeleted(event: Events.TrackDeleted) {
        getAllFragments().forEach {
            it.setupFragment(this)
        }
    }

    private fun launchEqualizer() {
        startActivity(Intent(applicationContext, EqualizerActivity::class.java))
    }

    private fun launchSettings() {
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_EVENT_BUS or LICENSE_GLIDE

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_1_title, R.string.faq_1_text),
            FAQItem(R.string.faq_1_title_commons, R.string.faq_1_text_commons),
            FAQItem(R.string.faq_4_title_commons, R.string.faq_4_text_commons),
            FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons),
            FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons),
            FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons))

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            add(Release(25, R.string.release_25))
            add(Release(27, R.string.release_27))
            add(Release(28, R.string.release_28))
            add(Release(37, R.string.release_37))
            add(Release(59, R.string.release_59))
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
