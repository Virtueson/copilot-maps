package com.virtueson.copilotmaps.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.virtueson.copilotmaps.data.DefaultCopilotRepository
import com.virtueson.copilotmaps.data.DefaultPlacesRepository
import com.virtueson.copilotmaps.data.DefaultRoutesRepository
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Place
import com.virtueson.copilotmaps.data.Route
import com.virtueson.copilotmaps.data.RouteSummary
import com.virtueson.copilotmaps.data.TrafficInterval
import com.virtueson.copilotmaps.data.TrafficSpeed
import com.virtueson.copilotmaps.data.TripContext
import com.virtueson.copilotmaps.data.buildTrafficSegments
import com.virtueson.copilotmaps.location.FusedLocationProvider
import com.virtueson.copilotmaps.location.LocationSample
import com.virtueson.copilotmaps.network.NetworkModule
import com.virtueson.copilotmaps.voice.AndroidVoiceInput
import com.virtueson.copilotmaps.voice.AndroidVoiceOutput
import com.virtueson.copilotmaps.ui.copilot.CopilotChatSheet
import com.virtueson.copilotmaps.ui.copilot.CopilotUiState
import com.virtueson.copilotmaps.ui.copilot.CopilotViewModel
import com.virtueson.copilotmaps.ui.copilot.CopilotViewModelFactory

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapViewModel: MapViewModel = viewModel(
        factory = MapViewModelFactory(
            FusedLocationProvider(LocationServices.getFusedLocationProviderClient(context))
        )
    )
    val routeViewModel: RouteViewModel = viewModel(
        factory = RouteViewModelFactory(DefaultRoutesRepository(NetworkModule.routesApi))
    )
    val placesViewModel: PlacesViewModel = viewModel(
        factory = PlacesViewModelFactory(DefaultPlacesRepository(NetworkModule.placesApi))
    )
    val voiceInput = remember { AndroidVoiceInput(context) }
    val voiceOutput = remember { AndroidVoiceOutput(context) }
    val copilotViewModel: CopilotViewModel = viewModel(
        factory = CopilotViewModelFactory(
            DefaultCopilotRepository(NetworkModule.copilotApi),
            voiceInput,
            voiceOutput,
        )
    )
    val locationState by mapViewModel.uiState.collectAsStateWithLifecycle()
    val routesState by routeViewModel.state.collectAsStateWithLifecycle()
    val placesState by placesViewModel.state.collectAsStateWithLifecycle()
    val copilotState by copilotViewModel.state.collectAsStateWithLifecycle()
    val locationSample by mapViewModel.location.collectAsStateWithLifecycle()

    var pendingMicContext by remember { mutableStateOf<TripContext?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val tripContext = pendingMicContext
        pendingMicContext = null
        if (granted && tripContext != null) copilotViewModel.onMicTapped(tripContext)
    }
    val handleMic: (TripContext) -> Unit = { tripContext ->
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            copilotViewModel.onMicTapped(tripContext)
        } else {
            pendingMicContext = tripContext
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) mapViewModel.onPermissionGranted() else mapViewModel.onPermissionDenied()
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) mapViewModel.onPermissionGranted()
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    when (val loc = locationState) {
        is MapUiState.Loading -> Centered(modifier) { CircularProgressIndicator() }

        is MapUiState.PermissionNeeded -> Centered(modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(
                    "Location permission is needed to show your position on the map.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                    Text("Grant permission")
                }
            }
        }

        is MapUiState.Error -> Centered(modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("Couldn't get your location: ${loc.message}", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { mapViewModel.onPermissionGranted() }) { Text("Retry") }
            }
        }

        is MapUiState.Located -> {
            val origin = GeoPoint(loc.latitude, loc.longitude)
            RoutingMap(
                modifier = modifier,
                location = locationSample,
                origin = origin,
                routesState = routesState,
                placesState = placesState,
                copilotState = copilotState,
                onPlan = { dest -> routeViewModel.planRoutes(origin, dest) },
                onSelect = routeViewModel::selectRoute,
                onSearchPlaces = { category ->
                    val polyline = (routesState as? RoutesState.Loaded)?.let { loaded ->
                        loaded.routes.firstOrNull { it.id == loaded.selectedId }?.polyline
                    }
                    placesViewModel.search(category, origin, polyline)
                },
                onClearPlaces = placesViewModel::clear,
                onSendCopilot = { text ->
                    copilotViewModel.sendMessage(text, buildTripContext(origin, routesState))
                },
                onMicCopilot = { handleMic(buildTripContext(origin, routesState)) },
                onToggleTts = copilotViewModel::setTtsEnabled,
            )
        }
    }
}

@Composable
private fun RoutingMap(
    modifier: Modifier,
    location: LocationSample?,
    origin: GeoPoint,
    routesState: RoutesState,
    placesState: PlacesState,
    copilotState: CopilotUiState,
    onPlan: (GeoPoint) -> Unit,
    onSelect: (String) -> Unit,
    onSearchPlaces: (PlaceCategory) -> Unit,
    onClearPlaces: () -> Unit,
    onSendCopilot: (String) -> Unit,
    onMicCopilot: () -> Unit,
    onToggleTts: (Boolean) -> Unit,
) {
    var destination by remember { mutableStateOf<LatLng?>(null) }
    val destinationMarkerState = rememberMarkerState()
    LaunchedEffect(destination) {
        destination?.let { destinationMarkerState.position = it }
    }
    val originLatLng = LatLng(origin.lat, origin.lng)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(originLatLng, 15f)
    }

    var following by remember { mutableStateOf(true) }

    // Disengage follow when the user pans the map by gesture.
    LaunchedEffect(cameraPositionState.isMoving) {
        if (cameraPositionState.isMoving &&
            cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
        ) {
            following = false
        }
    }

    // While following, animate the camera to each new location in a heading-up driving view.
    LaunchedEffect(location, following) {
        val loc = location
        if (following && loc != null) {
            val target = CameraPosition.Builder()
                .target(LatLng(loc.latitude, loc.longitude))
                .zoom(17f)
                .tilt(45f)
                .bearing(loc.bearing ?: cameraPositionState.position.bearing)
                .build()
            try {
                cameraPositionState.animate(
                    CameraUpdateFactory.newCameraPosition(target), 1000,
                )
            } catch (e: Exception) {
                // Map not ready yet; the next sample will retry.
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = true),
            uiSettings = MapUiSettings(myLocationButtonEnabled = true),
            onMapLongClick = { latLng ->
                destination = latLng
                onPlan(GeoPoint(latLng.latitude, latLng.longitude))
            },
        ) {
            if (destination != null) {
                Marker(state = destinationMarkerState, title = "Destination")
            }
            if (routesState is RoutesState.Loaded) {
                routesState.routes.forEach { route ->
                    val selected = route.id == routesState.selectedId
                    if (selected) {
                        buildTrafficSegments(route.points, route.trafficIntervals).forEach { segment ->
                            Polyline(
                                points = segment.points.map { LatLng(it.lat, it.lng) },
                                color = trafficColor(segment.speed),
                                width = 18f,
                                zIndex = 2f,
                                clickable = true,
                                onClick = { onSelect(route.id) },
                            )
                        }
                    } else {
                        Polyline(
                            points = route.points.map { LatLng(it.lat, it.lng) },
                            color = Color(0xFF9AA0A6),
                            width = 9f,
                            zIndex = 1f,
                            clickable = true,
                            onClick = { onSelect(route.id) },
                        )
                    }
                }
            }
            if (placesState is PlacesState.Loaded) {
                placesState.places.forEach { place ->
                    Marker(
                        state = rememberMarkerState(
                            key = place.id,
                            position = LatLng(place.location.lat, place.location.lng),
                        ),
                        title = place.name,
                        snippet = placeSnippet(place),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                    )
                }
            }
        }

        // Top overlay: category buttons + status/notes.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(onClick = { onSearchPlaces(PlaceCategory.GAS) }) { Text("Gas") }
                    Button(onClick = { onSearchPlaces(PlaceCategory.FOOD) }) { Text("Food") }
                    OutlinedButton(onClick = onClearPlaces) { Text("Clear") }
                }
            }
            val banner: String? = when {
                placesState is PlacesState.Loading -> "Searching…"
                placesState is PlacesState.Error -> placesState.message
                placesState is PlacesState.Loaded && placesState.places.isEmpty() -> "No places found nearby."
                placesState is PlacesState.Loaded && placesState.fellBackToNearby -> "None on your route — showing nearest."
                else -> null
            }
            if (banner != null) {
                Spacer(Modifier.height(6.dp))
                Surface(tonalElevation = 3.dp) {
                    Text(banner, modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center)
                }
            }
        }

        // Bottom overlay: route ETA cards / status.
        when (routesState) {
            is RoutesState.Idle -> BottomBar { Text("Long-press the map to set a destination") }
            is RoutesState.Loading -> BottomBar { Text("Finding routes…") }
            is RoutesState.Error -> BottomBar {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(routesState.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { destination?.let { onPlan(GeoPoint(it.latitude, it.longitude)) } }) {
                        Text("Retry")
                    }
                }
            }
            is RoutesState.Loaded -> RouteCards(
                state = routesState,
                onSelect = onSelect,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (!following) {
            FloatingActionButton(
                onClick = { following = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = 16.dp, bottom = 88.dp),
            ) { Text("◎") }
        }

        var showChat by remember { mutableStateOf(false) }
        ExtendedFloatingActionButton(
            onClick = { showChat = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
        ) { Text("Copilot") }

        if (showChat) {
            CopilotChatSheet(
                state = copilotState,
                onSend = onSendCopilot,
                onMic = onMicCopilot,
                onToggleTts = onToggleTts,
                onDismiss = { showChat = false },
            )
        }
    }
}

private fun placeSnippet(place: Place): String {
    val parts = mutableListOf<String>()
    place.rating?.let { parts.add("★ $it") }
    place.address?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

@Composable
private fun BoxScope.BottomBar(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .fillMaxWidth()
            .padding(12.dp),
        tonalElevation = 3.dp,
    ) {
        Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun RouteCards(
    state: RoutesState.Loaded,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.routes.forEach { route ->
            val selected = route.id == state.selectedId
            Card(
                onClick = { onSelect(route.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) Color(0xFFD2E3FC) else Color(0xFFF1F3F4)
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = route.summary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(formatDuration(route.durationSeconds))
                    Text(formatDistance(route.distanceMeters))
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return "$minutes min"
}

private fun formatDistance(meters: Int): String =
    if (meters >= 1000) String.format("%.1f km", meters / 1000.0) else "$meters m"

private fun trafficColor(speed: TrafficSpeed): Color = when (speed) {
    TrafficSpeed.NORMAL -> Color(0xFF34A853)
    TrafficSpeed.SLOW -> Color(0xFFFBBC04)
    TrafficSpeed.JAM -> Color(0xFFEA4335)
    TrafficSpeed.UNKNOWN -> Color(0xFF1A73E8)
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}

private fun buildTripContext(origin: GeoPoint, routesState: RoutesState): TripContext {
    val loaded = routesState as? RoutesState.Loaded
    val routes: List<Route> = loaded?.routes ?: emptyList()
    val selectedId = loaded?.selectedId
    val selectedPolyline = routes.firstOrNull { it.id == selectedId }?.polyline
    val summaries = routes.map { r ->
        RouteSummary(
            summary = r.summary,
            distanceMeters = r.distanceMeters,
            durationSeconds = r.durationSeconds,
            traffic = trafficLabel(r.trafficIntervals),
            selected = r.id == selectedId,
        )
    }
    return TripContext(origin, selectedPolyline, summaries)
}

private fun trafficLabel(intervals: List<TrafficInterval>): String = when {
    intervals.any { it.speed == TrafficSpeed.JAM } -> "heavy"
    intervals.any { it.speed == TrafficSpeed.SLOW } -> "moderate"
    else -> "light"
}
