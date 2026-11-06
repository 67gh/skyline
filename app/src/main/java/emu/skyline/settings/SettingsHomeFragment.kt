/* SPDX-License-Identifier: MPL-2.0 */
package emu.skyline.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import emu.skyline.R
import emu.skyline.databinding.FragmentSettingsHomeBinding
import emu.skyline.databinding.SettingsHomeItemBinding
import emu.skyline.utils.WindowInsetsHelper

/** UI-only category index inspired by Strato; preserves Skyline's original preference pages. */
class SettingsHomeFragment : Fragment() {
    private data class Entry(@StringRes val title: Int, @DrawableRes val icon: Int, val keys: Array<String>)
    private val entries = listOf(
        Entry(R.string.general, R.drawable.ic_settings_general, arrayOf("category_content", "category_appearance")),
        Entry(R.string.system, R.drawable.ic_settings_system, arrayOf("category_system")),
        Entry(R.string.settings_controllers_ui, R.drawable.ic_settings_controller, arrayOf("category_input")),
        Entry(R.string.settings_graphics_ui, R.drawable.ic_settings_graphics, arrayOf("category_gpu", "category_hacks")),
        Entry(R.string.settings_display_ui, R.drawable.ic_settings_layout, arrayOf("category_presentation")),
        Entry(R.string.audio, R.drawable.ic_settings_audio, arrayOf("category_audio")),
        Entry(R.string.debug, R.drawable.ic_settings_debug, arrayOf("category_debug")),
        Entry(R.string.licenses, R.drawable.ic_settings_licenses, arrayOf("category_credits", "category_licenses"))
    )
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentSettingsHomeBinding.inflate(inflater, container, false)
        binding.settingsHomeList.layoutManager = LinearLayoutManager(requireContext())
        binding.settingsHomeList.adapter = EntriesAdapter()
        WindowInsetsHelper.setPadding(binding.settingsHomeList, bottom = true)
        return binding.root
    }
    private inner class EntriesAdapter : RecyclerView.Adapter<EntriesAdapter.Holder>() {
        inner class Holder(val binding: SettingsHomeItemBinding): RecyclerView.ViewHolder(binding.root)
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(SettingsHomeItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.binding.icon.setImageResource(entry.icon)
            holder.binding.title.setText(entry.title)
            holder.binding.root.setOnClickListener {
                startActivity(Intent(requireContext(), SettingsActivity::class.java).apply {
                    putExtra(SettingsActivity.EXTRA_TITLE, entry.title)
                    putExtra(SettingsActivity.EXTRA_CATEGORIES, entry.keys)
                })
            }
        }
    }
}
