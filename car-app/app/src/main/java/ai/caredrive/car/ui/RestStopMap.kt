package ai.caredrive.car.ui

import ai.caredrive.car.model.GeoPos
import ai.caredrive.car.model.RestPreview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** OpenFreeMap: free vector tiles from OpenStreetMap, no API key. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Static map of the car and the suggested rest stop. No gestures and no camera animation,
 * so it stays glanceable while driving. Our own map because the emulator navigation map is empty.
 */
@Composable
fun RestStopMap(car: GeoPos?, rest: RestPreview, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        // Draw Hangul/CJK labels with the device font: the tile server's glyph set misses some characters.
        val options = MapLibreMapOptions.createFromAttributes(context).localIdeographFontFamily("sans-serif")
        MapView(context, options).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }) { view ->
            view.getMapAsync { map ->
                map.uiSettings.setAllGesturesEnabled(false)
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                val style = map.style
                if (style == null || !style.isFullyLoaded) {
                    map.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                        koreanLabels(s)
                        addMarkers(s)
                        // Fit after the view has its final size, otherwise the bounds fit uses 0×0.
                        view.post { show(map, s, car, rest) }
                    }
                } else {
                    view.post { show(map, style, car, rest) }
                }
            }
        }
        // Required attribution for OpenFreeMap / OpenMapTiles / OpenStreetMap.
        Text(
            "© OpenFreeMap © OpenMapTiles © OpenStreetMap",
            color = Color(0xFF475569), fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
    }
}

/**
 * Show Korean place names ("과천시") instead of the style's Latin + local pair, and hide road-name
 * labels: they clutter a glanceable map and some Hangul glyphs are missing on the tile font server.
 */
private fun koreanLabels(style: Style) {
    val name = Expression.coalesce(Expression.get("name:ko"), Expression.get("name"))
    style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        val roadLabel = layer.id.startsWith("highway-") || layer.id.startsWith("road_shield") ||
            layer.symbolPlacement.value == Property.SYMBOL_PLACEMENT_LINE
        when {
            roadLabel -> layer.setProperties(visibility(Property.NONE))
            layer.textField.expression != null || layer.textField.value != null -> layer.setProperties(textField(name))
        }
    }
}

private fun addMarkers(style: Style) {
    style.addSource(GeoJsonSource("cd-rest", FeatureCollection.fromFeatures(emptyList())))
    style.addSource(GeoJsonSource("cd-car", FeatureCollection.fromFeatures(emptyList())))
    style.addLayer(
        CircleLayer("cd-rest-dot", "cd-rest").withProperties(
            circleRadius(16f), circleColor("#F97316"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(5f),
        )
    )
    style.addLayer(
        CircleLayer("cd-car-dot", "cd-car").withProperties(
            circleRadius(13f), circleColor("#2563EB"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(5f),
        )
    )
}

private fun show(map: MapLibreMap, style: Style, car: GeoPos?, rest: RestPreview) {
    val restPt = Point.fromLngLat(rest.lon, rest.lat)
    style.getSourceAs<GeoJsonSource>("cd-rest")?.setGeoJson(Feature.fromGeometry(restPt))
    if (car != null) {
        style.getSourceAs<GeoJsonSource>("cd-car")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(car.lon, car.lat)))
        val bounds = LatLngBounds.Builder().include(LatLng(car.lat, car.lon)).include(LatLng(rest.lat, rest.lon)).build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 70))
        // Very close rest stops would zoom in too far; keep street-level context.
        if (map.cameraPosition.zoom > 15.0) map.moveCamera(CameraUpdateFactory.zoomTo(15.0))
    } else {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(rest.lat, rest.lon), 14.0))
    }
}
