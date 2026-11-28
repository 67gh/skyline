/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2020 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package emu.skyline

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import emu.skyline.data.AppItem
import emu.skyline.data.AppItemTag
import emu.skyline.databinding.AppDialogBinding
import emu.skyline.loader.LoaderResult
import emu.skyline.settings.SettingsActivity
import emu.skyline.utils.GameDataManager
import emu.skyline.utils.serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * This dialog is used to show extra game metadata and provide extra options such as pinning the game to the home screen,
 * deleting the shader cache of the game and exporting, importing or deleting its saves
 */
class AppDialog : BottomSheetDialogFragment() {
    companion object {
        /**
         * @param item This is used to hold the [AppItem] between instances
         */
        fun newInstance(item : AppItem) : AppDialog {
            val args = Bundle()
            args.putSerializable(AppItemTag, item)

            val fragment = AppDialog()
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var binding : AppDialogBinding

    private val item by lazy { requireArguments().serializable<AppItem>(AppItemTag)!! }

    private val exportSavesPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) exportSaves(uri) }

    private val importSavesPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmImportSaves(uri) }

    private val dataButtons get() = listOf(binding.gameDeleteShaderCache, binding.gameExportSaves, binding.gameImportSaves, binding.gameDeleteSaves)

    /**
     * This inflates the layout of the dialog after initial view creation
     */
    override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) = AppDialogBinding.inflate(inflater).also { binding = it }.root

    override fun onViewCreated(view : View, savedInstanceState : Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set the peek height after the root view has been laid out
        view.apply {
            post {
                val behavior = BottomSheetBehavior.from(parent as View)
                behavior.peekHeight = height
            }
        }

        binding.gameIcon.setImageBitmap(item.bitmapIcon)
        binding.gameTitle.text = item.title
        binding.gameVersion.text = item.version ?: item.loaderResultString(requireContext())
        binding.gameTitleId.text = item.titleId
        binding.gameAuthor.text = item.author

        binding.gamePlay.isEnabled = item.loaderResult == LoaderResult.Success
        binding.gamePlay.setOnClickListener {
            startActivity(Intent(activity, EmulationActivity::class.java).apply {
                putExtras(requireArguments())
            })
        }

        binding.gameSettings.isEnabled = item.loaderResult == LoaderResult.Success
        binding.gameSettings.setOnClickListener {
            startActivity(Intent(activity, SettingsActivity::class.java).apply {
                putExtras(requireArguments())
            })
        }

        val shortcutManager = requireActivity().getSystemService(ShortcutManager::class.java)
        binding.gamePin.isEnabled = shortcutManager.isRequestPinShortcutSupported

        binding.gamePin.setOnClickListener {
            val info = ShortcutInfo.Builder(context, item.title)
            info.setShortLabel(item.title)
            info.setActivity(ComponentName(requireContext(), EmulationActivity::class.java))
            info.setIcon(Icon.createWithAdaptiveBitmap(item.bitmapIcon))

            val intent = Intent(context, EmulationActivity::class.java)
            intent.data = item.uri
            intent.action = Intent.ACTION_VIEW

            info.setIntent(intent)

            shortcutManager.requestPinShortcut(info.build(), null)
        }

        binding.gameTitleId.setOnLongClickListener {
            val clipboard = requireActivity().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Title ID", item.titleId))
            Snackbar.make(binding.root, getString(R.string.copied_to_clipboard), Snackbar.LENGTH_SHORT).show()
            true
        }

        // The title ID is used to build file paths, so the data actions are only offered for a well-formed one
        val dataActionsAvailable = GameDataManager.isValidTitleId(item.titleId)
        dataButtons.forEach { it.isEnabled = dataActionsAvailable }

        binding.gameDeleteShaderCache.setOnClickListener {
            if (ensureNoRunningGame())
                deleteShaderCache()
        }

        binding.gameExportSaves.setOnClickListener {
            if (!ensureNoRunningGame())
                return@setOnClickListener
            if (GameDataManager.hasSaves(requireContext().getPublicFilesDir(), item.titleId!!))
                exportSavesPicker.launch("${item.titleId}_saves.zip")
            else
                showMessage(requireContext().getString(R.string.game_saves_none))
        }

        binding.gameImportSaves.setOnClickListener {
            if (ensureNoRunningGame())
                importSavesPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }

        binding.gameDeleteSaves.setOnClickListener {
            if (!ensureNoRunningGame())
                return@setOnClickListener
            if (GameDataManager.hasSaves(requireContext().getPublicFilesDir(), item.titleId!!))
                confirmDeleteSaves()
            else
                showMessage(requireContext().getString(R.string.game_saves_none))
        }

        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_UP) {
                dialog?.onBackPressed()
                true
            } else {
                false
            }
        }
    }

    /**
     * Game data must not be modified while an emulation session may be reading or writing it
     * @return If no game is running, a message is shown otherwise
     */
    private fun ensureNoRunningGame() : Boolean {
        if (!GameDataManager.isEmulationRunning(requireContext())) return true
        showMessage(requireContext().getString(R.string.game_data_close_game))
        return false
    }

    private fun showMessage(message : String) {
        if (view != null)
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        else
            Toast.makeText(SkylineApplication.instance, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Disables the data buttons and the dismissal of the dialog while a file operation is in flight
     */
    private fun setBusy(busy : Boolean) {
        dataButtons.forEach { it.isEnabled = !busy }
        isCancelable = !busy
    }

    /**
     * Runs [work] off the main thread on an application-wide scope, so it completes even if the dialog goes away, then reports the outcome
     * @param successMessage Builds the message to show from the result of [work]
     * @param onFailure Called on the worker thread if [work] failed, for cleanup
     */
    private fun <T> runOperation(work : () -> T, successMessage : (Context, T) -> String, onFailure : () -> Unit = {}) {
        setBusy(true)
        val appContext = requireContext().applicationContext

        GameDataManager.ioScope.launch {
            val result = runCatching(work)
            result.exceptionOrNull()?.let {
                Log.e("AppDialog", "Game data operation failed", it)
                runCatching(onFailure)
            }

            val message = result.fold({ successMessage(appContext, it) }, { describeFailure(appContext, it) })
            withContext(Dispatchers.Main) {
                if (view != null)
                    setBusy(false)
                showMessage(message)
            }
        }
    }

    private fun describeFailure(context : Context, error : Throwable) = when (error) {
        is GameDataManager.NoSavesException -> context.getString(R.string.game_saves_none)
        is GameDataManager.WrongGameArchiveException -> context.getString(R.string.game_saves_wrong_game)
        else -> context.getString(R.string.game_data_operation_failed, error.localizedMessage ?: error.javaClass.simpleName)
    }

    private fun deleteShaderCache() {
        val filesDir = requireContext().getPublicFilesDir()
        val titleId = item.titleId!!

        runOperation({ GameDataManager.deleteShaderCache(filesDir, titleId) }, { context, freed ->
            if (freed == null) context.getString(R.string.game_shader_cache_empty) else context.getString(R.string.game_shader_cache_deleted, Formatter.formatShortFileSize(context, freed))
        })
    }

    private fun exportSaves(uri : Uri) {
        if (!isAdded) return
        val resolver = requireContext().contentResolver
        val filesDir = requireContext().getPublicFilesDir()
        val titleId = item.titleId!!

        runOperation({
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write to the selected location")
            GameDataManager.exportSaves(filesDir, titleId, output)
        }, { context, _ -> context.getString(R.string.game_saves_exported) }, {
            DocumentsContract.deleteDocument(resolver, uri) // Don't leave a truncated archive behind
        })
    }

    private fun confirmImportSaves(uri : Uri) {
        if (!isAdded) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.game_saves_import_confirm_title)
            .setMessage(R.string.game_saves_import_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.game_replace) { _, _ -> importSaves(uri) }
            .show()
    }

    private fun importSaves(uri : Uri) {
        if (!isAdded || !ensureNoRunningGame()) return
        val resolver = requireContext().contentResolver
        val filesDir = requireContext().getPublicFilesDir()
        val titleId = item.titleId!!

        runOperation({
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read the selected file")
            GameDataManager.importSaves(filesDir, titleId, input)
        }, { context, _ -> context.getString(R.string.game_saves_imported) })
    }

    private fun confirmDeleteSaves() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.game_saves_delete_confirm_title)
            .setMessage(R.string.game_saves_delete_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.game_delete) { _, _ -> deleteSaves() }
            .show()
    }

    private fun deleteSaves() {
        if (!ensureNoRunningGame()) return
        val filesDir = requireContext().getPublicFilesDir()
        val titleId = item.titleId!!

        runOperation({ GameDataManager.deleteSaves(filesDir, titleId) }, { context, _ -> context.getString(R.string.game_saves_deleted) })
    }
}
