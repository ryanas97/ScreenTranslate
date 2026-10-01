package app.screentranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * What the user sees after long-pressing Home: a frozen copy of the screen with every paragraph
 * replaced by its translation, plus a small control bar at the bottom.
 */
class TranslateSession(context: Context) : VoiceInteractionSession(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = ScreenTranslator()
    private var work: Job? = null
    private var screenshotWait: Job? = null
    private var statusHide: Job? = null

    private var screenshot: Bitmap? = null
    private var blocks: List<ScreenBlock> = emptyList()
    private var showingOriginal = false
    private var bottomInset = 0

    private var viewsReady = false
    private lateinit var imageView: ImageView
    private lateinit var overlay: FrameLayout
    private lateinit var statusPill: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var toggleButton: TextView
    private lateinit var languageButton: TextView
    private lateinit var panelHost: FrameLayout

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        window.window?.let { w ->
            w.setLayout(MATCH_PARENT, MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            makeSystemBarsTransparent(w)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = w.attributes
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                w.attributes = attrs
            }
        }
        // Back closes an open sheet first, then the overlay. The manifest opts out of predictive
        // back, but register the new-style callback too so Back keeps working if that changes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            window.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { onBackPressed() }
        }
    }

    @Suppress("DEPRECATION") // Still needed on Android 14 and older.
    private fun makeSystemBarsTransparent(w: Window) {
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
    }

    override fun onCreateContentView(): View {
        val root = FrameLayout(context)

        imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(imageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        overlay = FrameLayout(context)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val bottomArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        statusPill = buildStatusPill()
        bottomArea.addView(statusPill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        bottomArea.addView(buildControls(), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val bottomParams = FrameLayout.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(24) }
        root.addView(bottomArea, bottomParams)

        panelHost = FrameLayout(context).apply { isVisible = false }
        root.addView(panelHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Keep the controls above the navigation bar (the assistant window covers the whole screen).
        fun applyInsets(insets: WindowInsetsCompat?) {
            bottomInset = insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
            bottomParams.bottomMargin = dp(20) + bottomInset
            bottomArea.layoutParams = bottomParams
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            applyInsets(insets)
            insets
        }
        root.doOnAttach { applyInsets(ViewCompat.getRootWindowInsets(it)) }

        viewsReady = true
        updateToggle()
        updateLanguageButton()
        screenshot?.let { showScreenshot(it) }
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        work?.cancel()
        if (viewsReady) {
            overlay.removeAllViews()
            overlay.isVisible = true
            closePanel()
            showingOriginal = false
            updateToggle()
            updateLanguageButton()
        }
        val shot = screenshot
        if (shot != null) {
            runPipeline(readAgain = true)
        } else {
            setStatus(R.string.status_capturing, busy = true)
            screenshotWait?.cancel()
            screenshotWait = scope.launch {
                delay(SCREENSHOT_TIMEOUT_MS)
                if (screenshot == null) showCaptureError()
            }
        }
    }

    override fun onHandleScreenshot(shot: Bitmap?) {
        super.onHandleScreenshot(shot)
        screenshotWait?.cancel()
        if (shot == null) {
            showCaptureError()
            return
        }
        // Screenshots can arrive as GPU-only bitmaps, which can't be read pixel by pixel.
        val usable = if (shot.config == Bitmap.Config.HARDWARE) {
            shot.copy(Bitmap.Config.ARGB_8888, false) ?: shot
        } else {
            shot
        }
        screenshot = usable
        if (viewsReady) showScreenshot(usable)
    }

    override fun onBackPressed() {
        if (viewsReady && panelHost.isVisible) closePanel() else hide()
    }

    override fun onHide() {
        super.onHide()
        work?.cancel()
        screenshotWait?.cancel()
        statusHide?.cancel()
        if (viewsReady) {
            overlay.removeAllViews()
            closePanel()
            imageView.setImageDrawable(null)
        }
        screenshot = null
        blocks = emptyList()
    }

    override fun onDestroy() {
        scope.cancel()
        engine.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- reading + translating

    private fun showScreenshot(bitmap: Bitmap) {
        closePanel()
        imageView.setImageBitmap(bitmap)
        runPipeline(readAgain = true)
    }

    private fun runPipeline(readAgain: Boolean) {
        val bitmap = screenshot ?: return
        if (!viewsReady) return
        work?.cancel()
        work = scope.launch {
            try {
                if (readAgain) {
                    setStatus(R.string.status_reading, busy = true)
                    val found = engine.readText(bitmap, Prefs.script(context))
                    engine.detectLanguages(found)
                    blocks = found
                }
                translateAll(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(
                    context.getString(R.string.status_error, e.localizedMessage ?: e.javaClass.simpleName),
                    busy = false
                )
            }
        }
    }

    private suspend fun translateAll(bitmap: Bitmap) {
        val target = Prefs.targetLanguage(context)
        updateLanguageButton()
        overlay.removeAllViews()
        overlay.isVisible = true
        showingOriginal = false
        updateToggle()

        if (blocks.isEmpty()) {
            setStatus(R.string.status_no_text, busy = false)
            return
        }
        val todo = blocks.filter { it.sourceLanguage != null && it.sourceLanguage != target }
        if (todo.isEmpty()) {
            setStatus(context.getString(R.string.status_already_in, Languages.displayName(target)), busy = false)
            return
        }

        // First use of a language downloads its pack (~30 MB). After that everything is offline.
        val needed = (todo.mapNotNull { it.sourceLanguage } + target).distinct()
        for (language in needed) {
            if (!engine.isDownloaded(language)) {
                setStatus(
                    context.getString(R.string.status_downloading, Languages.displayName(language)),
                    busy = true
                )
                try {
                    engine.download(language)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setStatus(
                        context.getString(R.string.status_download_failed, Languages.displayName(language)),
                        busy = false
                    )
                    return
                }
            }
        }

        setStatus(R.string.status_translating, busy = true)
        awaitLayout(overlay)
        val scaleX = overlay.width.toFloat() / bitmap.width
        val scaleY = overlay.height.toFloat() / bitmap.height
        var translatedCount = 0
        for (block in todo) {
            val from = block.sourceLanguage ?: continue
            val translated = translateOrNull(block.text, from, target) ?: continue
            block.translation = translated
            addTranslationView(block, translated, bitmap, scaleX, scaleY)
            translatedCount++
        }
        if (translatedCount > 0) {
            setStatus(R.string.status_done, busy = false, autoHide = true)
        } else {
            setStatus(R.string.status_nothing_translated, busy = false)
        }
    }

    private suspend fun translateOrNull(text: String, from: String, to: String): String? = try {
        engine.translate(text, from, to)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun awaitLayout(view: View) {
        if (view.width > 0 && view.height > 0 && !view.isLayoutRequested) return
        suspendCancellableCoroutine<Unit> { cont ->
            view.doOnLayout { if (cont.isActive) cont.resume(Unit) }
        }
    }

    /** Draws a translation over the original paragraph, matching its background colour. */
    private fun addTranslationView(block: ScreenBlock, translated: String, bitmap: Bitmap, sx: Float, sy: Float) {
        val box = block.bounds
        val pad = dp(2)
        val left = (box.left * sx).toInt() - pad
        val top = (box.top * sy).toInt() - pad
        val width = max((box.width() * sx).toInt() + pad * 2, dp(24))
        val height = max((box.height() * sy).toInt() + pad * 2, dp(14))

        val bgColor = sampleBackground(bitmap, box)
        val fgColor = if (ColorUtils.calculateLuminance(bgColor) > 0.5) Color.rgb(32, 33, 36) else Color.WHITE
        val lineHeight = box.height() * sy / block.lineCount
        val minSize = sp(6f).toInt()
        val maxSize = max((lineHeight * 0.8f).toInt(), minSize + 2)

        val view = TextView(context).apply {
            text = translated
            setTextColor(fgColor)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            setPadding(pad * 2, 0, pad * 2, 0)
            background = rounded(bgColor, dp(4).toFloat())
            setAutoSizeTextTypeUniformWithConfiguration(minSize, maxSize, 1, TypedValue.COMPLEX_UNIT_PX)
            setOnClickListener { showDetails(block) }
        }
        overlay.addView(view, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = max(left, 0)
            topMargin = max(top, 0)
        })
    }

    /** Median colour just outside the text's box, so the translation blends into the app. */
    private fun sampleBackground(bitmap: Bitmap, box: Rect): Int = try {
        val m = 3
        val xs = intArrayOf(box.left - m, box.centerX(), box.right + m)
        val ys = intArrayOf(box.top - m, box.centerY(), box.bottom + m)
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        for (x in xs) for (y in ys) {
            if (x == box.centerX() && y == box.centerY()) continue
            val px = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            reds += Color.red(px)
            greens += Color.green(px)
            blues += Color.blue(px)
        }
        reds.sort()
        greens.sort()
        blues.sort()
        Color.rgb(reds[reds.size / 2], greens[greens.size / 2], blues[blues.size / 2])
    } catch (e: Exception) {
        Color.rgb(32, 33, 36)
    }

    // ---------------------------------------------------------------- controls

    private fun buildStatusPill(): LinearLayout {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PANEL_COLOR, dp(20).toFloat())
            elevation = dp(6).toFloat()
            setPadding(dp(14), dp(8), dp(16), dp(8))
        }
        progress = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        pill.addView(progress, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(10) })
        statusText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.75f).toInt()
        }
        pill.addView(statusText)
        return pill
    }

    private fun buildControls(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PANEL_COLOR, dp(28).toFloat())
            elevation = dp(8).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        toggleButton = chip("") { toggleOriginal() }
        languageButton = chip("") { showPicker() }
        val close = chip("✕") { hide() }
        close.contentDescription = context.getString(R.string.close)
        bar.addView(toggleButton)
        bar.addView(languageButton, spaced())
        bar.addView(close, spaced())
        return bar
    }

    private fun toggleOriginal() {
        showingOriginal = !showingOriginal
        overlay.isVisible = !showingOriginal
        updateToggle()
    }

    private fun updateToggle() {
        toggleButton.setText(if (showingOriginal) R.string.show_translation else R.string.show_original)
    }

    private fun updateLanguageButton() {
        languageButton.text = context.getString(
            R.string.language_button, Languages.displayName(Prefs.targetLanguage(context))
        )
    }

    private fun setStatus(@StringRes res: Int, busy: Boolean, autoHide: Boolean = false) =
        setStatus(context.getString(res), busy, autoHide)

    private fun setStatus(message: String, busy: Boolean, autoHide: Boolean = false) {
        if (!viewsReady) return
        statusHide?.cancel()
        statusText.text = message
        progress.isVisible = busy
        statusPill.isVisible = true
        if (autoHide) {
            statusHide = scope.launch {
                delay(4000)
                statusPill.isVisible = false
            }
        }
    }

    // ---------------------------------------------------------------- bottom sheets

    private fun showPicker() {
        val currentTarget = Prefs.targetLanguage(context)
        val currentScript = Prefs.script(context)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        content.addView(sectionTitle(R.string.picker_script_title))
        val scripts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        TextScript.entries.forEachIndexed { index, script ->
            val button = chip(context.getString(script.labelRes), selected = script == currentScript) {
                closePanel()
                if (script != Prefs.script(context)) {
                    Prefs.setScript(context, script)
                    runPipeline(readAgain = true)
                }
            }
            scripts.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        val scriptScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(scripts)
        }
        content.addView(scriptScroller, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        content.addView(sectionTitle(R.string.picker_target_title), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        for (code in Languages.sortedForPicker(currentTarget)) {
            val selected = code == currentTarget
            val row = TextView(context).apply {
                text = Languages.label(code)
                textSize = 16f
                setTextColor(if (selected) ACCENT_COLOR else Color.WHITE)
                if (selected) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener {
                    closePanel()
                    if (code != Prefs.targetLanguage(context)) {
                        Prefs.setTargetLanguage(context, code)
                        runPipeline(readAgain = false)
                    }
                }
            }
            content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        openPanel(content)
    }

    /** Tapping a translation shows the original and the translation, each with a Copy button. */
    private fun showDetails(block: ScreenBlock) {
        val target = Prefs.targetLanguage(context)
        val sourceName = block.sourceLanguage?.let { Languages.displayName(it) }
            ?: context.getString(R.string.original)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(detailSection(sourceName, block.text))
        content.addView(
            detailSection(Languages.displayName(target), block.translation.orEmpty()),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) }
        )
        openPanel(content)
    }

    private fun detailSection(title: String, body: String): View {
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = title
            setTextColor(MUTED_COLOR)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(chip(context.getString(R.string.copy)) { copy(body) })
        section.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        section.addView(TextView(context).apply {
            text = body
            setTextColor(Color.WHITE)
            textSize = 17f
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        return section
    }

    private fun showCaptureError() {
        if (!viewsReady) return
        setStatus(R.string.capture_error_title, busy = false)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(context).apply {
            setText(R.string.capture_error_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(context).apply {
            setText(R.string.capture_error_body)
            setTextColor(Color.rgb(232, 234, 237))
            textSize = 15f
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(chip(context.getString(R.string.close)) { hide() })
        buttons.addView(chip(context.getString(R.string.open_settings), selected = true) {
            AssistantSettings.open(context)
            hide()
        }, spaced())
        content.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        openPanel(content)
    }

    private fun openPanel(content: View) {
        if (!viewsReady) return
        panelHost.removeAllViews()
        val scrim = View(context).apply {
            setBackgroundColor(Color.argb(128, 0, 0, 0))
            setOnClickListener { closePanel() }
        }
        panelHost.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
                )
            }
        }
        scroll.addView(content)

        val sheet = FrameLayout(context).apply {
            val r = dp(20).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.rgb(32, 33, 36))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(18), dp(20), dp(18) + bottomInset)
            isClickable = true
        }
        sheet.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panelHost.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        panelHost.isVisible = true
    }

    private fun closePanel() {
        if (!viewsReady) return
        panelHost.removeAllViews()
        panelHost.isVisible = false
    }

    // ---------------------------------------------------------------- small helpers

    private fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        // Android 13+ shows its own "Copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun chip(label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(if (selected) Color.rgb(32, 33, 36) else Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(40)
            minWidth = dp(40)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(if (selected) ACCENT_COLOR else CHIP_COLOR, dp(20).toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun sectionTitle(@StringRes res: Int): TextView = TextView(context).apply {
        setText(res)
        setTextColor(MUTED_COLOR)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun spaced() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private companion object {
        const val SCREENSHOT_TIMEOUT_MS = 3000L
        val PANEL_COLOR = Color.argb(240, 32, 33, 36)
        val CHIP_COLOR = Color.argb(60, 255, 255, 255)
        val ACCENT_COLOR = Color.rgb(138, 180, 248)
        val MUTED_COLOR = Color.rgb(154, 160, 166)
    }
}
