/* SPDX-License-Identifier: MPL-2.0 */
package emu.skyline.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import emu.skyline.BuildConfig
import emu.skyline.R
import emu.skyline.UpdateChecker
import emu.skyline.UpdateInstaller
import emu.skyline.databinding.FragmentSettingsHomeBinding
import emu.skyline.databinding.SettingsHomeItemBinding
import emu.skyline.utils.WindowInsetsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The landing page of the settings, a list of categories that each open their own page
 * The page of a category is a [SettingsActivity] showing a [GlobalSettingsFragment] filtered down to the preference categories listed in the entry
 */
class SettingsHomeFragment : Fragment() {
    companion object {
        // Repository checked for updates -- change these if you fork this project further
        private const val UPDATE_REPO_OWNER = "67gh"
        private const val UPDATE_REPO_NAME = "skyline"
    }

    /**
     * @param keys The keys of the preference categories shown in the page, null if the entry is an action rather than a page
     */
    private class Entry(@StringRes val title : Int, @DrawableRes val icon : Int, val keys : Array<String>? = null)

    private val entries = listOf(
        Entry(R.string.general, R.drawable.ic_settings_general, arrayOf("category_content", "category_appearance")),
        Entry(R.string.system, R.drawable.ic_settings_system, arrayOf("category_system")),
        Entry(R.string.settings_controllers_ui, R.drawable.ic_settings_controller, arrayOf("category_input")),
        Entry(R.string.settings_graphics_ui, R.drawable.ic_settings_graphics, arrayOf("category_gpu", "category_hacks")),
        Entry(R.string.settings_display_ui, R.drawable.ic_settings_layout, arrayOf("category_presentation")),
        Entry(R.string.audio, R.drawable.ic_settings_audio, arrayOf("category_audio")),
        Entry(R.string.debug, R.drawable.ic_settings_debug, arrayOf("category_debug")),
        Entry(R.string.licenses, R.drawable.ic_settings_licenses, arrayOf("category_credits", "category_licenses")),
        Entry(R.string.sk_about, R.drawable.ic_settings_licenses)
    )

    override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View {
        val binding = FragmentSettingsHomeBinding.inflate(inflater, container, false)
        binding.settingsHomeList.layoutManager = LinearLayoutManager(requireContext())
        binding.settingsHomeList.adapter = EntriesAdapter()
        WindowInsetsHelper.setPadding(binding.settingsHomeList, bottom = true)
        return binding.root
    }

    private fun onEntryClicked(entry : Entry) {
        val keys = entry.keys
        if (keys == null) {
            showAboutDialog()
            return
        }

        startActivity(Intent(requireContext(), SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_TITLE, entry.title)
            putExtra(SettingsActivity.EXTRA_CATEGORIES, keys)
        })
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sk_about)
            .setMessage(getString(R.string.sk_about_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.sk_check_for_updates) { _, _ -> checkForUpdatesManually() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun checkForUpdatesManually() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateChecker.checkForUpdate(UPDATE_REPO_OWNER, UPDATE_REPO_NAME, BuildConfig.BUILD_TIMESTAMP * 1000L, BuildConfig.BUILD_COMMIT_FULL)
            }

            if (!isAdded) return@launch

            val updateInfo = result.getOrNull()
            if (result.isFailure || updateInfo == null) {
                showUpdateCheckResult(getString(if (result.isFailure) R.string.error else R.string.sk_update_up_to_date))
                return@launch
            }

            val downloadUrl = updateInfo.downloadUrl
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sk_update_available_title)
                .setMessage(getString(R.string.sk_update_available_message, updateInfo.tagName))
                .setPositiveButton(R.string.sk_update_available_action) { _, _ ->
                    if (downloadUrl != null)
                        UpdateInstaller.downloadAndInstall(requireContext(), downloadUrl, updateInfo.tagName, updateInfo.downloadIsArchive)
                    else
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.releaseUrl)))
                }
                .setNegativeButton(R.string.sk_update_available_dismiss, null)
                .show()
        }
    }

    private fun showUpdateCheckResult(message : String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sk_check_for_updates)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private inner class EntriesAdapter : RecyclerView.Adapter<EntriesAdapter.Holder>() {
        inner class Holder(val binding : SettingsHomeItemBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : Holder = Holder(SettingsHomeItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder : Holder, position : Int) {
            val entry = entries[position]
            holder.binding.icon.setImageResource(entry.icon)
            holder.binding.title.setText(entry.title)
            holder.binding.root.setOnClickListener { onEntryClicked(entry) }
        }
    }
}
