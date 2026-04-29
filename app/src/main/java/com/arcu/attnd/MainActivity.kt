package com.arcu.attnd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.arcu.attnd.data.*
import com.arcu.attnd.data.prefs.DevicePrefs
import com.arcu.attnd.ui.screens.*
import com.arcu.attnd.ui.theme.AttndTheme
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch

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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
    val activity = context as? MainActivity
    val devicePrefs = remember { DevicePrefs(context) }
    val deviceId = remember { devicePrefs.getDeviceId() }
    
    val navController = rememberNavController()
    var currentEvent by remember { mutableStateOf<Event?>(null) }
    var recenterTrigger by remember { mutableIntStateOf(0) }
    var locationEnabled by remember { mutableStateOf(value = false) }
    var isLoadingInitialEvent by remember { mutableStateOf(true) }
    var initialRoute by remember { mutableStateOf(Screen.Launch.route) }

    val handleEventJoin: suspend (String) -> Boolean = { code ->
        try {
            val event = NetworkModule.api.lookupEvent(code)
            val joinResult = NetworkModule.api.joinEvent(event.id, deviceId, JoinRequest())
            if (joinResult.status == "ok") {
                devicePrefs.saveEventCode(event.eventCode)
                currentEvent = event
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    LaunchedEffect(Unit) {
        devicePrefs.getEventCode()?.let { storedCode ->
            if (handleEventJoin(storedCode)) {
                initialRoute = Screen.Map.route
            } else {
                devicePrefs.clearEventCode()
            }
        }
        isLoadingInitialEvent = false
    }

    // Listen for deep links
    LaunchedEffect(activity?.intent) {
        val intent = activity?.intent
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            val data = intent.data
            if ((data?.host == "attnd.app") && (data.pathSegments.getOrNull(0) == "join")) {
                val code = data.pathSegments.getOrNull(1)
                if (code != null) {
                    if (handleEventJoin(code)) {
                        navController.navigate(Screen.Map.route) {
                            popUpTo(Screen.Launch.route) { inclusive = true }
                        }
                    }
                }
            }
        }
    }

    if (isLoadingInitialEvent) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(navController = navController, startDestination = initialRoute) {
        composable(Screen.Launch.route) {
            LaunchScreen(
                deviceId = deviceId,
                onEventJoined = { event ->
                    devicePrefs.saveEventCode(event.eventCode)
                    currentEvent = event
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Launch.route) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Screen.Map.route) {
            var locations by remember { mutableStateOf<List<EventLocation>>(emptyList()) }
            val eventCenter = remember(currentEvent) {
                currentEvent?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0)
            }
            val cameraPositionState = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom(eventCenter, 15f)
            }

            LaunchedEffect(currentEvent?.id) {
                currentEvent?.id?.let { id ->
                    try {
                        val response = NetworkModule.api.getLocations(id)
                        locations = response.results
                    } catch (_: Exception) {}
                }
            }

            AppScaffold(
                navController = navController,
                onTabReselected = { screen ->
                    if (screen == Screen.Map) {
                        recenterTrigger++
                    }
                }
            ) { innerPadding ->
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainMapScreen(
                        locations = locations,
                        eventCenter = eventCenter,
                        recenterTrigger = recenterTrigger,
                        locationEnabled = locationEnabled,
                        onLocationEnabled = { locationEnabled = true },
                        contentPadding = innerPadding,
                        cameraPositionState = cameraPositionState,
                        onLocationClick = { locId ->
                            navController.navigate("location_detail/$locId")
                        }
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
            var isRefreshing by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var lastRefreshedAt by remember { mutableStateOf<Long?>(null) }
            val scope = rememberCoroutineScope()

            fun fetchNotifications(refresh: Boolean = false) {
                val eventId = currentEvent?.id ?: return
                if (refresh) isRefreshing = true else isLoading = true
                scope.launch {
                    try {
                        val response = NetworkModule.api.getNotifications(eventId)
                        notifications = response.results
                        lastRefreshedAt = System.currentTimeMillis()
                        error = null
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        isLoading = false
                        isRefreshing = false
                    }
                }
            }

            LaunchedEffect(currentEvent?.id) {
                fetchNotifications()
            }

            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    NotificationsScreen(
                        notifications = notifications,
                        isLoading = isLoading,
                        isRefreshing = isRefreshing,
                        error = error,
                        onRefresh = { fetchNotifications(refresh = true) },
                        lastRefreshedAt = lastRefreshedAt
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
                            devicePrefs.clearEventCode()
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
    onTabReselected: (Screen) -> Unit = {},
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
            BottomNavigationBar(navController, items, onTabReselected)
        }
    ) { innerPadding ->
        content(innerPadding)
    }
}

@Composable
private fun BottomNavigationBar(
    navController: androidx.navigation.NavHostController,
    items: List<Screen>,
    onTabReselected: (Screen) -> Unit
) {
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        items.forEach { screen ->
            val isSelected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
            NavigationBarItem(
                icon = { Icon(screen.icon!!, contentDescription = null) },
                label = { Text(screen.label) },
                selected = isSelected,
                onClick = {
                    if (isSelected) {
                        onTabReselected(screen)
                    } else {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppScaffoldPreview() {
    AttndTheme {
        AppScaffold(navController = rememberNavController()) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                Text("Content Area")
            }
        }
    }
}
