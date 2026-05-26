package com.enaide.sdk.simulation

import com.enaide.sdk.core.GeoUtils
import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Route
import com.enaide.sdk.model.UserLocation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Emette un [Flow] di [UserLocation] simulando la guida lungo un [route].
 *
 * Due modalità di realismo:
 *  - **costante** ([speedMetersPerSecond] fisso): semplice, deterministico, ideale per
 *    test della state machine in CI.
 *  - **fisica realistica** ([physics] != null): l'auto accelera, decelera e
 *    rallenta nelle curve in base all'angolo di virata, con jitter GPS gaussiano.
 *    Vicino al comportamento di un veicolo vero, ideale per demo "drive mode".
 *
 * Comune a entrambe:
 *  - Interpola lungo la geometria a passo determinato dalla velocità istantanea.
 *  - Emette una location ogni [tickInterval] (default 1Hz).
 *  - `courseDegrees` = bearing del segmento corrente.
 *  - Termina alla fine del route.
 *
 * @property route percorso da percorrere.
 * @property speedMetersPerSecond velocità di crociera (cap superiore in modalità fisica).
 * @property tickInterval frequenza di emissione.
 * @property jitterMeters deviazione standard del rumore GPS, in metri. 0 = esatto.
 * @property startTimeEpochMillis tempo del primo fix.
 * @property physics se non null, abilita il modello di guida realistico.
 */
public class SimulatedLocationSource(
    private val route: Route,
    private val speedMetersPerSecond: Double = 14.0,
    private val tickInterval: Duration = 1.seconds,
    private val jitterMeters: Double = 0.0,
    private val startTimeEpochMillis: Long = System.currentTimeMillis(),
    private val physics: DrivingPhysics? = null,
    private val wrongTurn: WrongTurn? = null,
) {
    /** Lunghezze cumulate per indice di vertice, pre-calcolate. */
    private val cumulative: DoubleArray = DoubleArray(route.geometry.size).also { arr ->
        for (i in 1 until route.geometry.size) {
            arr[i] = arr[i - 1] + GeoUtils.distanceMeters(route.geometry[i - 1], route.geometry[i])
        }
    }
    private val totalLengthMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    /**
     * Cap di velocità (m/s) a ciascun vertice della geometria, dettato dall'angolo
     * di virata: più la curva è secca, più si rallenta. Solo in modalità fisica.
     */
    private val cornerSpeedCap: DoubleArray by lazy { computeCornerSpeedCaps() }

    public fun asFlow(): Flow<UserLocation> =
        if (physics != null) realisticFlow(physics) else constantFlow()

    // --- Modalità a velocità costante (semplice, per CI) -----------------------

    private fun constantFlow(): Flow<UserLocation> = flow {
        require(route.geometry.size >= 2) { "route deve avere almeno 2 punti" }
        var distanceTraveled = 0.0
        var elapsedMillis = 0L
        val deltaPerTick = speedMetersPerSecond * (tickInterval.inWholeMilliseconds / 1000.0)

        while (distanceTraveled <= totalLengthMeters) {
            emit(fixAt(distanceTraveled, speedMetersPerSecond, elapsedMillis))
            delay(tickInterval)
            elapsedMillis += tickInterval.inWholeMilliseconds
            distanceTraveled += deltaPerTick
        }
    }

    // --- Modalità fisica realistica -------------------------------------------

    /**
     * Integra il moto a piccoli step temporali rispettando accelerazione,
     * decelerazione e il cap di velocità in curva. Emette un fix ogni [tickInterval].
     */
    private fun realisticFlow(p: DrivingPhysics): Flow<UserLocation> = flow {
        require(route.geometry.size >= 2) { "route deve avere almeno 2 punti" }

        val dtMillis = INTEGRATION_STEP.inWholeMilliseconds
        val dt = dtMillis / 1000.0
        val tickMillis = tickInterval.inWholeMilliseconds

        var distance = 0.0
        var speed = 0.0
        var elapsedMillis = 0L
        var sinceLastTick = Long.MAX_VALUE // emette subito il primo fix

        while (distance <= totalLengthMeters) {
            if (sinceLastTick >= tickMillis) {
                emit(fixAt(distance, speed, elapsedMillis))
                sinceLastTick = 0L
            }

            // Velocità target = min(crociera, cap di curva entro la distanza di frenata).
            val target = targetSpeedAt(distance, p)
            speed = if (speed < target) {
                (speed + p.accelMetersPerSecond2 * dt).coerceAtMost(target)
            } else {
                (speed - p.decelMetersPerSecond2 * dt).coerceAtLeast(target)
            }.coerceAtLeast(MIN_CREEP_SPEED)

            distance += speed * dt
            elapsedMillis += dtMillis
            sinceLastTick += dtMillis

            // Avanza in TEMPO REALE: senza questo delay il loop emetterebbe tutti
            // i fix istantaneamente e il puntatore "salterebbe" alla fine.
            delay(INTEGRATION_STEP)
        }
        // Fix finale a destinazione.
        emit(fixAt(totalLengthMeters, 0.0, elapsedMillis))
    }

    /**
     * Velocità desiderata alla distanza [d]: la crociera, ridotta se serve frenare
     * in tempo per un cap di curva imminente (modello di frenata `v² = 2·a·s`).
     */
    private fun targetSpeedAt(d: Double, p: DrivingPhysics): Double {
        var target = speedMetersPerSecond
        // Guarda avanti i prossimi vertici e impone che si possa frenare in tempo.
        var i = findSegmentIndex(d)
        while (i < cornerSpeedCap.size) {
            val vertexDist = cumulative[i]
            val ahead = vertexDist - d
            if (ahead > p.lookAheadMeters) break
            if (ahead >= 0) {
                val cap = cornerSpeedCap[i]
                // Velocità max ora per riuscire a scendere a `cap` tra `ahead` metri.
                val brakeable = sqrt(cap * cap + 2 * p.decelMetersPerSecond2 * ahead)
                target = minOf(target, brakeable)
            }
            i++
        }
        return target.coerceAtLeast(MIN_CREEP_SPEED)
    }

    /**
     * Cap di velocità a ogni vertice in base all'angolo di virata fra il segmento
     * entrante e quello uscente: dritto = nessun limite, tornante = molto lento.
     */
    private fun computeCornerSpeedCaps(): DoubleArray {
        val n = route.geometry.size
        val caps = DoubleArray(n) { speedMetersPerSecond }
        for (i in 1 until n - 1) {
            val inBearing = GeoUtils.bearingDegrees(route.geometry[i - 1], route.geometry[i])
            val outBearing = GeoUtils.bearingDegrees(route.geometry[i], route.geometry[i + 1])
            val turn = angularDifference(inBearing, outBearing) // 0..180
            // Mappa l'angolo su un fattore [0.12 .. 1]: 0° -> piena, 120°+ -> ~strada a passo d'uomo.
            val factor = (1.0 - (turn / 120.0)).coerceIn(0.12, 1.0)
            caps[i] = speedMetersPerSecond * factor
        }
        return caps
    }

    /** Differenza angolare minima fra due bearing, in gradi (0..180). */
    private fun angularDifference(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    // --- Helper geometria ------------------------------------------------------

    private fun fixAt(distance: Double, speed: Double, elapsedMillis: Long): UserLocation {
        val onRoute = pointAtDistance(distance)
        val bearing = bearingAtDistance(distance)

        // Errore di percorso simulato: oltre la soglia, il punto si stacca dalla
        // rotta spostandosi LATERALMENTE (perpendicolare al moto) in modo crescente.
        // Serve a far scattare il rilevamento di deviazione -> reroute.
        val base = wrongTurn?.let { wt ->
            if (distance >= wt.afterMeters) {
                val drift = ((distance - wt.afterMeters) * wt.lateralGrowth)
                    .coerceAtMost(wt.maxLateralMeters)
                offsetPerpendicular(onRoute, bearing, drift * wt.side)
            } else onRoute
        } ?: onRoute

        // Jitter GPS contenuto: rumore gaussiano piccolo, non sballotta la traiettoria.
        val jittered = if (jitterMeters > 0) jitter(base) else base
        return UserLocation(
            point = jittered,
            horizontalAccuracyMeters = if (jitterMeters > 0) jitterMeters else 5.0,
            courseDegrees = bearing,
            speedMetersPerSecond = speed,
            timestampEpochMillis = startTimeEpochMillis + elapsedMillis,
        )
    }

    /** Sposta [p] di [meters] perpendicolarmente al [bearing] (positivo = destra). */
    private fun offsetPerpendicular(p: GeoPoint, bearing: Double, meters: Double): GeoPoint {
        if (meters == 0.0) return p
        val perpRad = Math.toRadians((bearing + 90.0) % 360.0)
        val dNorth = meters * kotlin.math.cos(perpRad)
        val dEast = meters * kotlin.math.sin(perpRad)
        val cosLat = kotlin.math.cos(Math.toRadians(p.latitude)).coerceAtLeast(1e-6)
        return GeoPoint(
            latitude = p.latitude + dNorth / 111_195.0,
            longitude = p.longitude + dEast / (111_195.0 * cosLat),
        )
    }

    private fun pointAtDistance(d: Double): GeoPoint {
        if (d <= 0.0) return route.geometry.first()
        if (d >= totalLengthMeters) return route.geometry.last()
        val segIdx = findSegmentIndex(d)
        val segStart = cumulative[segIdx]
        val segEnd = cumulative[segIdx + 1]
        val segLen = (segEnd - segStart).coerceAtLeast(1e-9)
        val t = ((d - segStart) / segLen).coerceIn(0.0, 1.0)
        val a = route.geometry[segIdx]
        val b = route.geometry[segIdx + 1]
        return GeoPoint(
            latitude = a.latitude + t * (b.latitude - a.latitude),
            longitude = a.longitude + t * (b.longitude - a.longitude),
        )
    }

    private fun bearingAtDistance(d: Double): Double {
        val segIdx = findSegmentIndex(d.coerceIn(0.0, totalLengthMeters - 0.001))
        val a = route.geometry[segIdx]
        val b = route.geometry[(segIdx + 1).coerceAtMost(route.geometry.lastIndex)]
        return GeoUtils.bearingDegrees(a, b)
    }

    private fun findSegmentIndex(d: Double): Int {
        var lo = 0; var hi = cumulative.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo.coerceAtMost(route.geometry.size - 2)
    }

    private fun jitter(p: GeoPoint): GeoPoint {
        val u1 = kotlin.random.Random.nextDouble().coerceAtLeast(1e-9)
        val u2 = kotlin.random.Random.nextDouble()
        val mag = jitterMeters * kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1))
        val z0 = mag * kotlin.math.cos(2.0 * Math.PI * u2)
        val z1 = mag * kotlin.math.sin(2.0 * Math.PI * u2)
        val cosLat = kotlin.math.cos(Math.toRadians(p.latitude)).coerceAtLeast(1e-6)
        return GeoPoint(
            latitude = p.latitude + z0 / 111_195.0,
            longitude = p.longitude + z1 / (111_195.0 * cosLat),
        )
    }

    public companion object {
        /** Passo di integrazione del modello fisico. Più piccolo = più liscio. */
        private val INTEGRATION_STEP: Duration = 100.milliseconds

        /** Non scende mai sotto questa velocità in moto, per non incastrarsi a 0. */
        private const val MIN_CREEP_SPEED = 1.5

        /** Sorgente "urban driving" tipica (~30 km/h), velocità costante. */
        public fun urban(route: Route): SimulatedLocationSource =
            SimulatedLocationSource(route, speedMetersPerSecond = 8.3)

        /** Sorgente "highway driving" tipica (~110 km/h), velocità costante. */
        public fun highway(route: Route): SimulatedLocationSource =
            SimulatedLocationSource(route, speedMetersPerSecond = 30.5)

        /** Default per esempi/CI: ~50 km/h costante, tick 500ms. */
        public fun default(route: Route): SimulatedLocationSource =
            SimulatedLocationSource(route, speedMetersPerSecond = 14.0, tickInterval = 500.milliseconds)

        /**
         * Sorgente con **fisica di guida realistica**: accelera/frena, rallenta in
         * curva, jitter GPS contenuto (traiettoria lineare). Ideale per drive mode.
         *
         * @param cruiseKmh velocità di crociera in km/h.
         * @param wrongTurn se non null, a un certo punto l'auto "sbaglia strada"
         *   uscendo dalla rotta — utile per testare il reroute automatico.
         */
        public fun realistic(
            route: Route,
            cruiseKmh: Double = 50.0,
            wrongTurn: WrongTurn? = null,
        ): SimulatedLocationSource =
            SimulatedLocationSource(
                route = route,
                speedMetersPerSecond = cruiseKmh / 3.6,
                tickInterval = 500.milliseconds,
                jitterMeters = 1.5, // rumore GPS piccolo: la traiettoria resta lineare
                physics = DrivingPhysics(),
                wrongTurn = wrongTurn,
            )
    }
}

/**
 * Configura un **errore di percorso** simulato: l'auto si stacca dalla rotta in
 * modo crescente, facendo scattare il rilevamento di deviazione (e quindi il
 * reroute) dell'SDK.
 *
 * @property afterMeters distanza dall'inizio del percorso dopo la quale inizia lo scostamento.
 * @property lateralGrowth metri di scostamento laterale guadagnati per ogni metro percorso oltre [afterMeters].
 * @property maxLateralMeters scostamento laterale massimo (oltre, resta costante).
 * @property side +1 = scarta a destra, -1 = a sinistra.
 */
public data class WrongTurn(
    public val afterMeters: Double = 150.0,
    public val lateralGrowth: Double = 0.6,
    public val maxLateralMeters: Double = 120.0,
    public val side: Double = 1.0,
)

/**
 * Parametri del modello di guida realistico.
 *
 * @property accelMetersPerSecond2 accelerazione in marcia (m/s²). ~2.5 = berlina normale.
 * @property decelMetersPerSecond2 decelerazione/frenata (m/s²). ~3.5 = frenata confortevole.
 * @property lookAheadMeters quanto guardare avanti per anticipare le frenate in curva.
 */
public data class DrivingPhysics(
    public val accelMetersPerSecond2: Double = 2.5,
    public val decelMetersPerSecond2: Double = 3.5,
    public val lookAheadMeters: Double = 120.0,
)
