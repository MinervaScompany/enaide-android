package com.enaide.sdk.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE = "route-source"
private const val ROUTE_CASING_LAYER = "route-casing"
private const val ROUTE_LAYER = "route-layer"
private const val POSITION_SOURCE = "position-source"
private const val POSITION_LAYER = "position-layer"
private const val POSITION_HALO_LAYER = "position-halo"
private const val POSITION_ICON = "position-arrow"
private const val POI_SOURCE = "poi-source"
private const val POI_LAYER = "poi-layer"
private const val POI_ICON = "poi-pin"
private const val POI_PROP_LABEL = "label"
private const val POI_PROP_ID = "id"

/**
 * Marker generico da disegnare sulla mappa (es. un POI). Tipo neutro: il modulo
 * map non dipende dal modulo poi, così resta indipendente.
 *
 * @property id identificatore stabile (per il tap).
 * @property point coordinate.
 * @property label etichetta mostrata/usata al tap.
 */
public data class MapMarker(
    public val id: String,
    public val point: GeoPoint,
    public val label: String,
)

/** Zoom e tilt della camera in modalità guida 3D. */
private const val DRIVE_ZOOM = 17.5
private const val DRIVE_TILT = 55.0 // gradi di inclinazione (0 = dall'alto, 60 = molto prospettico)

/** Zoom di default della mappa 2D (esplorazione): ravvicinato, livello quartiere. */
private const val MAP_ZOOM = 16.0

/**
 * Stato della camera controllabile dall'esterno (es. FAB "ricentra").
 *
 * Tiene un flag [followMode]: in guida la camera insegue la posizione; quando
 * l'utente tocca/trascina la mappa il follow si disattiva e la mappa resta dove
 * l'ha lasciata l'utente, finché non chiama [recenter].
 */
class MapCameraState {
    internal var followMode: Boolean = true
    internal var onRecenter: (() -> Unit)? = null

    /** Riattiva l'inseguimento e ricentra sulla posizione corrente. */
    fun recenter() {
        followMode = true
        onRecenter?.invoke()
    }
}

/**
 * Vista mappa MapLibre, usabile sia per la **guida** sia come **mappa libera**.
 *
 * @param route percorso da disegnare; `null` = solo mappa (es. schermata iniziale).
 * @param position posizione corrente dell'utente (puntino + camera che segue).
 * @param bearing direzione di moto in gradi; null = nord.
 * @param threeD camera prospettica 3D che segue il bearing (modalità guida).
 * @param cameraState aggancio per il controllo esterno (ricentra / follow-mode).
 *
 * Interazioni: pan/zoom/rotate/tilt con le dita; al primo gesto la camera smette
 * di inseguire e si riattiva via [MapCameraState.recenter].
 */
@Composable
fun RouteMap(
    route: Route?,
    position: GeoPoint?,
    modifier: Modifier = Modifier,
    bearing: Double? = null,
    threeD: Boolean = false,
    cameraState: MapCameraState? = null,
    onLongPress: ((GeoPoint) -> Unit)? = null,
    markers: List<MapMarker> = emptyList(),
    onMarkerClick: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context) }

    val colors = EnaideTheme.colors
    val mapView = remember { MapView(context) }
    val holder = remember { MapHolder() }
    // Callback aggiornabili senza ricreare la mappa. In SideEffect: mutazione di
    // stato fatta dopo una composizione andata a buon fine, non durante.
    androidx.compose.runtime.SideEffect {
        holder.onLongPress = onLongPress
        holder.onMarkerClick = onMarkerClick
    }

    // Lifecycle del MapView legato a quello Android (non alla composizione): così
    // in background la mappa si ferma davvero (niente render/GL/location sprecati).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> mapView.onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.getMapAsync { map ->
            holder.map = map
            map.setStyle(Style.Builder().fromUri("asset://osm_raster_style.json")) { style ->
                setupLayers(style, colors)
                holder.styleReady = true
                route?.let {
                    setRouteGeometry(style, it)
                    if (position == null) fitToRoute(map, it)
                }
                position?.let {
                    updatePosition(style, it, bearing)
                    // All'avvio centra subito sulla posizione con zoom ravvicinato
                    // (anche senza GPS: parte da Zurigo a livello quartiere).
                    if (route == null) animateDriveCamera(map, it, bearing, threeD)
                }
            }
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    cameraState?.followMode = false
                }
            }
            // Long-press sulla mappa = scegli un punto (non confligge col pan/tap).
            map.addOnMapLongClickListener { latLng ->
                holder.onLongPress?.invoke(GeoPoint(latLng.latitude, latLng.longitude))
                true
            }
            // Tap su un marker POI = apri/naviga.
            map.addOnMapClickListener { latLng ->
                val pt = map.projection.toScreenLocation(latLng)
                val hits = map.queryRenderedFeatures(pt, POI_LAYER)
                val id = hits.firstOrNull()?.getStringProperty(POI_PROP_ID)
                if (id != null) { holder.onMarkerClick?.invoke(id); true } else false
            }
        }
        cameraState?.onRecenter = {
            val map = holder.map
            val pos = position
            if (map != null && pos != null) animateDriveCamera(map, pos, bearing, threeD)
        }
        onDispose {
            cameraState?.onRecenter = null
            // start/stop/pause/resume li gestisce il LifecycleObserver; qui solo destroy.
            mapView.onDestroy()
        }
    }

    // Aggiorna la geometria del route quando cambia (es. dopo un reroute).
    DisposableEffect(route?.id) {
        val map = holder.map
        if (map != null && holder.styleReady) {
            map.style?.let { style -> route?.let { setRouteGeometry(style, it) } }
        }
        onDispose { }
    }

    // Aggiorna i marker POI quando cambiano.
    DisposableEffect(markers) {
        val map = holder.map
        if (map != null && holder.styleReady) {
            map.style?.let { setMarkers(it, markers) }
        }
        onDispose { }
    }

    // Aggiorna marker + camera a ogni nuovo fix (solo se in follow-mode).
    DisposableEffect(position, bearing) {
        val map = holder.map
        if (position != null && map != null && holder.styleReady) {
            map.style?.let { updatePosition(it, position, bearing) }
            if (cameraState?.followMode != false) {
                animateDriveCamera(map, position, bearing, threeD)
            }
        }
        onDispose { }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private class MapHolder {
    var map: MapLibreMap? = null
    var styleReady: Boolean = false
    var onLongPress: ((GeoPoint) -> Unit)? = null
    var onMarkerClick: ((String) -> Unit)? = null
}

private fun animateDriveCamera(map: MapLibreMap, position: GeoPoint, bearing: Double?, threeD: Boolean) {
    // Centriamo SEMPRE sulla posizione utente. In 3D spostiamo il punto di vista
    // verso il basso (padding) cosi' l'utente sta nel terzo inferiore e si vede la
    // strada davanti — vista da navigatore.
    val h = map.height
    val builder = CameraPosition.Builder()
        .target(LatLng(position.latitude, position.longitude))
        .zoom(if (threeD) DRIVE_ZOOM else MAP_ZOOM)
        .tilt(if (threeD) DRIVE_TILT else 0.0)
        .bearing(if (threeD) (bearing ?: 0.0) else 0.0)
    if (threeD && h > 0) {
        // padding (left, top, right, bottom): top alto spinge il target in basso.
        builder.padding(doubleArrayOf(0.0, h * 0.45, 0.0, 0.0))
    }
    map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), 600)
}

/** Crea sorgenti e layer (route + posizione) UNA volta, anche senza dati ancora. */
private fun setupLayers(style: Style, colors: EnaideColors) {
    // Sorgente route vuota: popolata da setRouteGeometry quando c'è un percorso.
    style.addSource(GeoJsonSource(ROUTE_SOURCE))
    style.addLayer(
        LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.routeCasingHex()),
            PropertyFactory.lineWidth(11.0f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
    )
    style.addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.routeLineHex()),
            PropertyFactory.lineWidth(7.0f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
    )

    // Marker posizione: alone + freccia direzionale.
    style.addSource(GeoJsonSource(POSITION_SOURCE))
    style.addLayer(
        CircleLayer(POSITION_HALO_LAYER, POSITION_SOURCE).withProperties(
            PropertyFactory.circleRadius(22.0f),
            PropertyFactory.circleColor(colors.positionHaloHex()),
            PropertyFactory.circleOpacity(0.18f),
        )
    )
    style.addImage(POSITION_ICON, arrowBitmap())
    style.addLayer(
        SymbolLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
            PropertyFactory.iconImage(POSITION_ICON),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(1.0f),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconRotate(get("bearing")),
        )
    )

    // Marker POI: pin a goccia stile mappa. Sorgente vuota all'inizio.
    // NB: niente `textField` — lo style raster OSM non definisce `glyphs`, e un
    // SymbolLayer con testo senza glyphs NON viene renderizzato affatto (icona
    // inclusa). Mostriamo solo l'icona pin; l'etichetta appare al tap.
    style.addSource(GeoJsonSource(POI_SOURCE))
    style.addImage(POI_ICON, pinBitmap(AndroidColor.parseColor(colors.routeLineHex())))
    style.addLayer(
        SymbolLayer(POI_LAYER, POI_SOURCE).withProperties(
            PropertyFactory.iconImage(POI_ICON),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM), // punta in basso
            PropertyFactory.iconSize(1.0f),
        )
    )
}

/** Pin a goccia (stile mappa) disegnato a runtime, colore [argb]. */
private fun pinBitmap(argb: Int): Bitmap {
    val w = 48; val h = 64
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb; style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    // Goccia: cerchio in alto + punta in basso.
    val cx = w / 2f; val cy = w / 2f; val r = w / 2.6f
    val path = Path().apply {
        addCircle(cx, cy, r, Path.Direction.CW)
        moveTo(cx - r * 0.6f, cy + r * 0.6f)
        lineTo(cx, h.toFloat() - 2f)
        lineTo(cx + r * 0.6f, cy + r * 0.6f)
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    // Pallino bianco centrale.
    canvas.drawCircle(cx, cy, r * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE })
    return bmp
}

/** Popola/aggiorna la polilinea del percorso. */
private fun setRouteGeometry(style: Style, route: Route) {
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
    val points = route.geometry.map { Point.fromLngLat(it.longitude, it.latitude) }
    source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
}

/** Popola/aggiorna i marker POI. */
private fun setMarkers(style: Style, markers: List<MapMarker>) {
    val source = style.getSourceAs<GeoJsonSource>(POI_SOURCE) ?: return
    val features = markers.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.point.longitude, m.point.latitude)).apply {
            addStringProperty(POI_PROP_ID, m.id)
            addStringProperty(POI_PROP_LABEL, m.label)
        }
    }
    source.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(features))
}

/** Freccia/chevron nera puntata verso l'alto (0° = nord), disegnata a runtime. */
private fun arrowBitmap(): Bitmap {
    val size = 72
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val path = Path().apply {
        moveTo(size / 2f, size * 0.12f)        // punta in alto
        lineTo(size * 0.82f, size * 0.86f)     // base destra
        lineTo(size / 2f, size * 0.66f)        // incavo centrale
        lineTo(size * 0.18f, size * 0.86f)     // base sinistra
        close()
    }
    // Bordo bianco sotto, riempimento scuro sopra: leggibile su qualsiasi mappa.
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; color = AndroidColor.WHITE
        strokeJoin = Paint.Join.ROUND
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = AndroidColor.parseColor("#1A1A1A")
    })
    return bmp
}

private fun updatePosition(style: Style, position: GeoPoint, bearing: Double?) {
    val source = style.getSourceAs<GeoJsonSource>(POSITION_SOURCE) ?: return
    val feature = Feature.fromGeometry(Point.fromLngLat(position.longitude, position.latitude))
    feature.addNumberProperty("bearing", bearing ?: 0.0)
    source.setGeoJson(feature)
}

private fun fitToRoute(map: MapLibreMap, route: Route) {
    if (route.geometry.size < 2) return
    val builder = LatLngBounds.Builder()
    route.geometry.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
}
