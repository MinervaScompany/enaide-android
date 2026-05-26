package com.enaide.sdk.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Token di colore **semantici** del navigatore, personalizzabili dall'integratore.
 *
 * La UI e la mappa non usano mai hex hardcodati: leggono questi token (o lo
 * `MaterialTheme.colorScheme`). Per ribrandizzare basta passare un altro
 * [EnaideColors] a [EnaideTheme] — niente modifiche al codice dei componenti.
 *
 * I colori "Material" (primary/secondary/...) restano nello `colorScheme` di
 * Material 3; qui aggiungiamo i token specifici della navigazione che Material
 * non copre (linea percorso, puntino posizione, ecc.).
 *
 * @property routeLine colore della linea del percorso.
 * @property routeCasing bordo/ombra sotto la linea del percorso.
 * @property positionDot puntino della posizione utente.
 * @property positionHalo alone attorno al puntino.
 * @property positionStroke bordo del puntino.
 * @property maneuverBanner sfondo del banner manovra (default: primary).
 * @property onManeuverBanner testo/icone sul banner (default: onPrimary).
 * @property offRoute evidenza "fuori percorso".
 */
data class EnaideColors(
    val routeLine: Color,
    val routeCasing: Color,
    val positionDot: Color,
    val positionHalo: Color,
    val positionStroke: Color,
    val maneuverBanner: Color,
    val onManeuverBanner: Color,
    val offRoute: Color,
    // Token aggiuntivi stile navigatore (reference Maps/Waze).
    val roadCard: Color = Color(0xFFFFFFFF),       // scheda bianca col nome strada sotto il banner
    val onRoadCard: Color = Color(0xFF1A1A1A),
    val destinationCard: Color = Color(0xCC4CAF50), // scheda verde semitrasparente destinazione
    val onDestinationCard: Color = Color(0xFFFFFFFF),
    val speedLimitRing: Color = Color(0xFFE53935),  // anello rosso del cartello limite
    val speedLimitText: Color = Color(0xFF1A1A1A),
) {
    /** Converte un [Color] in stringa hex `#RRGGBB` per MapLibre (che vuole stringhe). */
    fun routeLineHex(): String = routeLine.toHex()
    fun routeCasingHex(): String = routeCasing.toHex()
    fun positionDotHex(): String = positionDot.toHex()
    fun positionHaloHex(): String = positionHalo.toHex()
    fun positionStrokeHex(): String = positionStroke.toHex()
}

private fun Color.toHex(): String {
    val argb = toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}

/** Palette di default (stile navigatore moderno, blu). Sovrascrivibile. */
val EnaideDefaultColors: EnaideColors = EnaideColors(
    routeLine = Color(0xFF1E88E5),
    routeCasing = Color(0xFF0D3C78),
    positionDot = Color(0xFF1E88E5),
    positionHalo = Color(0x331E88E5),
    positionStroke = Color(0xFFFFFFFF),
    // Banner manovra scuro come nella reference (non blu).
    maneuverBanner = Color(0xFF2A2A2E),
    onManeuverBanner = Color(0xFFFFFFFF),
    offRoute = Color(0xFFE53935),
)

/** CompositionLocal che espone i token di navigazione all'albero Compose. */
val LocalEnaideColors: ProvidableCompositionLocal<EnaideColors> =
    staticCompositionLocalOf { EnaideDefaultColors }

/** Accesso comodo: `EnaideTheme.colors.routeLine`. */
object EnaideTheme {
    val colors: EnaideColors
        @Composable @ReadOnlyComposable get() = LocalEnaideColors.current
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF00897B),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF64B5F6),
    secondary = Color(0xFF4DB6AC),
)

/** Palette token per la **dark mode**: colori più chiari per contrasto su mappa scura. */
val EnaideDarkColors: EnaideColors = EnaideDefaultColors.copy(
    routeLine = Color(0xFF64B5F6),
    routeCasing = Color(0xFF0D47A1),
    positionDot = Color(0xFF64B5F6),
    positionHalo = Color(0x3364B5F6),
    maneuverBanner = Color(0xFF1E3A5F),
)

/**
 * Theme dell'app: Material 3 + token di navigazione semantici.
 *
 * @param darkTheme se usare lo schema scuro. Quando true e [colors] non è
 *   esplicito, usa automaticamente [EnaideDarkColors].
 * @param colors token navigazione; default in base a [darkTheme].
 */
@Composable
fun EnaideTheme(
    darkTheme: Boolean = false,
    colors: EnaideColors = if (darkTheme) EnaideDarkColors else EnaideDefaultColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalEnaideColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}
