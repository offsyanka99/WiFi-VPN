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
import com.wifivpn.app.databinding.ActivityConfigurationBinding
import com.wifivpn.app.databinding.ItemWifiSsidBinding
import com.wifivpn.app.network.WifiConnectivityMonitor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * App settings: WireGuard config, trusted Wi‑Fi, VPN exclusions, auto-start.
 */
class ConfigurationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigurationBinding
    private val app get() = application as WifiVpnApp
    private lateinit var wifiMonitor: WifiConnectivityMonitor

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigurationBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            renderExcludedApps(app.configRepository.getExcludedApps())
            renderTrustedWifi(app.configRepository.getTrustedWifiSsids())
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
            toast(
                getString(
                    if (isChecked) R.string.msg_auto_start_on else R.string.msg_auto_start_off
                )
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
