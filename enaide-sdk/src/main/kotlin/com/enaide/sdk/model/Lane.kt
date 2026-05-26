package com.enaide.sdk.model

/**
 * Indicazione di **corsia** a una manovra (lane guidance), come nei veri
 * navigatori: mostra quali direzioni offre ogni corsia e quali sono valide per
 * seguire il percorso.
 *
 * Deriva dal campo `lanes` di Valhalla (richiede `turn_lanes:true`). Le direzioni
 * sono un set di [LaneDirection] decodificate dal bitmask Valhalla.
 *
 * @property directions tutte le direzioni che la corsia consente.
 * @property valid true se la corsia permette di proseguire sul percorso.
 * @property active true se è la corsia consigliata (proseguire senza cambi).
 */
public data class Lane(
    public val directions: Set<LaneDirection>,
    public val valid: Boolean,
    public val active: Boolean,
)

/** Direzione possibile di una corsia. Allineata al bitmask Valhalla. */
public enum class LaneDirection(internal val bit: Int) {
    NONE(0),
    THROUGH(1),
    SHARP_LEFT(2),
    LEFT(4),
    SLIGHT_LEFT(8),
    SLIGHT_RIGHT(16),
    RIGHT(32),
    SHARP_RIGHT(64),
    REVERSE(128),
    MERGE_TO_LEFT(256),
    MERGE_TO_RIGHT(512);

    public companion object {
        /** Decodifica il bitmask Valhalla `directions` nelle direzioni corrispondenti. */
        public fun fromBitmask(mask: Int): Set<LaneDirection> =
            entries.filter { it.bit != 0 && (mask and it.bit) != 0 }.toSet()
    }
}
