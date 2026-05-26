package com.enaide.sdk.model

/**
 * Istruzione visuale mostrata in alto allo schermo durante la guida.
 *
 * Tipicamente composta da una linea primaria ("Via Roma") e una secondaria
 * opzionale ("verso Milano"). Le distanze sono espresse in metri rispetto
 * alla manovra associata.
 */
public data class VisualInstruction(
    public val primary: String,
    public val secondary: String? = null,
    public val triggerDistanceBeforeManeuverMeters: Double,
)

/**
 * Istruzione parlata da pronunciare al passare di una soglia di distanza.
 *
 * @property text testo "piano" pronunciabile da un TTS standard.
 * @property ssml versione SSML opzionale per TTS che la supportano (controllo pause, enfasi).
 * @property triggerDistanceBeforeManeuverMeters distanza rimasta alla manovra alla quale far partire la voce.
 */
public data class SpokenInstruction(
    public val text: String,
    public val ssml: String? = null,
    public val triggerDistanceBeforeManeuverMeters: Double,
)
