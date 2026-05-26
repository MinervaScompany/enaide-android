package com.enaide.sdk.model

import kotlinx.serialization.Serializable

/**
 * Piano di viaggio: la lista **ordinata** dei waypoint da cui si calcola un
 * percorso — origine, eventuali tappe intermedie, destinazione.
 *
 * Immutabile: ogni operazione di modifica ritorna una **nuova** istanza, così lo
 * stato è prevedibile e diff-abile (utile per ricalcolare solo quando cambia
 * davvero). È il modello che la UI di pianificazione costruisce/modifica e che si
 * traduce nei `waypoints` di `computeRoute(...)`.
 *
 * Speculare in iOS come `struct TripPlan` con metodi che ritornano copie.
 *
 * @property stops i punti del viaggio, in ordine. Il primo è l'origine, l'ultimo
 *   la destinazione; quelli in mezzo sono tappe. Serve almeno 1 elemento per
 *   costruirlo, ma ne servono ≥2 perché [isRoutable].
 */
@Serializable
public data class TripPlan(
    public val stops: List<TripStop> = emptyList(),
) {
    /** Coordinate ordinate, pronte per `computeRoute(...)`. */
    public val waypoints: List<GeoPoint> get() = stops.map { it.point }

    /** Origine (primo stop), o null se vuoto. */
    public val origin: TripStop? get() = stops.firstOrNull()

    /** Destinazione (ultimo stop), o null se vuoto. */
    public val destination: TripStop? get() = stops.lastOrNull()

    /** Tappe intermedie (tutto tranne origine e destinazione). */
    public val intermediateStops: List<TripStop>
        get() = if (stops.size <= 2) emptyList() else stops.subList(1, stops.size - 1)

    /** True se ha almeno origine + destinazione: calcolabile. */
    public val isRoutable: Boolean get() = stops.size >= 2

    /** Imposta/sostituisce l'origine (primo stop). */
    public fun withOrigin(stop: TripStop): TripPlan =
        if (stops.isEmpty()) copy(stops = listOf(stop))
        else copy(stops = listOf(stop) + stops.drop(1))

    /** Imposta/sostituisce la destinazione (ultimo stop). */
    public fun withDestination(stop: TripStop): TripPlan = when {
        stops.isEmpty() -> copy(stops = listOf(stop))
        stops.size == 1 -> copy(stops = stops + stop)
        else -> copy(stops = stops.dropLast(1) + stop)
    }

    /**
     * Inserisce una tappa. Di default prima della destinazione; con [index]
     * espliciti la posizione (clampata fra 1 e size-1 per non spostare origine).
     */
    public fun addStop(stop: TripStop, index: Int? = null): TripPlan {
        if (stops.size < 2) return copy(stops = stops + stop)
        val at = (index ?: (stops.size - 1)).coerceIn(1, stops.size - 1)
        return copy(stops = stops.toMutableList().apply { add(at, stop) })
    }

    /** Rimuove lo stop all'indice [index] (no-op se fuori range). */
    public fun removeStop(index: Int): TripPlan {
        if (index !in stops.indices) return this
        return copy(stops = stops.toMutableList().apply { removeAt(index) })
    }

    /** Sposta uno stop da [from] a [to] (riordino tappe). */
    public fun moveStop(from: Int, to: Int): TripPlan {
        if (from !in stops.indices || to !in stops.indices || from == to) return this
        return copy(stops = stops.toMutableList().apply { add(to, removeAt(from)) })
    }

    public companion object {
        /** Crea un piano semplice origine → destinazione. */
        public fun of(origin: TripStop, destination: TripStop): TripPlan =
            TripPlan(listOf(origin, destination))
    }
}

/**
 * Una tappa del [TripPlan]: una coordinata con un'etichetta leggibile.
 *
 * @property point coordinate WGS84.
 * @property label nome mostrato all'utente (indirizzo, nome POI, "la mia posizione").
 */
@Serializable
public data class TripStop(
    public val point: GeoPoint,
    public val label: String,
)
