package com.example.psbill.modules.properties

import android.content.Context
import android.graphics.Color as AColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

internal fun money(v: Double?, currency: String = "KES"): String =
    if (v == null) "—" else "$currency ${"%,.0f".format(v)}"

internal fun distance(m: Double?): String = when {
    m == null -> "—"
    m >= 1000 -> "%.1f km".format(m / 1000)
    else -> "${m.toInt()} m"
}

internal fun minutes(s: Double?): String = when {
    s == null -> "—"
    s < 3600 -> "${maxOf(1, (s / 60).toInt())} min"
    else -> "${(s / 3600).toInt()} h ${((s % 3600) / 60).toInt()} min"
}

private fun initOsm(context: Context) {
    // OSM tile servers require an identifying user agent.
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }
}

@Composable
private fun rememberMapView(): MapView {
    val context = LocalContext.current
    val map = remember {
        initOsm(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    return map
}

/** House + shop + route polyline. [focus] zooms to one turn instruction. */
@Composable
fun RouteMapView(
    house: GeoPoint,
    houseTitle: String,
    shop: GeoPoint?,
    shopLabel: String,
    route: List<GeoPoint>,
    focus: GeoPoint?,
    modifier: Modifier = Modifier,
) {
    val map = rememberMapView()
    AndroidView(factory = { map }, modifier = modifier, update = { mv ->
        mv.overlays.clear()
        if (route.size > 1) {
            mv.overlays.add(Polyline(mv).apply {
                setPoints(route)
                outlinePaint.color = AColor.parseColor("#2563EB")
                outlinePaint.strokeWidth = 12f
            })
        }
        shop?.let {
            mv.overlays.add(Marker(mv).apply {
                position = it; title = shopLabel; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        mv.overlays.add(Marker(mv).apply {
            position = house; title = houseTitle; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        })
        val pts = if (route.size > 1) route else listOfNotNull(house, shop)
        mv.post {
            when {
                focus != null -> { mv.controller.setZoom(18.0); mv.controller.setCenter(focus) }
                pts.size > 1 -> runCatching { mv.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), false) }
                else -> { mv.controller.setZoom(16.0); mv.controller.setCenter(house) }
            }
        }
        mv.invalidate()
    })
}

/** Tap (or long-press) to drop the house pin. */
@Composable
fun LocationPickerView(
    point: GeoPoint?,
    shop: GeoPoint?,
    onPick: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val map = rememberMapView()
    AndroidView(factory = { mv ->
        map.apply {
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onPick(p); return true }
                override fun longPressHelper(p: GeoPoint): Boolean { onPick(p); return true }
            }))
            controller.setCenter(point ?: shop ?: GeoPoint(-1.2921, 36.8219))
            controller.setZoom(if (point != null) 17.0 else 14.0)
        }
    }, modifier = modifier, update = { mv ->
        mv.overlays.removeAll { it is Marker }
        shop?.let { mv.overlays.add(Marker(mv).apply { position = it; title = "Shop" }) }
        point?.let {
            mv.overlays.add(Marker(mv).apply { position = it; title = "House"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
            mv.controller.animateTo(it)
        }
        mv.invalidate()
    })
}
