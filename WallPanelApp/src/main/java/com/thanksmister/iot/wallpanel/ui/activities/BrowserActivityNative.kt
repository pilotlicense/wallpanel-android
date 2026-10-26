/*
 * Copyright (c) 2019 ThanksMister LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thanksmister.iot.wallpanel.ui.activities

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleObserver
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.thanksmister.iot.wallpanel.BuildConfig
import com.thanksmister.iot.wallpanel.R
import com.thanksmister.iot.wallpanel.network.ConnectionLiveData
import com.thanksmister.iot.wallpanel.ui.fragments.CodeBottomSheetFragment
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Dashboard browser backed by GeckoView (bundled Firefox engine) instead of the system WebView,
 * so pages render with a current engine even on devices whose WebView can't be updated.
 */
class BrowserActivityNative : BaseBrowserActivity(), LifecycleObserver {

    private val geckoView: GeckoView by lazy { findViewById(R.id.activity_browser_webview_native) }
    private val swipeContainer: SwipeRefreshLayout by lazy { findViewById(R.id.swipeContainer) }
    private val progressView: ProgressBar by lazy { findViewById(R.id.progressView) }
    private val launchSettingsFab: FloatingActionButton by lazy { findViewById(R.id.launchSettingsFab) }

    private var session: GeckoSession? = null
    private var bridgePort: WebExtension.Port? = null
    private var pendingPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var currentUrl: String? = null
    private var progressSnackbar: Snackbar? = null
    private var contentScrollY = 0

    private var playlistHandler: Handler? = null
    private var codeBottomSheet: CodeBottomSheetFragment? = null
    private val calendar: Calendar = Calendar.getInstance()
    private val reconnectionHandler = Handler(Looper.getMainLooper())
    private var connectionLiveData: ConnectionLiveData? = null
    private var isConnected = true
    private var awaitingReconnect = false

    // To save current index
    private var playlistIndex = 0

    private val playlistRunnable = object : Runnable {
        override fun run() {
            // TODO: allow users to set their own value in settings
            val offset = 60L - calendar.get(Calendar.SECOND)
            val urls: List<String> = configuration.appLaunchUrl.lines()
            // Avoid IndexOutOfBound
            playlistIndex = (playlistIndex + 1) % urls.size
            if (urls.isNotEmpty() && urls.size >= playlistIndex) {
                loadWebViewUrl(urls[playlistIndex])
                playlistHandler?.postDelayed(this, TimeUnit.SECONDS.toMillis(offset))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            configuration.mqttBroker = BuildConfig.BROKER
            configuration.mqttUsername = BuildConfig.BROKER_USERNAME
            configuration.mqttPassword = BuildConfig.BROKER_PASS
            configuration.appLaunchUrl = BuildConfig.HASS_URL
            configuration.isFirstTime = false
            configuration.settingsCode = BuildConfig.CODE.toString()
            configuration.hasClockScreenSaver = true
        }

        try {
            setContentView(R.layout.activity_browser)
        } catch (e: Exception) {
            Timber.e(e.message)
            AlertDialog.Builder(this@BrowserActivityNative)
                    .setMessage(getString(R.string.dialog_missing_webview_warning))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            return
        }

        launchSettingsFab.setOnClickListener {
            if (configuration.isFirstTime) {
                openSettings()
            } else {
                showCodeBottomSheet()
            }
        }

        connectionLiveData = ConnectionLiveData(this)
        connectionLiveData?.observe(this, { connected ->
            if (connected && isConnected.not()) {
                isConnected = true
                if(awaitingReconnect) { // reload the page if there was error initially loading page due to network disconnect
                    stopReloadDelay()
                    initWebPageLoad()
                } else if(configuration.browserRefreshDisconnect) { // reload page on network reconnect
                    initWebPageLoad()
                }
            } else if (connected.not()) {
                isConnected = false
            }
        })

        val runtime = getRuntime(this, zoomLevel)
        val newSession = GeckoSession(GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .useTrackingProtection(false)
                .build())
        newSession.progressDelegate = progressDelegate
        newSession.navigationDelegate = navigationDelegate
        newSession.permissionDelegate = permissionDelegate
        newSession.promptDelegate = promptDelegate
        newSession.scrollDelegate = scrollDelegate
        newSession.contentDelegate = contentDelegate
        newSession.open(runtime)
        geckoView.setSession(newSession)
        session = newSession

        geckoView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetScreen()
                    if (!v.hasFocus()) {
                        v.requestFocus()
                    }
                }
                MotionEvent.ACTION_UP -> if (!v.hasFocus()) {
                    v.requestFocus()
                }
            }
            false
        }

        // Load the page once the JS bridge is listening so the first page can receive eval commands
        attachBridge(runtime, newSession) { initWebPageLoad() }
    }

    override fun onDestroy() {
        super.onDestroy()
        codeBottomSheet?.dismiss()
        reconnectionHandler.removeCallbacksAndMessages(null)
        playlistHandler?.removeCallbacksAndMessages(null)
        pendingPermissionCallback?.reject()
        pendingPermissionCallback = null
        session?.let {
            geckoView.releaseSession()
            it.close()
        }
        session = null
    }

    override fun onStart() {
        super.onStart()
        if (configuration.useDarkTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            setLightTheme()
        }

        if (configuration.browserRefresh) {
            swipeContainer.setOnRefreshListener {
                clearCache()
                initWebPageLoad()
            }
            swipeContainer.isEnabled = contentScrollY == 0
        } else {
            swipeContainer.isEnabled = false
        }

        setupSettingsButton()

        if(configuration.hasSettingsUpdates()) {
            initWebPageLoad()
        }
    }

    override fun complete() {
        if (swipeContainer.isRefreshing && configuration.browserRefresh) {
            swipeContainer.isRefreshing = false
        }
    }

    override fun openSettings() {
        hideScreenSaver()
        // Stop our service for performance reasons and to pick up changes
        stopService(wallPanelService)
        val intent = SettingsActivity.createStartIntent(this)
        startActivity(intent)
    }

    override fun configureWebSettings(userAgent: String) {
        session?.settings?.userAgentOverride = userAgent.ifEmpty { null }
    }

    private fun initWebPageLoad() {
        if (session == null) {
            return
        }
        progressView.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
        // set user agent
        configureWebSettings(configuration.browserUserAgent)
        // check if we are using playlist
        if (configuration.appLaunchUrl.lines().size == 1) {
            loadWebViewUrl(configuration.appLaunchUrl)
        } else {
            startPlaylist()
        }
    }

    override fun loadWebViewUrl(url: String) {
        Timber.d("loadUrl $url")
        session?.loadUri(url)
    }

    override fun evaluateJavascript(js: String) {
        val port = bridgePort
        if (port == null) {
            Timber.w("JS bridge not connected, dropping eval")
            return
        }
        port.postMessage(JSONObject().put("js", js))
    }

    override fun clearCache() {
        getRuntime(this, zoomLevel).storageController.clearData(
                StorageController.ClearFlags.ALL_CACHES or StorageController.ClearFlags.COOKIES)
    }

    override fun reload() {
        session?.reload()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_GECKO_PERMISSIONS) {
            return
        }
        val callback = pendingPermissionCallback ?: return
        pendingPermissionCallback = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            callback.grant()
        } else {
            callback.reject()
        }
    }

    private val reloadPageRunnable = Runnable {
        initWebPageLoad()
    }

    private fun startReloadDelay() {
        awaitingReconnect = true
        playlistHandler?.removeCallbacksAndMessages(null)
        reconnectionHandler.removeCallbacks(reloadPageRunnable)
        reconnectionHandler.postDelayed(reloadPageRunnable, 30000)
    }

    private fun stopReloadDelay() {
        awaitingReconnect = false
        reconnectionHandler.removeCallbacks(reloadPageRunnable)
    }

    private fun startPlaylist() {
        playlistHandler = Handler(Looper.getMainLooper())
        playlistHandler?.postDelayed(playlistRunnable, 10)
    }

    /**
     * Installs the bundled extension (assets/wallpanel-bridge) that runs eval commands in the page,
     * then calls [onReady] whether or not the install worked.
     */
    private fun attachBridge(runtime: GeckoRuntime, session: GeckoSession, onReady: () -> Unit) {
        runtime.webExtensionController
                .ensureBuiltIn(BRIDGE_EXTENSION_URI, BRIDGE_EXTENSION_ID)
                .accept({ extension ->
                    extension?.let {
                        session.webExtensionController.setMessageDelegate(it, bridgeDelegate, BRIDGE_NATIVE_APP)
                    }
                    onReady()
                }, { e ->
                    Timber.e(e, "Could not install WallPanel bridge extension")
                    onReady()
                })
    }

    private val bridgeDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            // A new port is opened for each page load; keep the latest one
            bridgePort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onDisconnect(port: WebExtension.Port) {
                    if (bridgePort == port) {
                        bridgePort = null
                    }
                }
            })
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (progress >= 100 || !displayProgress) {
                return
            }
            val text = getString(R.string.text_loading_percent, progress.toString(), currentUrl)
            val snackbar = progressSnackbar
                    ?: Snackbar.make(geckoView, text, Snackbar.LENGTH_INDEFINITE).also { progressSnackbar = it }
            snackbar.setText(text)
            snackbar.show()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            progressSnackbar?.dismiss()
            progressSnackbar = null
            if (isConnected) {
                stopReloadDelay()
            }
            val url = currentUrl
            if (url != null) {
                pageLoadComplete(url)
            } else {
                Toast.makeText(this@BrowserActivityNative, getString(R.string.toast_empty_url), Toast.LENGTH_SHORT).show()
                complete()
            }
        }
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(session: GeckoSession, url: String?,
                                      perms: List<ContentPermission>, hasUserGesture: Boolean) {
            currentUrl = url
        }

        override fun onLoadRequest(session: GeckoSession,
                                   request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                // Keep new-window links in the single kiosk view, as the WebView version did
                session.loadUri(request.uri)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            return null
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
            Timber.e("Load error category=${error.category} code=${error.code} uri=$uri")
            if (isFinishing) {
                return null
            }
            isConnected = false
            startReloadDelay()
            return GeckoResult.fromValue(ERROR_PAGE_URI)
        }
    }

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?,
                                                 callback: GeckoSession.PermissionDelegate.Callback) {
            val missing = permissions.orEmpty().filter {
                ContextCompat.checkSelfPermission(this@BrowserActivityNative, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                callback.grant()
                return
            }
            pendingPermissionCallback?.reject()
            pendingPermissionCallback = callback
            ActivityCompat.requestPermissions(this@BrowserActivityNative, missing.toTypedArray(), REQUEST_CODE_GECKO_PERMISSIONS)
        }

        // Kiosk showing the user's own dashboard: allow autoplay, storage, etc. without prompting
        override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> {
            return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
        }

        override fun onMediaPermissionRequest(session: GeckoSession, uri: String,
                                              video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                                              audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                                              callback: GeckoSession.PermissionDelegate.MediaCallback) {
            callback.grant(video?.firstOrNull()?.id, audio?.firstOrNull()?.id)
        }
    }

    private val promptDelegate = object : GeckoSession.PromptDelegate {
        override fun onAlertPrompt(session: GeckoSession,
                                   prompt: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
            if (!isFinishing) {
                AlertDialog.Builder(this@BrowserActivityNative)
                        .setMessage(prompt.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
            }
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onButtonPrompt(session: GeckoSession,
                                    prompt: GeckoSession.PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            if (isFinishing) {
                result.complete(prompt.dismiss())
                return result
            }
            AlertDialog.Builder(this@BrowserActivityNative)
                    .setMessage(prompt.message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                    }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
            return result
        }
    }

    private val scrollDelegate = object : GeckoSession.ScrollDelegate {
        override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
            contentScrollY = scrollY
            if (configuration.browserRefresh) {
                swipeContainer.isEnabled = scrollY == 0
            }
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onCrash(session: GeckoSession) {
            Timber.e("GeckoView content process crashed, reopening session")
            reopenSession(session)
        }

        override fun onKill(session: GeckoSession) {
            Timber.e("GeckoView content process was killed, reopening session")
            reopenSession(session)
        }
    }

    private fun reopenSession(crashed: GeckoSession) {
        if (isFinishing || crashed != session) {
            return
        }
        if (!crashed.isOpen) {
            crashed.open(getRuntime(this, zoomLevel))
        }
        initWebPageLoad()
    }

    private fun showCodeBottomSheet() {
        codeBottomSheet = CodeBottomSheetFragment.newInstance(configuration.settingsCode.toString(),
                object : CodeBottomSheetFragment.OnAlarmCodeFragmentListener {
                    override fun onComplete(code: String) {
                        codeBottomSheet?.dismiss()
                        openSettings()
                    }

                    override fun onCodeError() {
                        Toast.makeText(this@BrowserActivityNative, R.string.toast_code_invalid, Toast.LENGTH_SHORT).show()
                    }

                    override fun onCancel() {
                        codeBottomSheet?.dismiss()
                    }
                })
        codeBottomSheet?.show(supportFragmentManager, codeBottomSheet?.tag)
    }

    private fun setupSettingsButton() {
        // Set the location and transparency of the fab button
        val params: CoordinatorLayout.LayoutParams = CoordinatorLayout.LayoutParams(CoordinatorLayout.LayoutParams.WRAP_CONTENT, CoordinatorLayout.LayoutParams.WRAP_CONTENT)
        params.topMargin = 16
        params.leftMargin = 16
        params.rightMargin = 16
        params.bottomMargin = 16
        when (configuration.settingsLocation) {
            0 -> {
                params.gravity = Gravity.BOTTOM or Gravity.END
            }
            1 -> {
                params.gravity = Gravity.BOTTOM or Gravity.START
            }
            2 -> {
                params.gravity = Gravity.TOP or Gravity.END
            }
            3 -> {
                params.gravity = Gravity.TOP or Gravity.START
            }
        }
        launchSettingsFab.layoutParams = params
        when {
            configuration.settingsDisabled -> {
                launchSettingsFab.visibility = View.GONE
            }
            configuration.settingsTransparent -> {
                launchSettingsFab.visibility = View.VISIBLE
                launchSettingsFab.backgroundTintList = ContextCompat.getColorStateList(this, R.color.transparent)
                launchSettingsFab.compatElevation = 0f
                launchSettingsFab.imageAlpha = 0
            }
            else -> {
                launchSettingsFab.visibility = View.VISIBLE
                launchSettingsFab.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorAccent)
                launchSettingsFab.compatElevation = 4f
                launchSettingsFab.imageAlpha = 180
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_GECKO_PERMISSIONS = 14
        private const val ERROR_PAGE_URI = "resource://android/assets/error_page.html"
        private const val BRIDGE_EXTENSION_URI = "resource://android/assets/wallpanel-bridge/"
        private const val BRIDGE_EXTENSION_ID = "bridge@wallpanel.thanksmister.com"
        private const val BRIDGE_NATIVE_APP = "wallpanel"

        // Gecko allows only one runtime per process, so it outlives the activity
        private var runtime: GeckoRuntime? = null

        private fun getRuntime(activity: BrowserActivityNative, zoomLevel: Float): GeckoRuntime {
            runtime?.let { return it }
            val settings = GeckoRuntimeSettings.Builder()
                    .consoleOutput(BuildConfig.DEBUG)
            // WebView's initial-scale zoom maps to a display density override; applied at runtime start
            if (zoomLevel > 0f && zoomLevel != 1.0f) {
                settings.displayDensityOverride(activity.resources.displayMetrics.density * zoomLevel)
            }
            return GeckoRuntime.create(activity.applicationContext, settings.build()).also { runtime = it }
        }
    }
}
