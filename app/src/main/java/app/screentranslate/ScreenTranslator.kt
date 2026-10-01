package app.screentranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** One paragraph of text found on the screen. [bounds] is in screenshot pixels. */
class ScreenBlock(val bounds: Rect, val lineCount: Int, val text: String) {
    /** Language to translate from, or null if the block shouldn't be translated (numbers, symbols). */
    var sourceLanguage: String? = null
    var translation: String? = null
}

/**
 * Reads text from a screenshot and translates it. Everything runs on the phone with ML Kit;
 * the internet is only used the first time a language pack is needed.
 */
class ScreenTranslator : AutoCloseable {
    private val recognizers = HashMap<TextScript, TextRecognizer>()
    private val translators = HashMap<String, Translator>()
    private val languageId: LanguageIdentifier = LanguageIdentification.getClient()
    private val models = RemoteModelManager.getInstance()
    private val anyNetwork = DownloadConditions.Builder().build()

    suspend fun readText(bitmap: Bitmap, script: TextScript): List<ScreenBlock> {
        val recognizer = recognizers.getOrPut(script) { createRecognizer(script) }
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val text = joinLines(block.lines.map { it.text })
            if (text.isBlank()) null else ScreenBlock(Rect(box), block.lines.size.coerceAtLeast(1), text)
        }
    }

    /**
     * Works out which language each block is in. The screen's main language is used for short
     * blocks (buttons, labels) because detection on a word or two is unreliable.
     */
    suspend fun detectLanguages(blocks: List<ScreenBlock>) {
        if (blocks.isEmpty()) return
        val sample = blocks.joinToString("\n") { it.text }.take(4000)
        val main = Languages.fromDetected(languageId.identifyLanguage(sample).await())
            ?: TranslateLanguage.ENGLISH
        for (block in blocks) {
            if (block.text.none { it.isLetter() }) {
                block.sourceLanguage = null
                continue
            }
            var own: String? = null
            if (block.text.length >= 20) {
                val best = languageId.identifyPossibleLanguages(block.text).await()
                    .maxByOrNull { it.confidence }
                if (best != null && best.confidence >= 0.7f) own = Languages.fromDetected(best.languageTag)
            }
            block.sourceLanguage = own ?: main
        }
    }

    suspend fun isDownloaded(language: String): Boolean =
        models.isModelDownloaded(TranslateRemoteModel.Builder(language).build()).await()

    suspend fun download(language: String) {
        models.download(TranslateRemoteModel.Builder(language).build(), anyNetwork).await()
    }

    suspend fun translate(text: String, from: String, to: String): String {
        val translator = translators.getOrPut("$from>$to") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
            )
        }
        translator.downloadModelIfNeeded(anyNetwork).await()
        return translator.translate(text).await()
    }

    override fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
        translators.values.forEach { it.close() }
        translators.clear()
        languageId.close()
    }

    private fun createRecognizer(script: TextScript): TextRecognizer = when (script) {
        TextScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        TextScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        TextScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        TextScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /**
     * Joins the lines of a paragraph so the translator sees whole sentences instead of fragments.
     * Chinese and Japanese are joined without spaces; words split with a hyphen are rejoined.
     */
    private fun joinLines(lines: List<String>): String {
        val out = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (out.isNotEmpty()) {
                val prev = out[out.length - 1]
                when {
                    prev == '-' && out.length > 1 && out[out.length - 2].isLetter() && line[0].isLowerCase() ->
                        out.setLength(out.length - 1)
                    isCjk(prev) || isCjk(line[0]) -> Unit
                    else -> out.append(' ')
                }
            }
            out.append(line)
        }
        return out.toString()
    }

    private fun isCjk(c: Char): Boolean {
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HAN ||
            script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA
    }
}
