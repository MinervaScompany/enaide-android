package com.enaide.demo

import com.enaide.sdk.model.Maneuver
import com.enaide.sdk.model.ManeuverModifier
import com.enaide.sdk.model.ManeuverType

/**
 * Traduzione lato-UI di una [Maneuver] in glifo direzionale + testo italiano.
 *
 * L'SDK espone tipi/modificatori grezzi e normalizzati (allineati a OSRM/Valhalla);
 * la resa testuale e' una scelta di presentazione, quindi vive nell'app, non nella
 * libreria. Cosi' un altro integratore puo' localizzare o sostituire le icone.
 */
internal object ManeuverText {

    /** Glifo a freccia per il banner. Usa caratteri Unicode per non dipendere da asset. */
    fun glyph(maneuver: Maneuver): String = when (maneuver.type) {
        ManeuverType.DEPART -> "●"
        ManeuverType.ARRIVE -> "⚑"
        ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_EXIT -> "↻"
        ManeuverType.UTURN -> "⮌"
        ManeuverType.MERGE -> "⤳"
        ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP -> "⤴"
        ManeuverType.FORK -> "⋔"
        else -> glyphForModifier(maneuver.modifier)
    }

    private fun glyphForModifier(modifier: ManeuverModifier): String = when (modifier) {
        ManeuverModifier.LEFT -> "←"
        ManeuverModifier.SLIGHT_LEFT -> "↖"
        ManeuverModifier.SHARP_LEFT -> "↰"
        ManeuverModifier.RIGHT -> "→"
        ManeuverModifier.SLIGHT_RIGHT -> "↗"
        ManeuverModifier.SHARP_RIGHT -> "↱"
        ManeuverModifier.UTURN -> "⮌"
        ManeuverModifier.STRAIGHT, ManeuverModifier.NONE -> "↑"
    }

    /**
     * Frase imperativa breve per la manovra, opzionalmente legata al [roadName]
     * (la strada su cui si entra dopo la manovra).
     */
    fun phrase(maneuver: Maneuver, roadName: String?): String {
        val onto = roadName?.takeIf { it.isNotBlank() }?.let { " su $it" } ?: ""
        return when (maneuver.type) {
            ManeuverType.DEPART -> "Parti${onto}"
            ManeuverType.ARRIVE -> "Sei arrivato a destinazione"
            ManeuverType.CONTINUE, ManeuverType.NEW_NAME -> "Continua dritto${onto}"
            ManeuverType.MERGE -> "Immettiti${onto}"
            ManeuverType.ON_RAMP -> "Prendi la rampa${onto}"
            ManeuverType.OFF_RAMP -> "Esci${onto}"
            ManeuverType.FORK -> "Al bivio tieni la ${sideOrStraight(maneuver.modifier)}${onto}"
            ManeuverType.END_OF_ROAD -> "Alla fine della strada svolta a ${sideOrStraight(maneuver.modifier)}${onto}"
            ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_EXIT -> {
                val exit = maneuver.roundaboutExit
                if (exit != null) "Alla rotonda prendi la ${ordinal(exit)} uscita${onto}"
                else "Imbocca la rotonda${onto}"
            }
            ManeuverType.UTURN -> "Fai inversione a U"
            ManeuverType.TURN, ManeuverType.NOTIFICATION -> "Svolta a ${sideOrStraight(maneuver.modifier)}${onto}"
        }
    }

    private fun sideOrStraight(modifier: ManeuverModifier): String = when (modifier) {
        ManeuverModifier.LEFT, ManeuverModifier.SHARP_LEFT -> "sinistra"
        ManeuverModifier.SLIGHT_LEFT -> "sinistra (leggermente)"
        ManeuverModifier.RIGHT, ManeuverModifier.SHARP_RIGHT -> "destra"
        ManeuverModifier.SLIGHT_RIGHT -> "destra (leggermente)"
        ManeuverModifier.UTURN -> "inversione"
        ManeuverModifier.STRAIGHT, ManeuverModifier.NONE -> "dritto"
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "1ª"; 2 -> "2ª"; 3 -> "3ª"; 4 -> "4ª"; 5 -> "5ª"
        else -> "${n}ª"
    }
}
