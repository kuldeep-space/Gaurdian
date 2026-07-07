package com.ai.guardian.ui.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.navigation
import com.ai.guardian.ui.screens.DashboardScreen
import com.ai.guardian.ui.screens.BiometricSettingsScreen
import com.ai.guardian.ui.screens.SettingsScreen
import com.ai.guardian.ui.theme.*
import com.ai.guardian.viewmodel.MainViewModel

private data class NavTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val topLevelTabs = listOf(
    NavTab("dashboard",    "Home",     Icons.Default.Home),
    NavTab("face_security","Face",     Icons.Default.Face),
    NavTab("protected_apps","Apps",    Icons.Default.Lock),
    NavTab("settings",     "Settings", Icons.Default.Settings)
)

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val startDest = if (hasAllPermissions(context)) "main_tabs" else "permissions"

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in topLevelTabs.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    topLevelTabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        // Pop up to the start destination of the current graph (main_tabs)
                                        val currentGraph = navController.currentBackStackEntry?.destination?.parent
                                        val startDestinationId = currentGraph?.startDestinationId
                                            ?: navController.graph.findStartDestination().id

                                        popUpTo(startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState    = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier
                                )
                            },
                            label = {
                                Text(
                                    tab.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = MaterialTheme.colorScheme.primary,
                                selectedTextColor   = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor      = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = startDest,
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable("permissions") {
                com.ai.guardian.ui.screens.PermissionsScreen(
                    onAllPermissionsGranted = {
                        navController.navigate("main_tabs") {
                            popUpTo("permissions") { inclusive = true }
                        }
                    }
                )
            }
            
            navigation(startDestination = "dashboard", route = "main_tabs") {
                composable("dashboard") {
                    DashboardScreen(viewModel = viewModel, navController = navController)
                }
                composable("face_security") {
                    val faces = viewModel.enrolledFaces.collectAsState().value
                    BiometricSettingsScreen(
                        enrolledFaces    = faces,
                        onRegisterClick  = {
                            viewModel.reenrollProfileId   = null
                            viewModel.reenrollProfileName = null
                            navController.navigate("face_registration")
                        },
                        onTestClick      = { navController.navigate("face_recognition_test") },
                        onReenrollClick  = { face ->
                            viewModel.reenrollProfileId   = face.profile.id
                            viewModel.reenrollProfileName = face.profile.name
                            navController.navigate("face_registration")
                        },
                        onRenameClick    = { face, name -> viewModel.renameFace(face.profile.id, name) },
                        onColorClick     = { face, color -> viewModel.updateAvatarColor(face.profile.id, color.toArgb()) },
                        onDeleteClick    = { face -> viewModel.deleteFaceById(face.profile.id) },
                        onBack           = { navController.popBackStack() }
                    )
                }
                composable("protected_apps") {
                    com.ai.guardian.ui.screens.AppSelectionScreen(
                        viewModel = viewModel,
                        onBack    = {
                            navController.navigate("dashboard") {
                                popUpTo("dashboard") { inclusive = false }
                            }
                        }
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        viewModel         = viewModel,
                        onNavigateToLogs  = { navController.navigate("security_logs") }
                    )
                }
            }
            composable("security_logs") {
                com.ai.guardian.ui.screens.SecurityLogsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("face_registration") {
                com.ai.guardian.ui.screens.FaceRegistrationScreen(
                    viewModel             = viewModel,
                    onRegistrationSuccess = {
                        navController.navigate("face_security") {
                            popUpTo("dashboard") {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("face_recognition_test") {
                com.ai.guardian.ui.screens.FaceRecognitionTestScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun hasAllPermissions(context: Context): Boolean {
    val hasCamera  = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val hasOverlay = Settings.canDrawOverlays(context)
    var hasA11y    = false
    val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    if (enabledServices != null) {
        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val c = splitter.next()
            if (c.equals("${context.packageName}/${context.packageName}.services.GuardianAccessibilityService", ignoreCase = true) ||
                c.equals("${context.packageName}/com.ai.guardian.services.GuardianAccessibilityService", ignoreCase = true)) {
                hasA11y = true; break
            }
        }
    }
    return hasCamera && hasOverlay && hasA11y
}
