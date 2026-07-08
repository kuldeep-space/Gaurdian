package com.ai.guardian.ui.screens.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.guardian.viewmodel.RemoteDeviceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDeviceScreen(
    deviceUuid: String,
    deviceName: String,
    onBack: () -> Unit,
    viewModel: RemoteDeviceViewModel = viewModel()
) {
    var searchQuery by remember { mutableStateOf("") }
    val remoteApps by viewModel.remoteApps.collectAsState()
    val presence by viewModel.remotePresence.collectAsState()
    val state by viewModel.remoteState.collectAsState()
    val pendingApprovals by viewModel.pendingApprovals.collectAsState()
    val isLockCommandPending by viewModel.isLockCommandPending.collectAsState()
    
    LaunchedEffect(deviceUuid) {
        viewModel.loadDeviceData(deviceUuid)
    }
    
    val filteredApps = remember(searchQuery, remoteApps) {
        if (searchQuery.isEmpty()) remoteApps else remoteApps.filter { 
            it.appName.contains(searchQuery, ignoreCase = true) 
        }
    }
    
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CenterAlignedTopAppBar(
            title = { Text(deviceName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 1. Search Input
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search remote apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }
            
            // 2. State Dashboard
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val isOnline = presence?.get("isOnline") as? Boolean ?: false
                        val battery = presence?.get("batteryLevel") as? Long ?: 0L
                        val isRemotelyLocked = state?.get("remotelyLocked") as? Boolean ?: false
                        val accessibilityEnabled = state?.get("accessibilityEnabled") as? Boolean ?: false
                        val protectionEnabled = state?.get("protectionEnabled") as? Boolean ?: false
                        val livenessEnabled = state?.get("livenessEnabled") as? Boolean ?: false
                        val appVersion = state?.get("appVersion") as? String ?: "Unknown"
                        val configVersion = state?.get("configurationVersion") as? Long ?: 0L
                        
                        val lastSeenText = remember(presence) {
                            val lastSeenTimestamp = presence?.get("lastSeen") as? com.google.firebase.Timestamp
                            if (lastSeenTimestamp != null) {
                                try {
                                    val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault())
                                    sdf.format(lastSeenTimestamp.toDate())
                                } catch (e: Exception) {
                                    "Recent"
                                }
                            } else {
                                "Never"
                            }
                        }

                        val syncStatusText = when {
                            !isOnline -> "Offline"
                            isLockCommandPending -> "Syncing"
                            else -> "Idle"
                        }
                        
                        Text("Device Status Dashboard", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Connectivity:", fontSize = 14.sp)
                            Text(if (isOnline) "Online" else "Offline", color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Sync Status:", fontSize = 14.sp)
                            Text(syncStatusText, color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Last Successful Sync:", fontSize = 14.sp)
                            Text(lastSeenText, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Device Lock State:", fontSize = 14.sp)
                            Text(if (isRemotelyLocked) "Locked" else "Unlocked", color = if (isRemotelyLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Guardian Protection:", fontSize = 14.sp)
                            Text(if (protectionEnabled) "Locked (Active)" else "Unlocked (Inactive)", color = if (protectionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Face Protection (Liveness):", fontSize = 14.sp)
                            Text(if (livenessEnabled) "Enabled" else "Disabled", color = if (livenessEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Accessibility Service:", fontSize = 14.sp)
                            Text(if (accessibilityEnabled) "Running" else "Stopped", color = if (accessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Battery Level:", fontSize = 14.sp)
                            Text("$battery%", fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("App Version:", fontSize = 14.sp)
                            Text(appVersion, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Config Version:", fontSize = 14.sp)
                            Text("$configVersion", fontSize = 14.sp)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 1. Device Lock Button
                            Button(
                                onClick = {
                                    val command = if (isRemotelyLocked) com.ai.guardian.data.remote.models.CommandType.UNLOCK_DEVICE else com.ai.guardian.data.remote.models.CommandType.LOCK_DEVICE
                                    viewModel.sendRemoteCommand(deviceUuid, command)
                                },
                                enabled = !isLockCommandPending,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRemotelyLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                )
                            ) {
                                if (isLockCommandPending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                } else {
                                    Text(if (isRemotelyLocked) "Unlock Device" else "Lock Device", fontSize = 13.sp)
                                }
                            }
                            
                            // 2. Guardian Lock/Unlock Button
                            Button(
                                onClick = {
                                    val command = if (protectionEnabled) com.ai.guardian.data.remote.models.CommandType.DISABLE_PROTECTION else com.ai.guardian.data.remote.models.CommandType.ENABLE_PROTECTION
                                    viewModel.sendRemoteCommand(deviceUuid, command)
                                },
                                enabled = !isLockCommandPending,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (protectionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                )
                            ) {
                                if (isLockCommandPending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                } else {
                                    Text(if (protectionEnabled) "Unlock Guardian" else "Lock Guardian", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            
            // 2.5 Security PIN Management Card
            item {
                var showSetPinDialog by remember { mutableStateOf(false) }
                var showChangePinDialog by remember { mutableStateOf(false) }
                var showResetPinDialog by remember { mutableStateOf(false) }
                
                var pinInput by remember { mutableStateOf("") }
                var oldPinInput by remember { mutableStateOf("") }
                var newPinInput by remember { mutableStateOf("") }
                
                val pinConfigured = state?.get("pinConfigured") as? Boolean ?: false
                val pinVersion = state?.get("pinVersion") as? Long ?: 0L
                val pinUpdatedAt = state?.get("pinUpdatedAt") as? Long ?: 0L
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Guardian Security PIN", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (pinConfigured) "Status: Configured (Version $pinVersion)" else "Status: Not Configured",
                            color = if (pinConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        if (pinConfigured && pinUpdatedAt > 0L) {
                            val timeStr = remember(pinUpdatedAt) {
                                try {
                                    val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault())
                                    sdf.format(java.util.Date(pinUpdatedAt))
                                } catch (e: Exception) { "Recent" }
                            }
                            Text("Last Updated: $timeStr", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!pinConfigured) {
                                Button(
                                    onClick = { showSetPinDialog = true },
                                    enabled = !isLockCommandPending,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Set PIN", fontSize = 13.sp)
                                }
                            } else {
                                Button(
                                    onClick = { showChangePinDialog = true },
                                    enabled = !isLockCommandPending,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Change PIN", fontSize = 13.sp)
                                }
                                Button(
                                    onClick = { showResetPinDialog = true },
                                    enabled = !isLockCommandPending,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Reset PIN", fontSize = 13.sp)
                                }
                            }
                        }
                        
                        if (pinConfigured) {
                            Spacer(modifier = Modifier.height(8.dp))
                            var showRevealDialog by remember { mutableStateOf(false) }
                            var revealedPin by remember { mutableStateOf<String?>(null) }
                            
                            Button(
                                onClick = { 
                                    revealedPin = viewModel.decryptChildPin(state)
                                    showRevealDialog = true 
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("Reveal Synced Child PIN", fontSize = 13.sp)
                            }
                            
                            if (showRevealDialog) {
                                AlertDialog(
                                    onDismissRequest = { 
                                        showRevealDialog = false
                                        revealedPin = null 
                                    },
                                    title = { Text("Decrypted Security PIN") },
                                    text = { 
                                        Column {
                                            Text("The current decrypted PIN for the child device is:")
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = revealedPin ?: "Failed to decrypt (key mismatch or empty)",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 24.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.align(Alignment.CenterHorizontally)
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { 
                                            showRevealDialog = false
                                            revealedPin = null 
                                        }) {
                                            Text("Close")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                
                // Set PIN Dialog
                if (showSetPinDialog) {
                    AlertDialog(
                        onDismissRequest = { showSetPinDialog = false; pinInput = "" },
                        title = { Text("Set Security PIN") },
                        text = {
                            Column {
                                Text("Enter a 4-6 digit Guardian Security PIN for the child device.")
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = pinInput,
                                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                                    placeholder = { Text("Enter 4-6 digits") },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (pinInput.length in 4..6) {
                                        viewModel.sendRemoteCommand(deviceUuid, com.ai.guardian.data.remote.models.CommandType.SET_PIN, pinInput)
                                        showSetPinDialog = false
                                        pinInput = ""
                                    }
                                }
                            ) { Text("Set PIN") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSetPinDialog = false; pinInput = "" }) { Text("Cancel") }
                        }
                    )
                }
                
                // Change PIN Dialog
                if (showChangePinDialog) {
                    AlertDialog(
                        onDismissRequest = { showChangePinDialog = false; oldPinInput = ""; newPinInput = "" },
                        title = { Text("Change Security PIN") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = oldPinInput,
                                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) oldPinInput = it },
                                    label = { Text("Old PIN") },
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = newPinInput,
                                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) newPinInput = it },
                                    label = { Text("New PIN") },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (oldPinInput.length in 4..6 && newPinInput.length in 4..6) {
                                        viewModel.sendRemoteCommand(deviceUuid, com.ai.guardian.data.remote.models.CommandType.CHANGE_PIN, "$oldPinInput:$newPinInput")
                                        showChangePinDialog = false
                                        oldPinInput = ""
                                        newPinInput = ""
                                    }
                                }
                            ) { Text("Change") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showChangePinDialog = false; oldPinInput = ""; newPinInput = "" }) { Text("Cancel") }
                        }
                    )
                }
                
                // Reset PIN Dialog
                if (showResetPinDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetPinDialog = false },
                        title = { Text("Reset Security PIN") },
                        text = { Text("Are you sure you want to reset the Guardian Security PIN? This will clear the PIN on the child device.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.sendRemoteCommand(deviceUuid, com.ai.guardian.data.remote.models.CommandType.RESET_PIN)
                                    showResetPinDialog = false
                                }
                            ) { Text("Reset") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetPinDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }
            
            // 3. Pending Approvals Section
            if (pendingApprovals.isNotEmpty()) {
                item {
                    Text(
                        "Pending Approval Requests",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                
                items(pendingApprovals, key = { it.requestId }) { request ->
                    var remainingSeconds by remember(request.requestId) {
                        mutableStateOf(((request.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
                    }
                    
                    LaunchedEffect(request.expiresAt) {
                        while (remainingSeconds > 0) {
                            kotlinx.coroutines.delay(1000)
                            remainingSeconds = ((request.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        }
                    }
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        request.requestType,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        "Device: $deviceName",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "${remainingSeconds}s remaining",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (remainingSeconds < 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { viewModel.rejectRequest(deviceUuid, request.requestId) },
                                    enabled = !isLockCommandPending,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Reject")
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Button(
                                    onClick = { viewModel.approveRequest(request) },
                                    enabled = !isLockCommandPending,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Approve")
                                }
                            }
                        }
                    }
                }
            }
            
            // 4. Remote Apps Section
            if (remoteApps.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item {
                    Text(
                        "Remote Apps",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                
                items(filteredApps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(app.appName.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            
                            Spacer(Modifier.width(16.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(app.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            
                            Switch(
                                checked = app.isLocked,
                                enabled = !isLockCommandPending,
                                onCheckedChange = { locked ->
                                    viewModel.toggleAppLock(deviceUuid, app.packageName, locked)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
