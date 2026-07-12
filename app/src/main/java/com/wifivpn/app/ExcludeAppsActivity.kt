package com.wifivpn.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wifivpn.app.databinding.ActivityExcludeAppsBinding
import com.wifivpn.app.databinding.ItemAppRowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-select list of installed apps to exclude from the VPN tunnel.
 */
class ExcludeAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExcludeAppsBinding
    private val app get() = application as WifiVpnApp

    private val selected = linkedSetOf<String>()
    private var allApps: List<AppRow> = emptyList()
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExcludeAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = AppAdapter(
            selected = selected,
            onToggle = { pkg, checked ->
                if (checked) selected.add(pkg) else selected.remove(pkg)
            }
        )
        binding.appsList.layoutManager = LinearLayoutManager(this)
        binding.appsList.adapter = adapter

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filter(s?.toString().orEmpty())
            }
        })

        binding.btnSave.setOnClickListener { saveAndFinish() }

        lifecycleScope.launch {
            selected.clear()
            selected.addAll(app.configRepository.getExcludedApps())
            allApps = withContext(Dispatchers.IO) { loadInstalledApps() }
            filter(binding.searchInput.text?.toString().orEmpty())
        }
    }

    private fun loadInstalledApps(): List<AppRow> {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val self = packageName
        return apps
            .asSequence()
            .filter { it.packageName != self }
            // Prefer user-facing apps; still include system apps that can matter (Auto, etc.)
            .map { info ->
                AppRow(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                )
            }
            .sortedWith(
                compareBy<AppRow> { it.isSystem }
                    .thenBy { it.label.lowercase() }
            )
            .toList()
    }

    private fun filter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        adapter.submit(filtered)
    }

    private fun saveAndFinish() {
        lifecycleScope.launch {
            val packages = selected.toSet()
            app.configRepository.setExcludedApps(packages)
            val list = if (packages.isEmpty()) {
                "(none)"
            } else {
                packages.sorted().joinToString(",")
            }
            app.diagnosticLogger.i(
                "CONFIG",
                "excluded_apps count=${packages.size} packages=$list"
            )
            Toast.makeText(this@ExcludeAppsActivity, R.string.msg_exclusions_saved, Toast.LENGTH_SHORT)
                .show()
            finish()
        }
    }

    data class AppRow(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val icon: android.graphics.drawable.Drawable?
    )

    private class AppAdapter(
        private val selected: MutableSet<String>,
        private val onToggle: (packageName: String, checked: Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        private val items = mutableListOf<AppRow>()

        fun submit(list: List<AppRow>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemAppRowBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val binding: ItemAppRowBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(row: AppRow) {
                binding.appLabel.text = row.label
                binding.appPackage.text = row.packageName
                if (row.icon != null) {
                    binding.appIcon.setImageDrawable(row.icon)
                } else {
                    binding.appIcon.setImageResource(R.mipmap.ic_launcher)
                }

                binding.appCheck.setOnCheckedChangeListener(null)
                binding.appCheck.isChecked = selected.contains(row.packageName)
                binding.root.setOnClickListener {
                    val now = !binding.appCheck.isChecked
                    binding.appCheck.isChecked = now
                    onToggle(row.packageName, now)
                }
            }
        }
    }
}
