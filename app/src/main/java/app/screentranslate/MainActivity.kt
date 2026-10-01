package app.screentranslate

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Setup screen: make the app the assistant, pick languages, optionally pre-download a language pack. */
class MainActivity : AppCompatActivity() {

    private lateinit var assistantStatus: TextView
    private lateinit var targetButton: Button
    private lateinit var scriptButton: Button
    private lateinit var downloadButton: Button
    private lateinit var packsText: TextView
    private val models by lazy { RemoteModelManager.getInstance() }
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        assistantStatus = findViewById(R.id.assistant_status)
        targetButton = findViewById(R.id.target_language)
        scriptButton = findViewById(R.id.text_script)
        downloadButton = findViewById(R.id.download_pack)
        packsText = findViewById(R.id.packs_on_phone)

        findViewById<Button>(R.id.open_assistant_settings).setOnClickListener { AssistantSettings.open(this) }
        targetButton.setOnClickListener { pickTargetLanguage() }
        scriptButton.setOnClickListener { pickScript() }
        downloadButton.setOnClickListener { downloadTargetPack() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val active = AssistantSettings.isActive(this)
        assistantStatus.setText(if (active) R.string.assistant_active else R.string.assistant_inactive)
        assistantStatus.setTextColor(if (active) Color.rgb(30, 142, 62) else Color.rgb(217, 48, 37))
        targetButton.text = Languages.label(Prefs.targetLanguage(this))
        scriptButton.setText(Prefs.script(this).labelRes)
        refreshPacks()
    }

    private fun refreshPacks() {
        lifecycleScope.launch {
            val onPhone = try {
                models.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val target = Prefs.targetLanguage(this@MainActivity)
            val targetName = Languages.displayName(target)
            packsText.text = if (onPhone.isEmpty()) {
                getString(R.string.no_packs)
            } else {
                getString(R.string.packs_on_phone, onPhone.map { Languages.displayName(it) }.sorted().joinToString(", "))
            }
            if (!downloading) {
                val ready = target in onPhone
                downloadButton.isEnabled = !ready
                downloadButton.text = getString(if (ready) R.string.pack_ready else R.string.download_pack, targetName)
            }
        }
    }

    private fun downloadTargetPack() {
        val target = Prefs.targetLanguage(this)
        downloading = true
        downloadButton.isEnabled = false
        downloadButton.text = getString(R.string.downloading_pack, Languages.displayName(target))
        lifecycleScope.launch {
            try {
                models.download(TranslateRemoteModel.Builder(target).build(), DownloadConditions.Builder().build()).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.download_failed, Toast.LENGTH_LONG).show()
            }
            downloading = false
            refreshPacks()
        }
    }

    private fun pickTargetLanguage() {
        val current = Prefs.targetLanguage(this)
        val codes = Languages.sortedForPicker(current)
        val labels = codes.map { Languages.label(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translate_to)
            .setSingleChoiceItems(labels, codes.indexOf(current)) { dialog, which ->
                Prefs.setTargetLanguage(this, codes[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickScript() {
        val scripts = TextScript.entries
        val labels = scripts.map { getString(it.labelRes) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_script_label)
            .setSingleChoiceItems(labels, scripts.indexOf(Prefs.script(this))) { dialog, which ->
                Prefs.setScript(this, scripts[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
