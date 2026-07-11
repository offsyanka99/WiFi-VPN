package com.wifivpn.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wifivpn.app.databinding.ActivityMainBinding
import com.wifivpn.app.databinding.DialogAboutBinding
import com.wifivpn.app.databinding.ItemWifiSsidBinding
import com.wifivpn.app.network.WifiConnectivityMonitor
import com.wifivpn.app.service.WifiMonitorService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as WifiVpnApp
    private lateinit var wifiMonitor: WifiConnectivityMonitor

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            toast("VPN permission granted")
            updateVpnPermissionButton()
        } else {
            toast(getString(R.string.msg_vpn_permission_needed))
        }
    }

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

    private val openConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importConfigFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        wifiMonitor = WifiConnectivityMonitor(this)

        requestNotificationPermissionIfNeeded()
        requestSsidPermissionsIfNeeded()

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
        binding.btnChooseExcludedApps.setOnClickListener {
            startActivity(Intent(this, ExcludeAppsActivity::class.java))
        }
        binding.btnAddWifi.setOnClickListener { addSsidFromInput() }
        binding.btnAddCurrentWifi.setOnClickListener { addCurrentSsid() }
        binding.btnVpnPermission.setOnClickListener { requestVpnPermission() }
        binding.btnToggleMonitoring.setOnClickListener { toggleMonitoring() }
        binding.linkAbout.setOnClickListener { showAboutDialog() }
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
                    app.configRepository.excludedApps.collectLatest { packages ->
                        renderExcludedApps(packages)
                    }
                }
                launch {
                    app.configRepository.trustedWifiSsids.collectLatest { ssids ->
                        renderTrustedWifi(ssids)
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
                    WifiMonitorService.uiState.collectLatest { state ->
                        renderState(state)
                    }
                }
            }
        }

        updateVpnPermissionButton()
    }

    override fun onResume() {
        super.onResume()
        updateVpnPermissionButton()
        if (!WifiMonitorService.uiState.value.monitoring) {
            refreshWifiStatusHint()
        }
        lifecycleScope.launch {
            renderExcludedApps(app.configRepository.getExcludedApps())
            renderTrustedWifi(app.configRepository.getTrustedWifiSsids())
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
                toast(getString(R.string.msg_wifi_added, ssid))
            } else {
                toast(getString(R.string.msg_wifi_exists, ssid))
            }
        }
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
                toast(getString(R.string.msg_config_saved))
            } catch (e: Exception) {
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
            toast(getString(R.string.msg_config_cleared))
        }
    }

    private fun onAutoStartToggled(isChecked: Boolean) {
        lifecycleScope.launch {
            if (isChecked && !app.configRepository.canStartMonitoring()) {
                binding.switchAutoStart.isChecked = false
                toast(getString(R.string.msg_config_empty) + " / " + getString(R.string.msg_trusted_wifi_required))
                return@launch
            }
            app.configRepository.setAutoStartEnabled(isChecked)
            toast(
                getString(
                    if (isChecked) R.string.msg_auto_start_on else R.string.msg_auto_start_off
                )
            )
        }
    }

    private fun showAboutDialog() {
        val about = DialogAboutBinding.inflate(layoutInflater)
        about.aboutIcon.setImageResource(R.mipmap.ic_launcher)
        about.aboutName.text = getString(R.string.app_name)
        about.aboutVersion.text = getString(R.string.about_version, appVersionName())
        about.aboutAuthor.text = getString(R.string.about_author)
        about.aboutYear.text = getString(R.string.about_year)

        MaterialAlertDialogBuilder(this)
            .setView(about.root)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun appVersionName(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName?.takeIf { it.isNotBlank() } ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    private fun requestVpnPermission() {
        val prepare = app.wireGuardManager.prepareVpnPermission()
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            toast("VPN permission already granted")
            updateVpnPermissionButton()
        }
    }

    private fun updateVpnPermissionButton() {
        val needs = app.wireGuardManager.prepareVpnPermission() != null
        binding.btnVpnPermission.isEnabled = needs
        binding.btnVpnPermission.alpha = if (needs) 1f else 0.5f
    }

    private fun toggleMonitoring() {
        val running = WifiMonitorService.uiState.value.monitoring ||
            WifiMonitorService.instance != null

        if (running) {
            WifiMonitorService.stop(this)
            return
        }

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
                toast(getString(R.string.msg_vpn_permission_needed))
                requestVpnPermission()
                return@launch
            }

            WifiMonitorService.start(this@MainActivity)
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
}
