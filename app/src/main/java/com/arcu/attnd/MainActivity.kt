package com.arcu.attnd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.arcu.attnd.data.AppNotification
import com.arcu.attnd.data.Event
import com.arcu.attnd.data.EventLocation
import com.arcu.attnd.data.NetworkModule
import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.ui.screens.*
import com.arcu.attnd.ui.theme.AttndTheme
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.rememberCameraPositionState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AttndTheme {
                MainNavigation()
            }
        }
    }
}

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    object Launch : Screen("launch", "Launch")
    object Map : Screen("map", "Map", Icons.Default.Place)
    object Locations : Screen("locations", "Locations", Icons.AutoMirrored.Filled.List)
    object LocationDetail : Screen("location_detail/{id}", "Detail")
    object Flyer : Screen("flyer", "Flyer", Icons.Default.Info)
    object Notifications : Screen("notifications", "Notifications", Icons.Default.Notifications)
    object Options : Screen("options", "Options", Icons.Default.Settings)
}

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val devicePrefs = remember { DevicePrefs(context) }
    val deviceId = remember { devicePrefs.getDeviceId() }
    
    val navController = rememberNavController()
    var currentEvent by remember { mutableStateOf<Event?>(null) }
    var locationEnabled by remember { mutableStateOf(false) }

    NavHost(navController = navController, startDestination = Screen.Launch.route) {
        composable(Screen.Launch.route) {
            LaunchScreen(
                deviceId = deviceId,
                onEventJoined = { event ->
                    currentEvent = event
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Launch.route) { inclusive = true }
                    }
                },
                onLocationEnabled = { locationEnabled = true }
            )
        }
        
        composable(Screen.Map.route) {
            var locations by remember { mutableStateOf<List<EventLocation>>(emptyList()) }
            val cameraPositionState = rememberCameraPositionState {
                val initialPos = currentEvent?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(32.754695, -97.331605)
                position = CameraPosition.fromLatLngZoom(initialPos, 15f)
            }

            LaunchedEffect(currentEvent?.id) {
                currentEvent?.id?.let { id ->
                    try {
                        val response = NetworkModule.api.getLocations(id)
                        locations = response.results
                    } catch (_: Exception) {}
                }
            }

            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainMapScreen(
                        locations = locations,
                        locationEnabled = locationEnabled,
                        onLocationEnabled = { locationEnabled = true },
                        contentPadding = innerPadding,
                        cameraPositionState = cameraPositionState
                    )
                }
            }
        }
        
        composable(Screen.Locations.route) {
            var locations by remember { mutableStateOf<List<EventLocation>>(emptyList()) }
            var isLoading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(currentEvent?.id) {
                currentEvent?.id?.let { id ->
                    try {
                        val response = NetworkModule.api.getLocations(id)
                        locations = response.results
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        isLoading = false
                    }
                }
            }

            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    LocationsScreen(
                        locations = locations,
                        isLoading = isLoading,
                        error = error,
                        onLocationClick = { locId ->
                            navController.navigate("location_detail/$locId")
                        }
                    )
                }
            }
        }

        composable(
            route = Screen.LocationDetail.route,
            arguments = listOf(navArgument("id") { type = NavType.IntType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getInt("id") ?: 0
            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    LocationDetailScreen(currentEvent?.id ?: 0, id)
                }
            }
        }

        composable(Screen.Flyer.route) {
            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    FlyerScreen(currentEvent?.flyerUrl)
                }
            }
        }

        composable(Screen.Notifications.route) {
            var notifications by remember { mutableStateOf<List<AppNotification>>(emptyList()) }
            var isLoading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(currentEvent?.id) {
                currentEvent?.id?.let { id ->
                    try {
                        val response = NetworkModule.api.getNotifications(id)
                        notifications = response.results
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        isLoading = false
                    }
                }
            }

            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    NotificationsScreen(
                        notifications = notifications,
                        isLoading = isLoading,
                        error = error
                    )
                }
            }
        }

        composable(Screen.Options.route) {
            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    OptionsScreen(
                        eventId = currentEvent?.id ?: 0,
                        deviceId = deviceId,
                        locationEnabled = locationEnabled,
                        onLocationChanged = { locationEnabled = it },
                        onLeaveEvent = {
                            currentEvent = null
                            navController.navigate(Screen.Launch.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = true
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AppScaffold(
    navController: androidx.navigation.NavHostController,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val items = listOf(
        Screen.Map,
        Screen.Locations,
        Screen.Flyer,
        Screen.Notifications,
        Screen.Options
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon!!, contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        content(innerPadding)
    }
}
