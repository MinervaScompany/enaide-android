package com.enaide.sdk.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Riproduce vocalmente le istruzioni di navigazione usando il [TextToSpeech] di
 * Android.
 *
 * L'SDK enaide emette una [com.enaide.sdk.model.SpokenInstruction] in
 * `NavigationState.Navigating.pendingSpokenInstruction` quando si supera la
 * soglia di trigger di una manovra. La stessa istruzione puo' ripresentarsi su
 * fix consecutivi: qui de-duplichiamo per testo cosi' la voce non balbetta.
 *
 * Ciclo di vita: crea l'istanza nell'`onCreate` dell'Activity e chiama [shutdown]
 * in `onDestroy`. Il motore TTS si inizializza in modo asincrono; finche' non e'
 * pronto le richieste vengono accodate e pronunciate appena disponibile.
 */
public class VoiceGuidance(context: Context, private val locale: Locale = Locale.ITALIAN) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var lastSpoken: String? = null

    /** Istruzione in attesa se arriva prima che il motore sia pronto. */
    private var pending: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val res = engine.setLanguage(locale)
                // Se la lingua non e' disponibile, ripieghiamo sul default del device.
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.getDefault()
                }
                ready = true
                pending?.let { speak(it) }
                pending = null
            }
        }
    }

    /**
     * Pronuncia [text] saltandolo se identico all'ultima frase detta. Se il motore
     * non e' ancora pronto, tiene da parte l'ultima richiesta e la dice appena puo'.
     */
    public fun speak(text: String) {
        if (text.isBlank() || text == lastSpoken) return
        lastSpoken = text
        val engine = tts
        if (!ready || engine == null) {
            pending = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "enaide-nav")
    }

    /** Azzera lo storico (es. all'avvio di un nuovo viaggio) cosi' la prima frase parte sempre. */
    public fun reset() {
        lastSpoken = null
        pending = null
        tts?.stop()
    }

    public fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
