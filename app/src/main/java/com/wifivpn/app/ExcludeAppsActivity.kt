package com.wifivpn.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wifivpn.app.databinding.ActivityExcludeAppsBinding
import com.wifivpn.app.databinding.ItemAppRowBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-select list of installed apps to exclude from the VPN tunnel.
 * Icons load lazily on bind; list updates use [DiffUtil].
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
            scope = lifecycleScope,
            selected = selected,
            packageManager = packageManager,
            onToggle = { pkg, checked ->
                if (checked) selected.add(pkg) else selected.remove(pkg)
            }
        )
        binding.appsList.layoutManager = LinearLayoutManager(this)
        binding.appsList.adapter = adapter
        binding.appsList.setHasFixedSize(true)
        binding.appsList.setItemViewCacheSize(20)

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
            .map { info ->
                AppRow(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
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
        adapter.submitList(filtered)
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
        val isSystem: Boolean
    )

    private class AppAdapter(
        private val scope: CoroutineScope,
        private val selected: MutableSet<String>,
        private val packageManager: PackageManager,
        private val onToggle: (packageName: String, checked: Boolean) -> Unit
    ) : ListAdapter<AppRow, AppAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemAppRowBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onViewRecycled(holder: VH) {
            holder.cancelIconLoad()
            super.onViewRecycled(holder)
        }

        inner class VH(private val binding: ItemAppRowBinding) :
            RecyclerView.ViewHolder(binding.root) {

            private var iconJob: Job? = null
            private var boundPackage: String? = null

            fun bind(row: AppRow) {
                cancelIconLoad()
                boundPackage = row.packageName
                binding.appLabel.text = row.label
                binding.appPackage.text = row.packageName
                binding.appIcon.setImageResource(R.mipmap.ic_launcher)

                val pkg = row.packageName
                iconJob = scope.launch {
                    val icon: Drawable? = withContext(Dispatchers.IO) {
                        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
                    }
                    if (boundPackage == pkg && icon != null) {
                        binding.appIcon.setImageDrawable(icon)
                    }
                }

                binding.appCheck.setOnCheckedChangeListener(null)
                binding.appCheck.isChecked = selected.contains(row.packageName)
                binding.root.setOnClickListener {
                    val now = !binding.appCheck.isChecked
                    binding.appCheck.isChecked = now
                    onToggle(row.packageName, now)
                }
            }

            fun cancelIconLoad() {
                iconJob?.cancel()
                iconJob = null
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<AppRow>() {
                override fun areItemsTheSame(oldItem: AppRow, newItem: AppRow): Boolean =
                    oldItem.packageName == newItem.packageName

                override fun areContentsTheSame(oldItem: AppRow, newItem: AppRow): Boolean =
                    oldItem == newItem
            }
        }
    }
}
