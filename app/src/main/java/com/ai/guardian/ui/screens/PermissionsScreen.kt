package com.ai.guardian.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.guardian.ui.theme.*

@Composable
fun PermissionsScreen(onAllPermissionsGranted: () -> Unit) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCamera      by remember { mutableStateOf(checkCameraPermission(context)) }
    var hasOverlay     by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasA11y        by remember { mutableStateOf(checkA11yPermission(context)) }
    var showA11yDisclosure by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasA11y    = checkA11yPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasCamera, hasOverlay, hasA11y) {
        if (hasCamera && hasOverlay && hasA11y) onAllPermissionsGranted()
    }

    if (showA11yDisclosure) {
        AlertDialog(
            onDismissRequest = { showA11yDisclosure = false },
            title = { Text("Accessibility Service Required", fontWeight = FontWeight.SemiBold) },
            text = { Text("Guardian AI requires Accessibility Service permission to detect when you launch a protected application. This allows Guardian AI to instantly lock the screen and verify your face before granting access to the app.\n\nWe do not use this permission to read your screen content, collect personal data, or monitor your general device usage. The permission is strictly used to monitor app launches for security purposes.") },
            confirmButton = {
                TextButton(onClick = {
                    showA11yDisclosure = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("I Understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { showA11yDisclosure = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PhonelinkSetup, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Setup Guardian", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, lineHeight = 32.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Grant the following permissions to start protecting your device.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                PermissionItem(
                    title       = "Camera Access",
                    description = "Required to scan and verify your face.",
                    isGranted   = hasCamera,
                    icon        = Icons.Default.CameraAlt,
                    onGrant     = { cameraLauncher.launch(Manifest.permission.CAMERA) }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
                PermissionItem(
                    title       = "Display Overlay",
                    description = "Required to show the lock screen over apps.",
                    isGranted   = hasOverlay,
                    icon        = Icons.Default.Layers,
                    onGrant     = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
                PermissionItem(
                    title       = "Accessibility Service",
                    description = "Required to detect when protected apps are opened.",
                    isGranted   = hasA11y,
                    icon        = Icons.Default.Accessibility,
                    onGrant     = { showA11yDisclosure = true }
                )
            }
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: ImageVector,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isGranted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (isGranted) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                } else {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, lineHeight = 20.sp)
                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (!isGranted) {
            Button(
                onClick    = onGrant,
                shape      = RoundedCornerShape(8.dp),
                colors     = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Grant", color = MaterialTheme.colorScheme.background, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text("Done", fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 4.dp))
        }
    }
}

private fun checkCameraPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun checkA11yPermission(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val splitter = android.text.TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    while (splitter.hasNext()) {
        val c = splitter.next()
        if (c.equals("${ctx.packageName}/${ctx.packageName}.services.GuardianAccessibilityService", ignoreCase = true) ||
            c.equals("${ctx.packageName}/com.ai.guardian.services.GuardianAccessibilityService", ignoreCase = true)) return true
    }
    return false
}
