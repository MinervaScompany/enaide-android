package com.enaide.demo

import android.content.Context
import com.enaide.sdk.model.TripPlan
import com.enaide.sdk.vehicle.VehicleKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistenza locale del viaggio attivo, per **recuperare la navigazione** dopo
 * un kill di processo o un crash.
 *
 * Salva solo il piano (waypoint + label) e il mezzo: al ripristino il percorso si
 * **ricalcola** (chiamata di rete), così il modello dell'SDK resta disaccoppiato
 * dalla serializzazione e il route ripreso è sempre fresco.
 *
 * Storage: un singolo file JSON in `filesDir`. Niente DataStore per non aggiungere
 * dipendenze: il payload è minuscolo e scritto di rado (allo start/stop guida).
 */
internal class TripStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "active_trip.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Stato persistito: piano + mezzo. Il mezzo è il NOME dell'enum (l'enum
     * resta puro, senza dipendere da kotlinx.serialization nel suo modulo). */
    @Serializable
    private data class SavedDto(val plan: TripPlan, val vehicle: String)

    /** Esito del caricamento, con l'enum risolto. */
    data class Saved(val plan: TripPlan, val vehicle: VehicleKind)

    /** Salva il viaggio attivo. */
    fun save(plan: TripPlan, vehicle: VehicleKind) {
        runCatching { file.writeText(json.encodeToString(SavedDto(plan, vehicle.name))) }
    }

    /** Carica il viaggio salvato, o null se assente/illeggibile. */
    fun load(): Saved? = runCatching {
        if (!file.exists()) return null
        val dto = json.decodeFromString<SavedDto>(file.readText())
        val vehicle = VehicleKind.entries.firstOrNull { it.name == dto.vehicle } ?: VehicleKind.CAR
        Saved(dto.plan, vehicle).takeIf { it.plan.isRoutable }
    }.getOrNull()

    /** Cancella il viaggio salvato (a fine navigazione/arrivo). */
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }
}
