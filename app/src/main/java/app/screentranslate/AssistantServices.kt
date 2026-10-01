package app.screentranslate

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Registers the app as a "digital assistant", so long-pressing Home (or swiping up from a bottom
 * corner) opens it with a screenshot of whatever is on screen.
 */
class AssistantService : VoiceInteractionService()

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = TranslateSession(this)
}

/**
 * Android requires every assistant app to name a speech recognizer. This app doesn't do speech,
 * so it answers any request with an error straight away instead of leaving the caller waiting.
 */
class NoSpeechRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: Exception) {
            // The caller went away; nothing to do.
        }
    }

    override fun onCancel(listener: RecognitionService.Callback?) {}

    override fun onStopListening(listener: RecognitionService.Callback?) {}
}
