package com.enaide.sdk.model

/**
 * Tipo di manovra richiesta dal navigatore.
 *
 * Allineato al set di [ManeuverType] del formato OSRM/Mapbox/Valhalla, normalizzato
 * in un'unica enum stabile. I valori `null`-able lato Valhalla vengono mappati su [TURN].
 */
public enum class ManeuverType {
    DEPART,
    ARRIVE,
    TURN,
    CONTINUE,
    MERGE,
    ON_RAMP,
    OFF_RAMP,
    FORK,
    END_OF_ROAD,
    ROUNDABOUT,
    ROUNDABOUT_EXIT,
    NEW_NAME,
    NOTIFICATION,
    UTURN,
}

/**
 * Modificatore direzionale della manovra (sinistra/destra, leggero/secco, ...).
 */
public enum class ManeuverModifier {
    LEFT,
    SLIGHT_LEFT,
    SHARP_LEFT,
    RIGHT,
    SLIGHT_RIGHT,
    SHARP_RIGHT,
    STRAIGHT,
    UTURN,
    NONE,
}

/**
 * Singola manovra all'interno di uno [RouteStep].
 *
 * @property type tipo di manovra.
 * @property modifier modificatore direzionale.
 * @property at punto geografico in cui la manovra avviene (junction point).
 * @property roundaboutExit numero dell'uscita per rotonda, `null` se non pertinente.
 * @property bearingBefore bearing del moto in entrata alla manovra (gradi, 0 = nord).
 * @property bearingAfter bearing del moto in uscita dalla manovra.
 */
public data class Maneuver(
    public val type: ManeuverType,
    public val modifier: ManeuverModifier,
    public val at: GeoPoint,
    public val roundaboutExit: Int? = null,
    public val bearingBefore: Double? = null,
    public val bearingAfter: Double? = null,
)
