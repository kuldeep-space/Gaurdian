package com.ai.guardian.ui.screens.device

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ai.guardian.viewmodel.DeviceViewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDeviceScreen(
    deviceViewModel: DeviceViewModel,
    mainViewModel: com.ai.guardian.viewmodel.MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("This Device", "Connect Device")

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CenterAlignedTopAppBar(
            title = { Text("Device Management", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = {
                    deviceViewModel.stopPairingMode()
                    onBack()
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        TabRow(selectedTabIndex = selectedTabIndex, containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        if (selectedTabIndex == 0) {
            ThisDeviceTab(deviceViewModel, mainViewModel)
        } else {
            ConnectDeviceTab(deviceViewModel, onPairSuccess = {
                Toast.makeText(context, "Device Paired!", Toast.LENGTH_SHORT).show()
                onBack()
            })
        }
    }
}

@Composable
fun ThisDeviceTab(viewModel: DeviceViewModel, mainViewModel: com.ai.guardian.viewmodel.MainViewModel) {
    val context = LocalContext.current
    val uuid = viewModel.syncManager.deviceUuid
    val deviceName = android.os.Build.MODEL ?: "Unknown Device"
    
    var pairCode by remember { mutableStateOf(viewModel.syncManager.pairCode) }
    val isPairing by viewModel.isPairingModeActive.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.startPairingMode()
    }
    
    LaunchedEffect(isPairing) {
        if (isPairing) {
            pairCode = viewModel.syncManager.pairCode
        }
    }
    
    val qrContent = JSONObject().apply {
        put("uuid", uuid)
        put("name", deviceName)
        put("key", pairCode)
    }.toString()
    
    val qrBitmap = remember(qrContent) { QrUtils.generateQrCode(qrContent, 600) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Scan this QR code from another device to pair it.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        
        Spacer(Modifier.height(32.dp))
        
        OutlinedTextField(
            value = deviceName,
            onValueChange = {},
            label = { Text("Device Name") },
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = uuid,
            onValueChange = {},
            label = { Text("Device UUID") },
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pairCode,
            onValueChange = {},
            label = { Text("Pairing Code") },
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        
        val settings by mainViewModel.settings.collectAsState()
        
        if (!settings.isPinConfigured) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Initial Security PIN", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Set a 4-6 digit Guardian PIN for this device. The Parent device will sync this state upon pairing.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    
                    var newPin by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) newPin = it },
                        label = { Text("Enter 4-6 digits") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { mainViewModel.setInitialChildPin(newPin, context) },
                        enabled = newPin.length in 4..6,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save PIN")
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Guardian Security PIN", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Configured \u2714", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("The PIN is active. Connect from Parent device to manage it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        Button(
            onClick = { pairCode = viewModel.regenerateMyPairCode() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Regenerate Pair Code")
        }
    }
}

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun ConnectDeviceTab(viewModel: DeviceViewModel, onPairSuccess: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember { 
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) 
    }
    
    var showScanner by remember { mutableStateOf(false) }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (granted) showScanner = true
    }
    
    var manualUuid by remember { mutableStateOf("") }
    var manualCode by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(true) }
    
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!showScanner) {
            Button(
                onClick = { 
                    if (hasCameraPermission) {
                        showScanner = true
                        isScanning = true
                    } else {
                        launcher.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open QR Scanner")
            }
        } else if (hasCameraPermission && isScanning) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setTargetResolution(android.util.Size(640, 480))
                                .build()
                                
                             imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                 val mediaImage = imageProxy.image
                                 android.util.Log.v("BarcodeScanner", "Analyzer called. mediaImage is null: ${mediaImage == null}, isScanning: $isScanning")
                                 if (mediaImage != null && isScanning) {
                                     val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                     try {
                                         scanner.process(image)
                                             .addOnSuccessListener { barcodes ->
                                                 android.util.Log.d("BarcodeScanner", "Successfully scanned ${barcodes.size} barcodes.")
                                                 for (barcode in barcodes) {
                                                     val rawValue = barcode.rawValue
                                                     android.util.Log.d("BarcodeScanner", "Barcode detected: $rawValue")
                                                     if (rawValue != null && rawValue.contains("uuid") && rawValue.contains("key")) {
                                                         try {
                                                             val json = JSONObject(rawValue)
                                                             val u = json.optString("uuid")
                                                             val n = json.optString("name")
                                                             val k = json.optString("key")
                                                             if (u.isNotEmpty() && k.isNotEmpty()) {
                                                                 android.util.Log.d("BarcodeScanner", "Valid pairing QR detected: uuid=$u, name=$n")
                                                                 isScanning = false
                                                                 viewModel.pairNewDevice(u, n, k) { success, msg ->
                                                                     if (success) {
                                                                         onPairSuccess()
                                                                     } else {
                                                                         Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                                         isScanning = true // Resume
                                                                     }
                                                                 }
                                                             }
                                                         } catch (e: Exception) {
                                                             android.util.Log.e("BarcodeScanner", "Error parsing QR json", e)
                                                         }
                                                     }
                                                 }
                                             }
                                             .addOnFailureListener { e ->
                                                 android.util.Log.e("BarcodeScanner", "ML Kit barcode processing failed", e)
                                             }
                                             .addOnCompleteListener {
                                                 imageProxy.close()
                                             }
                                     } catch (e: Exception) {
                                         android.util.Log.e("BarcodeScanner", "Failed to process scanner framework call", e)
                                         imageProxy.close()
                                     }
                                 } else {
                                     imageProxy.close()
                                 }
                             }
                            
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e("Camera", "Use case binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (!hasCameraPermission) {
            Box(Modifier.size(240.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text("Camera permission required")
            }
        } else {
            Box(Modifier.size(240.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text("Processing...")
            }
        }
        
        Spacer(Modifier.height(32.dp))
        Text("OR ENTER MANUALLY", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(16.dp))
        
        OutlinedTextField(
            value = manualUuid,
            onValueChange = { manualUuid = it },
            label = { Text("Device UUID") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = manualCode,
            onValueChange = { manualCode = it },
            label = { Text("Pairing Code") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        
        Button(
            onClick = {
                viewModel.pairNewDevice(manualUuid, manualName.takeIf { it.isNotBlank() } ?: "Manual Device", manualCode) { success, msg ->
                    if (success) onPairSuccess() else Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = manualUuid.isNotBlank() && manualCode.isNotBlank()
        ) {
            Text("Connect")
        }
    }
}
