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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.virtueson.copilotmaps.data.DefaultRoutesRepository
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.location.FusedLocationProvider
import com.virtueson.copilotmaps.network.NetworkModule

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
    val locationState by mapViewModel.uiState.collectAsStateWithLifecycle()
    val routesState by routeViewModel.state.collectAsStateWithLifecycle()

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

        is MapUiState.Located -> RoutingMap(
            modifier = modifier,
            origin = GeoPoint(loc.latitude, loc.longitude),
            routesState = routesState,
            onPlan = { dest -> routeViewModel.planRoutes(GeoPoint(loc.latitude, loc.longitude), dest) },
            onSelect = routeViewModel::selectRoute,
        )
    }
}

@Composable
private fun RoutingMap(
    modifier: Modifier,
    origin: GeoPoint,
    routesState: RoutesState,
    onPlan: (GeoPoint) -> Unit,
    onSelect: (String) -> Unit,
) {
    var destination by remember { mutableStateOf<LatLng?>(null) }
    val originLatLng = LatLng(origin.lat, origin.lng)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(originLatLng, 15f)
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
            destination?.let { dest ->
                Marker(state = rememberMarkerState(key = dest.toString(), position = dest), title = "Destination")
            }
            if (routesState is RoutesState.Loaded) {
                routesState.routes.forEach { route ->
                    val selected = route.id == routesState.selectedId
                    Polyline(
                        points = route.points.map { LatLng(it.lat, it.lng) },
                        color = if (selected) Color(0xFF1A73E8) else Color(0xFF9AA0A6),
                        width = if (selected) 18f else 9f,
                        zIndex = if (selected) 2f else 1f,
                        clickable = true,
                        onClick = { onSelect(route.id) },
                    )
                }
            }
        }

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
    }
}

@Composable
private fun BoxScope.BottomBar(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
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

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}
