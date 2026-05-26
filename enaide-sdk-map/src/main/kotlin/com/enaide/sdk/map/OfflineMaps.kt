package com.enaide.sdk.map

import android.content.Context
import com.enaide.sdk.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume

/**
 * Gestione **mappe offline** tramite l'OfflineManager nativo di MapLibre: scarica
 * e mette in cache le tile di un'area (bounding box + range di zoom) dallo style
 * vettoriale, così la mappa funziona senza rete in quella zona.
 *
 * Il routing resta online (Valhalla offline è un'iterazione separata).
 *
 * NB: l'istanza pubblica OpenFreeMap ha una fair-use policy; per pacchetti grandi
 * o uso intensivo conviene self-host. Qui scarichiamo aree limitate (es. città).
 */
public class OfflineMaps(context: Context) {

    init { MapLibre.getInstance(context) }
    private val manager = OfflineManager.getInstance(context.applicationContext)

    /** Stato di avanzamento di un download. */
    public sealed class Progress {
        public data class Downloading(public val percent: Int) : Progress()
        public data object Completed : Progress()
        public data class Failed(public val reason: String) : Progress()
    }

    /** Una regione offline salvata. */
    public data class Region(public val id: Long, public val name: String)

    /**
     * Scarica le tile per il riquadro [southWest]–[northEast] tra [minZoom] e
     * [maxZoom] usando lo [styleUri]. Emette il progresso fino al completamento.
     *
     * @param name etichetta della regione (mostrata in lista).
     */
    public fun download(
        name: String,
        styleUri: String,
        southWest: GeoPoint,
        northEast: GeoPoint,
        minZoom: Double = 6.0,
        maxZoom: Double = 16.0,
        pixelRatio: Float = 2.0f,
    ): Flow<Progress> = callbackFlow {
        val bounds = LatLngBounds.Builder()
            .include(LatLng(southWest.latitude, southWest.longitude))
            .include(LatLng(northEast.latitude, northEast.longitude))
            .build()
        val definition = OfflineTilePyramidRegionDefinition(styleUri, bounds, minZoom, maxZoom, pixelRatio)
        val metadata = name.toByteArray()

        var active: OfflineRegion? = null
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                active = region
                region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val total = status.requiredResourceCount.coerceAtLeast(1)
                        val pct = (100.0 * status.completedResourceCount / total).toInt().coerceIn(0, 100)
                        if (status.isComplete) {
                            trySend(Progress.Completed); close()
                        } else {
                            trySend(Progress.Downloading(pct))
                        }
                    }
                    override fun onError(error: OfflineRegionError) {
                        trySend(Progress.Failed(error.message)); close()
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        trySend(Progress.Failed("Limite tile superato ($limit)")); close()
                    }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) {
                trySend(Progress.Failed(error)); close()
            }
        })
        awaitClose { active?.setDownloadState(OfflineRegion.STATE_INACTIVE) }
    }

    /** Elenca le regioni offline salvate. */
    public suspend fun list(): List<Region> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                cont.resume(regions.orEmpty().map { Region(it.id, String(it.metadata)) })
            }
            override fun onError(error: String) { cont.resume(emptyList()) }
        })
    }

    /** Elimina una regione offline. */
    public suspend fun delete(id: Long): Unit = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                val region = regions.orEmpty().firstOrNull { it.id == id }
                if (region == null) { cont.resume(Unit); return }
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() { cont.resume(Unit) }
                    override fun onError(error: String) { cont.resume(Unit) }
                })
            }
            override fun onError(error: String) { cont.resume(Unit) }
        })
    }
}
