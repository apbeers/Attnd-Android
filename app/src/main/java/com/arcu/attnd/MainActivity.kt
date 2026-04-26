package com.arcu.attnd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.arcu.attnd.ui.screens.*
import com.arcu.attnd.ui.theme.AttndTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
}

@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    var currentEventId by remember { mutableStateOf<Int?>(null) }

    NavHost(navController = navController, startDestination = Screen.Launch.route) {
        composable(Screen.Launch.route) {
            LaunchScreen(onEventSelected = { id ->
                currentEventId = id
                navController.navigate(Screen.Map.route) {
                    popUpTo(Screen.Launch.route) { inclusive = true }
                }
            })
        }
        
        composable(Screen.Map.route) {
            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    MainMapScreen(currentEventId ?: 0)
                }
            }
        }
        
        composable(Screen.Locations.route) {
            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    LocationsScreen(currentEventId ?: 0, onLocationClick = { locId ->
                        navController.navigate("location_detail/$locId")
                    })
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
                    LocationDetailScreen(id)
                }
            }
        }

        composable(Screen.Flyer.route) {
            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    FlyerScreen(null) // Mocking null for now
                }
            }
        }

        composable(Screen.Notifications.route) {
            AppScaffold(navController) { innerPadding ->
                Surface(modifier = Modifier.padding(innerPadding)) {
                    NotificationsScreen(currentEventId ?: 0)
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
        Screen.Notifications
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
