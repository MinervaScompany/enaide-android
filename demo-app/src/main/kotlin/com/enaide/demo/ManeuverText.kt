package com.enaide.demo

import android.content.Context
import com.enaide.sdk.model.Maneuver
import com.enaide.sdk.model.ManeuverModifier
import com.enaide.sdk.model.ManeuverType

/**
 * Resa lato-UI di una [Maneuver]: glifo direzionale + testo **localizzato**.
 *
 * L'SDK espone tipi/modificatori grezzi; la resa testuale è presentazione, quindi
 * vive nell'app e legge da `strings.xml` (IT/EN…). È un *fallback*: di norma il
 * testo turn-by-turn arriva già localizzato da Valhalla.
 */
internal object ManeuverText {

    /** Glifo a freccia (simboli Unicode, indipendenti dalla lingua). */
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

    /** Frase imperativa breve, localizzata, opzionalmente legata al [roadName]. */
    fun phrase(ctx: Context, maneuver: Maneuver, roadName: String?): String {
        val onto = roadName?.takeIf { it.isNotBlank() }
            ?.let { ctx.getString(R.string.mnv_onto, it) } ?: ""
        return when (maneuver.type) {
            ManeuverType.DEPART -> ctx.getString(R.string.mnv_depart, onto)
            ManeuverType.ARRIVE -> ctx.getString(R.string.mnv_arrive)
            ManeuverType.CONTINUE, ManeuverType.NEW_NAME -> ctx.getString(R.string.mnv_continue, onto)
            ManeuverType.MERGE -> ctx.getString(R.string.mnv_merge, onto)
            ManeuverType.ON_RAMP -> ctx.getString(R.string.mnv_on_ramp, onto)
            ManeuverType.OFF_RAMP -> ctx.getString(R.string.mnv_off_ramp, onto)
            ManeuverType.FORK -> ctx.getString(R.string.mnv_fork, side(ctx, maneuver.modifier), onto)
            ManeuverType.END_OF_ROAD -> ctx.getString(R.string.mnv_end_of_road, side(ctx, maneuver.modifier), onto)
            ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_EXIT -> {
                val exit = maneuver.roundaboutExit
                if (exit != null) ctx.getString(R.string.mnv_roundabout_exit, exit, onto)
                else ctx.getString(R.string.mnv_roundabout, onto)
            }
            ManeuverType.UTURN -> ctx.getString(R.string.mnv_uturn)
            ManeuverType.TURN, ManeuverType.NOTIFICATION -> ctx.getString(R.string.mnv_turn, side(ctx, maneuver.modifier), onto)
        }
    }

    private fun side(ctx: Context, modifier: ManeuverModifier): String = ctx.getString(when (modifier) {
        ManeuverModifier.LEFT, ManeuverModifier.SHARP_LEFT -> R.string.dir_left
        ManeuverModifier.SLIGHT_LEFT -> R.string.dir_slight_left
        ManeuverModifier.RIGHT, ManeuverModifier.SHARP_RIGHT -> R.string.dir_right
        ManeuverModifier.SLIGHT_RIGHT -> R.string.dir_slight_right
        ManeuverModifier.UTURN -> R.string.dir_uturn
        ManeuverModifier.STRAIGHT, ManeuverModifier.NONE -> R.string.dir_straight
    })
}
