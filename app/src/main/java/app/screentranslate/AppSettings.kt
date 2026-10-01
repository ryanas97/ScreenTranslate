package app.screentranslate

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Which alphabet the on-screen text uses. ML Kit needs a different text reader for each. */
enum class TextScript(val key: String, @StringRes val labelRes: Int) {
    LATIN("latin", R.string.script_latin),
    CHINESE("chinese", R.string.script_chinese),
    JAPANESE("japanese", R.string.script_japanese),
    KOREAN("korean", R.string.script_korean);

    companion object {
        fun fromKey(key: String?): TextScript = entries.firstOrNull { it.key == key } ?: LATIN
    }
}

/** The two things the user can choose: the language to translate into, and the script to read. */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_TARGET = "target_language"
    private const val KEY_SCRIPT = "text_script"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun targetLanguage(context: Context): String {
        val saved = prefs(context).getString(KEY_TARGET, null)
        if (saved != null && saved in Languages.all) return saved
        return Languages.deviceDefault()
    }

    fun setTargetLanguage(context: Context, code: String) {
        prefs(context).edit().putString(KEY_TARGET, code).apply()
    }

    fun script(context: Context): TextScript =
        TextScript.fromKey(prefs(context).getString(KEY_SCRIPT, null))

    fun setScript(context: Context, script: TextScript) {
        prefs(context).edit().putString(KEY_SCRIPT, script.key).apply()
    }
}

/** Language names and code conversions. Codes are ML Kit translate codes such as "id", "en", "zh". */
object Languages {
    val all: List<String> get() = TranslateLanguage.getAllLanguages()

    /** The phone's own language if ML Kit can translate into it, otherwise English. */
    fun deviceDefault(): String =
        TranslateLanguage.fromLanguageTag(normalize(Locale.getDefault().language)) ?: TranslateLanguage.ENGLISH

    fun displayName(code: String): String {
        if (code.isBlank()) return ""
        val name = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
        return if (name.isBlank() || name == code) code.uppercase(Locale.ROOT) else capitalize(name, Locale.getDefault())
    }

    /** "Japanese · 日本語" style label for pickers. */
    fun label(code: String): String {
        val name = displayName(code)
        val locale = Locale.forLanguageTag(code)
        val native = capitalize(locale.getDisplayName(locale), locale)
        return if (native.isBlank() || native == code || native.equals(name, ignoreCase = true)) name else "$name · $native"
    }

    /** Current choice, phone language, Indonesian and English first; everything else A–Z. */
    fun sortedForPicker(current: String): List<String> {
        val supported = all
        val pinned = linkedSetOf(current, deviceDefault(), TranslateLanguage.INDONESIAN, TranslateLanguage.ENGLISH)
            .filter { it in supported }
        val rest = supported.filter { it !in pinned }
            .sortedBy { displayName(it).lowercase(Locale.getDefault()) }
        return pinned + rest
    }

    /** Converts a language-identification result (BCP-47, e.g. "zh", "ja-Latn", "und") to a translate code. */
    fun fromDetected(tag: String?): String? {
        if (tag.isNullOrBlank() || tag == "und" || tag.endsWith("-Latn")) return null
        return TranslateLanguage.fromLanguageTag(normalize(tag.substringBefore('-')))
    }

    private fun normalize(language: String): String = when (language) {
        "in" -> "id"   // older Android reports Indonesian as "in"
        "iw" -> "he"
        "ji" -> "yi"
        "fil" -> "tl"
        else -> language
    }

    private fun capitalize(text: String, locale: Locale): String =
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** Checks whether this app is the phone's digital assistant, and opens the page where that's chosen. */
object AssistantSettings {
    fun isActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            }
        }
        return try {
            val current = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            current?.startsWith(context.packageName + "/") == true
        } catch (e: Exception) {
            false
        }
    }

    fun open(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next, more general settings page.
            } catch (e: SecurityException) {
                // Some phones protect a page; fall back to a more general one.
            }
        }
    }
}
