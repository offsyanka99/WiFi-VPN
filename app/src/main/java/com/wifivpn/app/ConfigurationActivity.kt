package com.wifivpn.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
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
import com.wifivpn.app.configuration.BackgroundSettingsHelper
import com.wifivpn.app.configuration.DiagnosticLogHelper
import com.wifivpn.app.data.ConfigRepository
import com.wifivpn.app.databinding.ActivityConfigurationBinding
import com.wifivpn.app.databinding.ItemWifiSsidBinding
import com.wifivpn.app.log.DiagnosticSupport
import com.wifivpn.app.network.WifiConnectivityMonitor
import com.wifivpn.app.tile.MonitorTileService
import com.wifivpn.app.util.AppInfo
import com.wifivpn.app.widget.StatusWidgets
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * App settings: WireGuard config, trusted Wi‑Fi, VPN exclusions,
 * VPN permission, battery / unused-app background settings, auto-start.
 */
class ConfigurationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigurationBinding
    private val app get() = application as WifiVpnApp
    private lateinit var wifiMonitor: WifiConnectivityMonitor

    private var retryAttempts: Int = ConfigRepository.DEFAULT_VPN_RETRY_ATTEMPTS
    private var retryDelaySeconds: Int = ConfigRepository.DEFAULT_VPN_RETRY_DELAY_SECONDS

    private lateinit var backgroundSettings: BackgroundSettingsHelper
    private lateinit var diagnosticLog: DiagnosticLogHelper

    private val openConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importConfigFromUri(uri)
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (!granted) {
            toast(getString(R.string.msg_location_permission_needed))
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultCode = result.resultCode
        val stillNeedsPermission = app.wireGuardManager.prepareVpnPermission() != null
        Log.i(
            TAG,
            "VPN permission activity result: resultCode=$resultCode " +
                "(${resultCodeLabel(resultCode)}), stillNeedsPermission=$stillNeedsPermission"
        )
        updateVpnPermissionButton()
        when {
            resultCode == RESULT_OK && !stillNeedsPermission -> {
                logConfig("vpn_permission granted")
                toast(getString(R.string.msg_vpn_permission_granted))
            }
            resultCode == RESULT_OK && stillNeedsPermission -> {
                logConfig("vpn_permission still_missing after OK result")
                showInfoDialog(
                    R.string.msg_vpn_permission_title,
                    getString(R.string.msg_vpn_permission_still_missing)
                )
            }
            else -> {
                logConfig("vpn_permission denied or cancelled resultCode=$resultCode")
                showInfoDialog(
                    R.string.msg_vpn_permission_title,
                    getString(R.string.msg_vpn_permission_denied)
                )
            }
        }
    }

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (::backgroundSettings.isInitialized) backgroundSettings.refreshBattery()
    }

    private val unusedAppLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (::backgroundSettings.isInitialized) backgroundSettings.refreshUnused()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityConfigurationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        wifiMonitor = WifiConnectivityMonitor(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnLoadConfig.setOnClickListener {
            openConfigLauncher.launch(
                arrayOf(
                    "text/*",
                    "application/octet-stream",
                    "application/*",
                    "*/*"
                )
            )
        }
        binding.btnClearConfig.setOnClickListener { clearConfig() }
        binding.btnAddWifi.setOnClickListener { addSsidFromInput() }
        binding.btnAddCurrentWifi.setOnClickListener { addCurrentSsid() }
        binding.btnChooseExcludedApps.setOnClickListener {
            startActivity(Intent(this, ExcludeAppsActivity::class.java))
        }
        binding.btnVpnPermission.setOnClickListener { requestVpnPermission() }
        binding.btnRetryAttemptsMinus.setOnClickListener {
            adjustRetryAttempts(-1)
        }
        binding.btnRetryAttemptsPlus.setOnClickListener {
            adjustRetryAttempts(1)
        }
        binding.btnRetryDelayMinus.setOnClickListener {
            adjustRetryDelay(-1)
        }
        binding.btnRetryDelayPlus.setOnClickListener {
            adjustRetryDelay(1)
        }
        backgroundSettings = BackgroundSettingsHelper(
            activity = this,
            batterySwitch = binding.switchBatteryOptimization,
            unusedSwitch = binding.switchManageUnused,
            batteryLauncher = batteryOptLauncher,
            unusedLauncher = unusedAppLauncher,
            logConfig = ::logConfig,
            toast = ::toast
        )
        diagnosticLog = DiagnosticLogHelper(
            activity = this,
            app = app,
            switch = binding.switchDiagnosticLogging,
            btnSend = binding.btnSendDiagnosticLog,
            btnClear = binding.btnClearDiagnosticLog,
            logConfig = ::logConfig,
            toast = ::toast
        )
        diagnosticLog.bindClicks()
        bindSectionInfoIcons()
        binding.switchBatteryOptimization.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed || backgroundSettings.syncingBattery) {
                return@setOnCheckedChangeListener
            }
            backgroundSettings.onBatteryToggled(isChecked)
        }
        binding.switchManageUnused.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed || backgroundSettings.syncingUnused) {
                return@setOnCheckedChangeListener
            }
            backgroundSettings.onUnusedToggled(isChecked)
        }
        binding.switchAutoStart.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            onAutoStartToggled(isChecked)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.configRepository.wireGuardConfigFileName.collectLatest { name ->
                        binding.configFileName.text = if (name.isBlank()) {
                            getString(R.string.config_not_loaded)
                        } else {
                            getString(R.string.config_loaded, name)
                        }
                        binding.btnClearConfig.isEnabled = name.isNotBlank()
                    }
                }
                launch {
                    app.configRepository.trustedWifiSsids.collectLatest { ssids ->
                        renderTrustedWifi(ssids)
                    }
                }
                launch {
                    app.configRepository.excludedApps.collectLatest { packages ->
                        renderExcludedApps(packages)
                    }
                }
                launch {
                    app.configRepository.autoStartEnabled.collectLatest { enabled ->
                        if (binding.switchAutoStart.isChecked != enabled) {
                            binding.switchAutoStart.isChecked = enabled
                        }
                    }
                }
                launch {
                    app.configRepository.diagnosticLoggingEnabled.collectLatest { enabled ->
                        diagnosticLog.applyEnabledFromPrefs(enabled)
                    }
                }
                launch {
                    app.configRepository.vpnRetryAttempts.collectLatest { value ->
                        retryAttempts = value
                        renderRetryUi()
                    }
                }
                launch {
                    app.configRepository.vpnRetryDelaySeconds.collectLatest { value ->
                        retryDelaySeconds = value
                        renderRetryUi()
                    }
                }
            }
        }

        updateVpnPermissionButton()
        backgroundSettings.refreshAll()
        renderRetryUi()
    }

    private fun renderRetryUi() {
        binding.retryAttemptsValue.text = retryAttempts.toString()
        binding.retryDelayValue.text =
            getString(R.string.vpn_retry_delay_value, retryDelaySeconds)
        binding.btnRetryAttemptsMinus.isEnabled =
            retryAttempts > ConfigRepository.MIN_VPN_RETRY_ATTEMPTS
        binding.btnRetryAttemptsPlus.isEnabled =
            retryAttempts < ConfigRepository.MAX_VPN_RETRY_ATTEMPTS
        binding.btnRetryDelayMinus.isEnabled =
            retryDelaySeconds > ConfigRepository.MIN_VPN_RETRY_DELAY_SECONDS
        binding.btnRetryDelayPlus.isEnabled =
            retryDelaySeconds < ConfigRepository.MAX_VPN_RETRY_DELAY_SECONDS
    }

    private fun adjustRetryAttempts(delta: Int) {
        val next = ConfigRepository.clampRetryAttempts(retryAttempts + delta)
        if (next == retryAttempts) return
        val previous = retryAttempts
        retryAttempts = next
        renderRetryUi()
        lifecycleScope.launch {
            app.configRepository.setVpnRetryAttempts(next)
            logConfig("vpn_retry_attempts $previous -> $next")
        }
    }

    private fun adjustRetryDelay(delta: Int) {
        val next = ConfigRepository.clampRetryDelaySeconds(retryDelaySeconds + delta)
        if (next == retryDelaySeconds) return
        val previous = retryDelaySeconds
        retryDelaySeconds = next
        renderRetryUi()
        lifecycleScope.launch {
            app.configRepository.setVpnRetryDelaySeconds(next)
            logConfig("vpn_retry_delay_seconds $previous -> $next")
        }
    }

    override fun onResume() {
        super.onResume()
        updateVpnPermissionButton()
        backgroundSettings.refreshAll()
        lifecycleScope.launch {
            renderExcludedApps(app.configRepository.getExcludedApps())
            renderTrustedWifi(app.configRepository.getTrustedWifiSsids())
        }
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            windowInsets
        }
    }

    private fun requestVpnPermission() {
        val prepare = app.wireGuardManager.prepareVpnPermission()
        if (prepare == null) {
            Log.i(TAG, "VPN permission already granted (prepare returned null)")
            toast(getString(R.string.msg_vpn_permission_already_granted))
            updateVpnPermissionButton()
            return
        }

        Log.i(
            TAG,
            "Launching VPN permission dialog: action=${prepare.action}, " +
                "component=${prepare.component}"
        )
        try {
            vpnPermissionLauncher.launch(prepare)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch VPN permission intent", e)
            showInfoDialog(
                R.string.msg_vpn_permission_title,
                getString(
                    R.string.msg_vpn_permission_launch_failed,
                    e.message ?: e.javaClass.simpleName
                )
            )
        }
    }

    private fun updateVpnPermissionButton() {
        val needs = app.wireGuardManager.prepareVpnPermission() != null
        binding.btnVpnPermission.isEnabled = needs
        binding.btnVpnPermission.alpha = if (needs) 1f else 0.5f
        Log.d(TAG, "VPN permission button needsGrant=$needs")
    }

    private fun importConfigFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val fileName = queryDisplayName(uri) ?: "config.conf"
                val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    .orEmpty()
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
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                app.configRepository.setWireGuardConfig(raw, fileName)
                logConfig(
                    "wireguard_config loaded file=$fileName bytes=${raw.length} " +
                        "fingerprint=${DiagnosticSupport.configFingerprint(raw)}"
                )
                MonitorTileService.requestUpdate(this@ConfigurationActivity)
                StatusWidgets.updateAll(this@ConfigurationActivity)
                toast(getString(R.string.msg_config_saved))
            } catch (e: Exception) {
                app.diagnosticLogger.logException(
                    CAT_CONFIG,
                    "wireguard_config import failed: ${e.message ?: e.javaClass.simpleName}",
                    e
                )
                toast(getString(R.string.msg_config_read_failed, e.message ?: "error"))
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return uri.lastPathSegment
    }

    private fun clearConfig() {
        lifecycleScope.launch {
            app.configRepository.clearWireGuardConfig()
            logConfig("wireguard_config cleared")
            MonitorTileService.requestUpdate(this@ConfigurationActivity)
            StatusWidgets.updateAll(this@ConfigurationActivity)
            toast(getString(R.string.msg_config_cleared))
        }
    }

    private fun renderTrustedWifi(ssids: Set<String>) {
        if (ssids.isEmpty()) {
            binding.trustedWifiSummary.text = getString(R.string.trusted_wifi_none)
        } else {
            binding.trustedWifiSummary.text = getString(R.string.trusted_wifi_count, ssids.size)
        }

        binding.trustedWifiList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        ssids.sortedBy { it.lowercase() }.forEach { ssid ->
            val row = ItemWifiSsidBinding.inflate(inflater, binding.trustedWifiList, false)
            row.ssidName.text = ssid
            row.btnRemoveSsid.setOnClickListener {
                lifecycleScope.launch {
                    app.configRepository.removeTrustedWifiSsid(ssid)
                    logConfig("trusted_wifi removed ssid=$ssid")
                    toast(getString(R.string.msg_wifi_removed, ssid))
                }
            }
            binding.trustedWifiList.addView(row.root)
        }
    }

    private fun addSsidFromInput() {
        val raw = binding.ssidInput.text?.toString().orEmpty()
        lifecycleScope.launch {
            val normalized = com.wifivpn.app.data.ConfigRepository.normalizeSsid(raw)
            if (normalized == null) {
                toast(getString(R.string.msg_wifi_empty))
                return@launch
            }
            val added = app.configRepository.addTrustedWifiSsid(normalized)
            if (added) {
                binding.ssidInput.text?.clear()
                logConfig("trusted_wifi added ssid=$normalized source=manual")
                toast(getString(R.string.msg_wifi_added, normalized))
            } else {
                toast(getString(R.string.msg_wifi_exists, normalized))
            }
        }
    }

    private fun addCurrentSsid() {
        if (!wifiMonitor.hasSsidPermission()) {
            requestSsidPermissionsIfNeeded()
            toast(getString(R.string.msg_location_permission_needed))
            return
        }
        val ssid = wifiMonitor.currentSsid()
        if (ssid == null) {
            toast(getString(R.string.msg_wifi_current_unknown))
            return
        }
        lifecycleScope.launch {
            val added = app.configRepository.addTrustedWifiSsid(ssid)
            if (added) {
                logConfig("trusted_wifi added ssid=$ssid source=current")
                toast(getString(R.string.msg_wifi_added, ssid))
            } else {
                toast(getString(R.string.msg_wifi_exists, ssid))
            }
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

    /** Count + friendly app names only (no package names). */
    private fun renderExcludedApps(packages: Set<String>) {
        if (packages.isEmpty()) {
            binding.excludedAppsSummary.text = getString(R.string.excluded_none)
            binding.excludedAppsList.visibility = View.GONE
            binding.excludedAppsList.text = ""
            return
        }

        binding.excludedAppsSummary.text = getString(R.string.excluded_count, packages.size)
        val pm = packageManager
        val labels = packages.map { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
        }.sortedBy { it.lowercase() }
        binding.excludedAppsList.text = labels.joinToString("\n")
        binding.excludedAppsList.visibility = View.VISIBLE
    }

    private fun onAutoStartToggled(isChecked: Boolean) {
        lifecycleScope.launch {
            if (isChecked && !app.configRepository.canStartMonitoring()) {
                binding.switchAutoStart.isChecked = false
                toast(
                    getString(R.string.msg_config_empty) + " / " +
                        getString(R.string.msg_trusted_wifi_required)
                )
                return@launch
            }
            app.configRepository.setAutoStartEnabled(isChecked)
            logConfig("auto_start ${if (isChecked) "enabled" else "disabled"}")
            toast(
                getString(
                    if (isChecked) R.string.msg_auto_start_on else R.string.msg_auto_start_off
                )
            )
        }
    }

    private fun logConfig(message: String) {
        app.diagnosticLogger.i(CAT_CONFIG, message)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun bindSectionInfoIcons() {
        binding.infoTrustedWifi.setOnClickListener {
            showInfoDialog(R.string.label_trusted_wifi, getString(R.string.trusted_wifi_help))
        }
        binding.infoExcludedApps.setOnClickListener {
            showInfoDialog(R.string.label_excluded_apps, getString(R.string.excluded_apps_help))
        }
        binding.infoVpnRetry.setOnClickListener {
            showInfoDialog(R.string.label_vpn_retry, getString(R.string.vpn_retry_help))
        }
        binding.infoDiagnosticLog.setOnClickListener {
            showInfoDialog(R.string.label_diagnostic_log, getString(R.string.diagnostic_log_help))
        }
    }

    private fun showInfoDialog(titleRes: Int, message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }

    private fun resultCodeLabel(resultCode: Int): String = when (resultCode) {
        Activity.RESULT_OK -> "RESULT_OK"
        Activity.RESULT_CANCELED -> "RESULT_CANCELED"
        Activity.RESULT_FIRST_USER -> "RESULT_FIRST_USER"
        else -> "UNKNOWN"
    }

    companion object {
        private const val TAG = "ConfigurationActivity"
        private const val CAT_CONFIG = "CONFIG"
    }
}
