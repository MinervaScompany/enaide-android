package com.enaide.sdk.format

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Formattatori per distanze, durate ed ETA in formato user-facing.
 *
 * Tutti i metodi accettano un [Locale] esplicito così l'output è coerente con
 * la lingua dell'app indipendentemente dal locale di sistema. Default `it-IT`.
 *
 * Convenzioni:
 *  - Distanze sotto 1km: arrotondate a 10m (es. "120 m", "850 m").
 *  - Distanze 1-10km: 1 decimale ("1,2 km").
 *  - Distanze >10km: numero intero ("12 km").
 *  - Durate <60s: "Adesso".
 *  - Durate 1-59 min: "12 min".
 *  - Durate ≥1h: "1 h 30 min".
 */
public object UnitFormatter {

    private val italian: Locale = Locale.forLanguageTag("it-IT")

    /**
     * Formatta una distanza in metri come stringa user-facing.
     */
    public fun formatDistance(meters: Double, locale: Locale = italian): String {
        return when {
            meters < 1000 -> {
                val rounded = (round(meters / 10.0) * 10.0).toInt().coerceAtLeast(0)
                "$rounded m"
            }
            meters < 10_000 -> {
                val km = meters / 1000.0
                val nf = NumberFormat.getInstance(locale).apply {
                    minimumFractionDigits = 1
                    maximumFractionDigits = 1
                }
                "${nf.format(km)} km"
            }
            else -> {
                val km = (meters / 1000.0).roundToInt()
                "$km km"
            }
        }
    }

    /**
     * Formatta una durata in secondi come stringa user-facing.
     */
    public fun formatDuration(seconds: Double): String {
        val s = seconds.toInt().coerceAtLeast(0)
        if (s < 60) return "Adesso"
        val minutes = s / 60
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val remMinutes = minutes % 60
        return if (remMinutes == 0) "$hours h" else "$hours h $remMinutes min"
    }

    /**
     * Calcola e formatta un orario d'arrivo aggiungendo [durationSeconds] al [nowEpochMillis].
     *
     * Esempio: "Arrivo alle 14:35".
     */
    public fun formatEta(
        durationSeconds: Double,
        nowEpochMillis: Long = System.currentTimeMillis(),
        locale: Locale = italian,
    ): String {
        val etaMillis = nowEpochMillis + (durationSeconds * 1000L).toLong()
        val formatter = java.text.SimpleDateFormat("HH:mm", locale)
        return "Arrivo alle ${formatter.format(java.util.Date(etaMillis))}"
    }

    /**
     * Velocità m/s → "km/h" arrotondato.
     */
    public fun formatSpeedKmh(metersPerSecond: Double): String {
        val kmh = (metersPerSecond * 3.6).roundToInt().coerceAtLeast(0)
        return "$kmh km/h"
    }
}
