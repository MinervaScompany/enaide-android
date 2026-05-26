package com.enaide.demo

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.UserLocation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Sorgente di posizione basata sul [LocationManager] nativo di Android (framework,
 * NON Google Play Services), coerente con la filosofia dell'SDK enaide: nessuna
 * dipendenza da GMS, l'integratore sceglie il provider.
 *
 * Emette un [Flow] di [UserLocation] che mappa i fix GPS reali nel modello
 * dell'SDK. L'app li gira a `EnaideNavigator.updateLocation(...)`.
 *
 * Il chiamante DEVE aver gia' ottenuto il permesso runtime
 * `ACCESS_FINE_LOCATION` (o coarse) prima di collezionare il flow: qui usiamo
 * [SuppressLint] perche' il controllo permesso e' responsabilita' della UI.
 *
 * In produzione un integratore puo' preferire `FusedLocationProviderClient`
 * (GMS) per fix piu' fluidi: basta produrre lo stesso [UserLocation]. La UI e il
 * motore di navigazione non cambiano.
 */
class GpsLocationSource(context: Context) {

    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Flow caldo di fix di posizione. Si registra al GPS quando il flow viene
     * collezionato e si deregistra alla cancellazione.
     *
     * @param minIntervalMillis intervallo minimo tra aggiornamenti.
     * @param minDistanceMeters spostamento minimo tra aggiornamenti.
     */
    /** Ultima posizione nota (la più recente fra i provider attivi), o null. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): UserLocation? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toUserLocation()

    @SuppressLint("MissingPermission")
    fun asFlow(
        minIntervalMillis: Long = 1000L,
        minDistanceMeters: Float = 0f,
    ): Flow<UserLocation> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toUserLocation())
            }

            // Override no-op per compatibilita' con API vecchie.
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        // Ascoltiamo TUTTI i provider attivi (GPS + network): indoor il network
        // dà un fix in pochi secondi, all'aperto il GPS è più preciso.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            close(IllegalStateException("Nessun provider di posizione attivo (GPS off?)"))
            return@callbackFlow
        }

        // Spinge subito l'ultimo fix noto più recente, per non far aspettare.
        providers.mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { trySend(it.toUserLocation()) }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(provider, minIntervalMillis, minDistanceMeters, listener)
        }

        awaitClose { locationManager.removeUpdates(listener) }
    }

    private fun Location.toUserLocation(): UserLocation = UserLocation(
        point = GeoPoint(latitude, longitude),
        altitudeMeters = if (hasAltitude()) altitude else null,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        courseDegrees = if (hasBearing()) bearing.toDouble() else null,
        speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
        timestampEpochMillis = time,
    )
}
