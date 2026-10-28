package lab.dawnforge.hermes.feature.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import lab.dawnforge.hermes.core.model.CardPlace
import lab.dawnforge.hermes.core.model.CardRoute
import lab.dawnforge.hermes.core.model.PlacesCard
import lab.dawnforge.hermes.core.model.PlacesKind
import lab.dawnforge.hermes.core.model.RouteMode

/**
 * Places: a map with a pin per place and the route (with a Maps key), one place in detail, or a
 * strip of them. Each opens in the maps app; iOS looks them up in Apple Maps, the phone shows what
 * Hermes gave.
 */
@Composable
internal fun PlacesCardView(card: PlacesCard) {
    CardSurface {
        card.title?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        when (card.kind) {
            PlacesKind.Map -> {
                PlacesMap(card.places, card.route)
                card.places.forEachIndexed { i, place -> PlaceRow(place, if (card.places.size > 1) i + 1 else null) }
                card.route?.let { RouteButton(card.places, it) }
            }

            PlacesKind.Place -> PlaceDetail(card.places.first())

            PlacesKind.Places -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(card.places) { _, place -> PlaceTile(place) }
            }
        }
    }
}

/** A lite map of the places that have coordinates; nothing without a Maps key or coordinates. */
@Composable
private fun PlacesMap(places: List<CardPlace>, route: CardRoute?, height: Int = MAP_HEIGHT) {
    if (!LocalMapsAvailable.current) return
    val located = places.mapNotNull { place ->
        place.lat?.let { lat -> place.lon?.let { lon -> place to LatLng(lat, lon) } }
    }
    if (located.isEmpty()) return
    // New places start a new camera.
    val camera: CameraPositionState = key(located) {
        rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(located.first().second, SINGLE_ZOOM) }
    }
    Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(14.dp))) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
            uiSettings = MapUiSettings(mapToolbarEnabled = false, zoomControlsEnabled = false),
            onMapLoaded = {
                if (located.size > 1) {
                    val bounds = LatLngBounds.builder().apply { located.forEach { include(it.second) } }.build()
                    camera.move(com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(bounds, MAP_PADDING))
                }
            },
        ) {
            located.forEach { (place, at) ->
                key(place.name, at) { Marker(state = rememberUpdatedMarkerState(position = at), title = place.name) }
            }
            if (route != null) {
                val to = places.getOrNull(route.to)?.let {
                    it.lat?.let { lat -> it.lon?.let { lon -> LatLng(lat, lon) } }
                }
                val from = route.from?.let {
                    places.getOrNull(it)
                }?.let { it.lat?.let { lat -> it.lon?.let { lon -> LatLng(lat, lon) } } }
                if (to != null &&
                    from != null
                ) {
                    Polyline(points = listOf(from, to), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: CardPlace, number: Int?) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { openPlace(context, place) },
    ) {
        if (number != null) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            place.address?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlaceFacts(place)
            place.note?.let { note ->
                Text("💬 $note", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
        }
    }
}

/** A rating, hours and price, each with where it's from. */
@Composable
private fun PlaceFacts(place: CardPlace) {
    val context = LocalContext.current
    place.rating?.let { rating ->
        Text(
            stars(rating.value) + " %.1f".format(rating.value) + (rating.count?.let { " ($it)" }.orEmpty()) + " · " +
                rating.source,
            style = MaterialTheme.typography.labelMedium,
            modifier = rating.url?.let { url -> Modifier.clickable { HostFiles.browse(context, url) } } ?: Modifier,
        )
    }
    place.hours?.let { Text("🕒 ${it.text} · ${it.source}", style = MaterialTheme.typography.labelMedium) }
    place.price?.let { Text("💵 ${it.text} · ${it.source}", style = MaterialTheme.typography.labelMedium) }
}

private fun stars(value: Double): String = (1..STARS).joinToString("") { i ->
    when {
        value >= i - STAR_FULL_SLACK -> "★"
        value >= i - STAR_HALF_SLACK -> "⯪"
        else -> "☆"
    }
}

@Composable
private fun PlaceDetail(place: CardPlace) {
    val context = LocalContext.current
    place.photo?.let { photo ->
        WebImage(photo.url, place.name, Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp)))
        Text(
            photo.source,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = photo.pageUrl?.let { url -> Modifier.clickable { HostFiles.browse(context, url) } } ?: Modifier,
        )
    }
    PlacesMap(listOf(place), null, height = if (place.photo != null) STRIP_HEIGHT else DETAIL_HEIGHT)
    Text(place.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    place.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    PlaceFacts(place)
    place.note?.let { Text("💬 $it", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { directions(context, null, place, RouteMode.Driving) }) {
            Text(stringResource(R.string.feature_chat_card_directions))
        }
        OutlinedButton(onClick = {
            openPlace(context, place)
        }) { Text(stringResource(R.string.feature_chat_card_open_maps)) }
    }
}

@Composable
private fun PlaceTile(place: CardPlace) {
    val context = LocalContext.current
    Column(
        Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { openPlace(context, place) }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val photo = place.photo
        if (photo != null) {
            WebImage(photo.url, place.name, Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            PlacesMap(listOf(place), null, height = TILE_MAP_HEIGHT)
        }
        Text(
            place.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        place.address?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        place.rating?.let {
            Text(
                stars(it.value) + " %.1f · %s".format(it.value, it.source),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        place.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

@Composable
private fun RouteButton(places: List<CardPlace>, route: CardRoute) {
    val context = LocalContext.current
    val to = places.getOrNull(route.to) ?: return
    val from = route.from?.let { places.getOrNull(it) }
    val mode = stringResource(
        when (route.mode) {
            RouteMode.Driving -> R.string.feature_chat_card_mode_driving
            RouteMode.Walking -> R.string.feature_chat_card_mode_walking
            RouteMode.Transit -> R.string.feature_chat_card_mode_transit
            RouteMode.Cycling -> R.string.feature_chat_card_mode_cycling
        },
    )
    FilledTonalButton(onClick = { directions(context, from, to, route.mode) }) {
        Text(stringResource(R.string.feature_chat_card_route, mode, to.name))
    }
}

/** A place in the maps app: by its coordinates when known, else by its name and address. */
private fun openPlace(context: Context, place: CardPlace) {
    val label = Uri.encode(listOfNotNull(place.name, place.address).joinToString(", "))
    val lat = place.lat
    val lon = place.lon
    val uri = if (lat != null &&
        lon != null
    ) {
        "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(place.name)})"
    } else {
        "geo:0,0?q=$label"
    }
    HostFiles.browse(context, uri)
}

/** Directions in the maps app, from [from] or (null) from where the phone is. */
private fun directions(context: Context, from: CardPlace?, to: CardPlace, mode: RouteMode) {
    val travel = when (mode) {
        RouteMode.Driving -> "driving"
        RouteMode.Walking -> "walking"
        RouteMode.Transit -> "transit"
        RouteMode.Cycling -> "bicycling"
    }
    val url = buildString {
        append("https://www.google.com/maps/dir/?api=1")
        append("&destination=").append(Uri.encode(where(to)))
        from?.let { append("&origin=").append(Uri.encode(where(it))) }
        append("&travelmode=").append(travel)
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun where(place: CardPlace): String {
    val lat = place.lat
    val lon = place.lon
    return if (lat != null && lon != null) "$lat,$lon" else listOfNotNull(place.name, place.address).joinToString(", ")
}

private const val MAP_HEIGHT = 190
private const val DETAIL_HEIGHT = 120
private const val STRIP_HEIGHT = 80
private const val TILE_MAP_HEIGHT = 110
private const val SINGLE_ZOOM = 14f
private const val MAP_PADDING = 80
private const val STARS = 5
private const val STAR_FULL_SLACK = 0.25
private const val STAR_HALF_SLACK = 0.75
