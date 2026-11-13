/*
 * SPDX-License-Identifier: MPL-2.0
 */

package emu.skyline

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.snackbar.Snackbar
import emu.skyline.databinding.SetupActivityBinding
import emu.skyline.databinding.SetupStepBinding
import emu.skyline.settings.AppSettings
import emu.skyline.utils.WindowInsetsHelper
import java.io.File

/**
 * Shown on the first launch: takes the game folders (as many as needed), the production keys and the title keys before the game list is shown
 * Every step can be skipped and done later from the settings
 */
class SetupActivity : AppCompatActivity() {
    private val binding by lazy { SetupActivityBinding.inflate(layoutInflater) }
    private val settings by lazy { AppSettings(this) }

    private lateinit var storage : SetupStepBinding
    private lateinit var prodKeys : SetupStepBinding
    private lateinit var titleKeys : SetupStepBinding

    private val keysDirectory get() = File(filesDir, "keys")

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let { uri ->
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settings.searchLocationList = settings.searchLocationList + uri
            settings.refreshRequired = true
            refresh()
        }
    }

    private val prodKeysPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { importKeys(it, KeyReader.KeyType.Prod) }
    private val titleKeysPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { importKeys(it, KeyReader.KeyType.Title) }

    override fun onCreate(savedInstanceState : Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsHelper.applyToActivity(binding.root)

        storage = addStep(R.string.sk_setup_storage, R.string.sk_setup_storage_desc)
        prodKeys = addStep(R.string.sk_prod_keys, R.string.sk_setup_prod_keys_desc)
        titleKeys = addStep(R.string.sk_title_keys, R.string.sk_setup_title_keys_desc)

        storage.stepButton.setText(R.string.sk_add_folder)
        storage.stepButton.setOnClickListener { folderPicker.launch(null) }

        prodKeys.stepButton.setText(R.string.sk_setup_choose)
        prodKeys.stepButton.setOnClickListener { prodKeysPicker.launch(arrayOf("*/*")) }

        titleKeys.stepButton.setText(R.string.sk_setup_choose)
        titleKeys.stepButton.setOnClickListener { titleKeysPicker.launch(arrayOf("*/*")) }

        binding.setupFinish.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }

        refresh()
    }

    /**
     * Leaving the screen in any way (the finish button or back) counts as having done the setup, it's not shown again
     */
    override fun finish() {
        settings.setupCompleted = true
        super.finish()
    }

    private fun addStep(title : Int, description : Int) : SetupStepBinding {
        val step = SetupStepBinding.inflate(layoutInflater, binding.setupSteps, false)
        step.stepTitle.setText(title)
        step.stepDescription.setText(description)
        binding.setupSteps.addView(step.root)
        return step
    }

    private fun importKeys(uri : Uri?, type : KeyReader.KeyType) {
        if (uri == null)
            return

        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings.refreshRequired = true

        val message = when (KeyReader.import(this, uri, type)) {
            KeyReader.ImportResult.Success -> R.string.sk_import_keys_success
            KeyReader.ImportResult.InvalidInputPath -> R.string.sk_import_keys_invalid_input_path
            KeyReader.ImportResult.InvalidKeys -> R.string.sk_import_keys_invalid_keys
            KeyReader.ImportResult.DeletePreviousFailed -> R.string.sk_import_keys_delete_previous_failed
            KeyReader.ImportResult.MoveFailed -> R.string.sk_import_keys_move_failed
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        refresh()
    }

    private fun SetupStepBinding.showStatus(text : String?) {
        stepStatus.text = text
        stepStatus.visibility = if (text != null) View.VISIBLE else View.GONE
    }

    /**
     * Updates what every step shows from the current state
     */
    private fun refresh() {
        val folders = settings.searchLocationList
        storage.showStatus(if (folders.isEmpty()) null else folders.joinToString("\n") { folderName(it) })

        val prodKeysImported = File(keysDirectory, KeyReader.KeyType.Prod.fileName).exists()
        prodKeys.showStatus(if (prodKeysImported) getString(R.string.sk_setup_imported) else null)
        titleKeys.showStatus(if (File(keysDirectory, KeyReader.KeyType.Title.fileName).exists()) getString(R.string.sk_setup_imported) else null)

        binding.setupFinish.setText(if (folders.isNotEmpty() || prodKeysImported) R.string.sk_setup_finish else R.string.sk_setup_skip)
    }

    private fun folderName(uri : Uri) : String = try {
        Uri.decode(DocumentsContract.getTreeDocumentId(uri))
    } catch (e : Exception) {
        Uri.decode(uri.toString())
    }
}
