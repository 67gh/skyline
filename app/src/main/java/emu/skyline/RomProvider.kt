package emu.skyline

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import emu.skyline.loader.AppEntry
import emu.skyline.loader.RomFile
import emu.skyline.loader.RomFormat
import emu.skyline.loader.RomFormat.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RomProvider @Inject constructor(@ApplicationContext private val context : Context) {
    /**
     * This adds all files in [directory] with [extension] as an entry using [RomFile] to load metadata
     */
    @SuppressLint("DefaultLocale")
    private fun addEntries(fileFormats : Map<String, RomFormat>, directory : DocumentFile, entries : HashMap<RomFormat, ArrayList<AppEntry>>, systemLanguage : Int) {
        directory.listFiles().forEach { file ->
            if (file.isDirectory) {
                addEntries(fileFormats, file, entries, systemLanguage)
            } else {
                fileFormats[file.name?.substringAfterLast(".")?.lowercase()]?.let { romFormat->
                    entries.getOrPut(romFormat, { arrayListOf() }).add(RomFile(context, romFormat, file.uri, systemLanguage).appEntry)
                }
            }
        }
    }

    fun loadRoms(searchLocation : Uri, systemLanguage : Int) = DocumentFile.fromTreeUri(context, searchLocation)!!.let { documentFile ->
        hashMapOf<RomFormat, ArrayList<AppEntry>>().apply {
            addEntries(mapOf("nro" to NRO, "nso" to NSO, "nca" to NCA, "nsp" to NSP, "xci" to XCI), documentFile, this, systemLanguage)
        }
    }

    /**
     * Loads the games of every given folder, a game found in more than one folder (nested folders) is only listed once
     * A folder that can't be read anymore (permission revoked, folder deleted) is skipped so the others still load
     */
    fun loadRoms(searchLocations : List<Uri>, systemLanguage : Int) : HashMap<RomFormat, ArrayList<AppEntry>> = hashMapOf<RomFormat, ArrayList<AppEntry>>().also { merged ->
        searchLocations.forEach { location ->
            try {
                loadRoms(location, systemLanguage).forEach { (format, entries) -> merged.getOrPut(format) { arrayListOf() }.addAll(entries) }
            } catch (e : Exception) {
                Log.w("RomProvider", "Skipping game folder '$location': ${e.message}")
            }
        }
        merged.keys.toList().forEach { format -> merged[format] = ArrayList(merged[format]!!.distinctBy { it.uri }) }
    }
}
