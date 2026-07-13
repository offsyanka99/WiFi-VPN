package com.wifivpn.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wifivpn.app.databinding.ActivityMainBinding
import com.wifivpn.app.databinding.DialogAboutBinding
import com.wifivpn.app.network.WifiConnectivityMonitor
import com.wifivpn.app.service.WifiMonitorService
import com.wifivpn.app.tile.MonitorTileService
import com.wifivpn.app.widget.StatusWidgets
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as WifiVpnApp
    private lateinit var wifiMonitor: WifiConnectivityMonitor

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast(getString(R.string.msg_notification_permission_needed))
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (!granted) {
            toast(getString(R.string.msg_location_permission_needed))
        }
        refreshWifiStatusHint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        wifiMonitor = WifiConnectivityMonitor(this)

        requestNotificationPermissionIfNeeded()
        requestSsidPermissionsIfNeeded()

        binding.btnToggleMonitoring.setOnClickListener { toggleMonitoring() }
        binding.linkConfiguration.setOnClickListener {
            startActivity(Intent(this, ConfigurationActivity::class.java))
        }
        binding.linkAbout.setOnClickListener { showAboutDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    WifiMonitorService.uiState.collectLatest { state ->
                        renderState(state)
                        StatusWidgets.updateAll(this@MainActivity)
                    }
                }
                // Keep VPN indicator in sync if tunnel state changes outside a UI-state write
                launch {
                    app.wireGuardManager.stateFlow.collectLatest { tunnelState ->
                        val monitoring = WifiMonitorService.uiState.value.monitoring
                        if (!monitoring) return@collectLatest
                        val up = tunnelState == com.wireguard.android.backend.Tunnel.State.UP
                        binding.statusVpn.text = getString(
                            if (up) R.string.status_vpn_on else R.string.status_vpn_off
                        )
                        binding.statusVpn.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                if (up) R.color.status_ok else R.color.status_off
                            )
                        )
                        StatusWidgets.updateAll(this@MainActivity)
                    }
                }
            }
        }

        handleStartMonitoringIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartMonitoringIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!WifiMonitorService.uiState.value.monitoring) {
            refreshWifiStatusHint()
        }
        // Keep QS tile + home widgets in sync when returning to the app
        MonitorTileService.requestUpdate(this)
        StatusWidgets.updateAll(this)
    }

    /**
     * QS tile / home widget starts monitoring via this Activity so the location
     * FGS can start from a foreground-eligible process (Android 14+ requirement).
     */
    private fun handleStartMonitoringIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_MONITORING, false) != true) return
        // Consume so rotation / re-delivery does not restart repeatedly
        intent.removeExtra(EXTRA_START_MONITORING)
        val fromTile = intent.getBooleanExtra(EXTRA_FROM_TILE, false)
        intent.removeExtra(EXTRA_FROM_TILE)
        val source = intent.getStringExtra(EXTRA_START_SOURCE)
            ?: if (fromTile) WifiMonitorService.SOURCE_TILE else WifiMonitorService.SOURCE_UI
        intent.removeExtra(EXTRA_START_SOURCE)
        startMonitoringInternal(source = source)
    }

    /**
     * Pixel / Android 15+ draws edge-to-edge; pad content below the status bar
     * so the title does not sit under system icons.
     */
    private fun applySystemBarInsets() {
        val baseTop = binding.root.paddingTop
        val baseBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = bars.top + baseTop,
                bottom = bars.bottom + baseBottom
            )
            windowInsets
        }
    }

    private fun refreshWifiStatusHint() {
        lifecycleScope.launch {
            val trusted = app.configRepository.getTrustedWifiSsids()
            val snap = wifiMonitor.snapshot(trusted)
            binding.statusWifi.text = formatWifiStatus(snap)
            binding.statusWifi.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    when {
                        snap.onTrustedWifi -> R.color.status_ok
                        snap.wifiConnected -> R.color.status_warn
                        else -> R.color.status_warn
                    }
                )
            )
        }
    }

    private fun formatWifiStatus(snap: WifiConnectivityMonitor.WifiSnapshot): String {
        return when {
            !snap.wifiConnected -> getString(R.string.status_wifi_disconnected)
            snap.onTrustedWifi && snap.ssid != null ->
                getString(R.string.status_wifi_trusted, snap.ssid)
            snap.ssid != null ->
                getString(R.string.status_wifi_other, snap.ssid)
            else -> getString(R.string.status_wifi_unknown)
        }
    }

    private fun renderState(state: WifiMonitorService.MonitorUiState) {
        binding.statusMonitoring.text = if (state.monitoring) {
            getString(R.string.status_monitoring_on)
        } else {
            getString(R.string.status_monitoring_off)
        }
        binding.statusMonitoring.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.monitoring) R.color.status_ok else R.color.status_off
            )
        )

        val snap = WifiConnectivityMonitor.WifiSnapshot(
            wifiConnected = state.wifiConnected,
            ssid = state.currentSsid,
            onTrustedWifi = state.onTrustedWifi
        )
        binding.statusWifi.text = formatWifiStatus(snap)
        binding.statusWifi.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    state.onTrustedWifi -> R.color.status_ok
                    state.wifiConnected -> R.color.status_warn
                    else -> R.color.status_warn
                }
            )
        )

        binding.statusVpn.text = if (state.vpnActive) {
            getString(R.string.status_vpn_on)
        } else {
            getString(R.string.status_vpn_off)
        }
        binding.statusVpn.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.vpnActive) R.color.md_theme_primary else R.color.status_off
            )
        )

        binding.statusMessage.text = state.message
        binding.btnToggleMonitoring.text = if (state.monitoring) {
            getString(R.string.btn_stop_monitoring)
        } else {
            getString(R.string.btn_start_monitoring)
        }
    }

    private fun showAboutDialog() {
        val about = DialogAboutBinding.inflate(layoutInflater)
        about.aboutIcon.setImageResource(R.mipmap.ic_launcher)
        about.aboutName.text = getString(R.string.app_name)
        about.aboutVersion.text =
            getString(R.string.about_version, com.wifivpn.app.util.AppInfo.versionName(this))
        about.aboutEmail.text = getString(R.string.about_email)
        about.aboutYear.text = getString(R.string.about_year)

        MaterialAlertDialogBuilder(this)
            .setView(about.root)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun toggleMonitoring() {
        val running = WifiMonitorService.uiState.value.monitoring ||
            WifiMonitorService.instance != null

        if (running) {
            app.diagnosticLogger.i("UI", "stop monitoring requested source=ui")
            WifiMonitorService.stop(this, WifiMonitorService.SOURCE_UI)
            return
        }
        startMonitoringInternal(source = WifiMonitorService.SOURCE_UI)
    }

    private fun startMonitoringInternal(source: String) {
        lifecycleScope.launch {
            val raw = app.configRepository.getWireGuardConfig()
            if (raw.isBlank()) {
                toast(getString(R.string.msg_config_empty))
                return@launch
            }
            val parsed = app.wireGuardManager.parseConfig(raw)
            if (parsed.isFailure) {
                toast(
                    getString(
                        R.string.msg_config_invalid,
                        parsed.exceptionOrNull()?.message ?: "parse error"
                    )
                )
                return@launch
            }

            if (app.configRepository.getTrustedWifiSsids().isEmpty()) {
                toast(getString(R.string.msg_trusted_wifi_required))
                return@launch
            }

            if (!wifiMonitor.hasSsidPermission()) {
                requestSsidPermissionsIfNeeded()
                toast(getString(R.string.msg_location_permission_needed))
                return@launch
            }

            if (app.wireGuardManager.prepareVpnPermission() != null) {
                Log.i(TAG, "Start monitoring blocked: VPN permission not granted yet")
                toast(getString(R.string.msg_vpn_open_configuration))
                startActivity(Intent(this@MainActivity, ConfigurationActivity::class.java))
                return@launch
            }

            try {
                app.diagnosticLogger.i("UI", "start monitoring requested source=$source")
                WifiMonitorService.start(this@MainActivity, source)
                // startForegroundService is async — wait so we don't push a stale
                // "Start" widget/tile state that can overwrite the service update.
                withTimeoutOrNull(5_000) {
                    WifiMonitorService.uiState.first { it.monitoring }
                }
                MonitorTileService.requestUpdate(this@MainActivity)
                StatusWidgets.updateAllSoon(this@MainActivity)
                if (source == WifiMonitorService.SOURCE_TILE ||
                    source == WifiMonitorService.SOURCE_WIDGET
                ) {
                    // Return to previous app / home after tile or widget start
                    moveTaskToBack(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start monitoring", e)
                app.diagnosticLogger.logException("UI", "Failed to start monitoring", e)
                toast(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestSsidPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (needed.isNotEmpty()) {
            locationPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"

        /** QS tile / widget: start monitoring from a foreground-eligible Activity. */
        const val EXTRA_START_MONITORING = "com.wifivpn.app.extra.START_MONITORING"
        const val EXTRA_FROM_TILE = "com.wifivpn.app.extra.FROM_TILE"
        /** Optional [WifiMonitorService] source string (e.g. tile, widget). */
        const val EXTRA_START_SOURCE = "com.wifivpn.app.extra.START_SOURCE"
    }
}
