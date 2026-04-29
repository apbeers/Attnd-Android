@file:OptIn(ExperimentalMaterial3Api::class)

package com.arcu.attnd.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.arcu.attnd.data.Event
import com.arcu.attnd.data.JoinRequest
import com.arcu.attnd.data.NearbyEvent
import com.arcu.attnd.data.NetworkModule
import com.arcu.attnd.ui.theme.AttndTheme
import com.google.android.gms.location.LocationServices
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
@Composable
fun LaunchScreen(
    deviceId: String,
    onEventJoined: (Event) -> Unit,
    onLocationEnabled: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var eventCode by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(value = false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoadingNearby by remember { mutableStateOf(false) }
    var nearbyEvents by remember { mutableStateOf<List<NearbyEvent>>(emptyList()) }
    var nearbyError by remember { mutableStateOf<String?>(null) }
    
    var showScanner by remember { mutableStateOf(false) }

    fun joinEvent(code: String) {
        isSearching = true
        errorMessage = null
        scope.launch {
            try {
                val event = NetworkModule.api.lookupEvent(code)
                NetworkModule.api.joinEvent(event.id, deviceId, JoinRequest())
                onEventJoined(event)
            } catch (e: HttpException) {
                errorMessage = when (e.code()) {
                    401 -> "Authentication failed (401). API key may not be configured."
                    404 -> "Event \"$code\" not found. Check the code and try again."
                    else -> "Server error (${e.code()}). Please try again."
                }
            } catch (_: IOException) {
                errorMessage = "Network error. Check your connection and try again."
            } catch (e: Exception) {
                errorMessage = "Unexpected error: ${e.javaClass.simpleName}"
            } finally {
                isSearching = false
            }
        }
    }

    fun loadNearbyEvents() {
        scope.launch {
            isLoadingNearby = true
            nearbyError = null
            try {
                val location = fusedClient.lastLocation.await()
                if (location != null) {
                    val response = NetworkModule.api.getNearbyEvents(location.latitude, location.longitude)
                    nearbyEvents = response.results
                } else {
                    nearbyError = "Unable to determine your location. Please move around and try again."
                }
            } catch (e: Exception) {
                nearbyError = "Could not load nearby events: ${e.message}"
            } finally {
                isLoadingNearby = false
            }
        }
    }

    // Auto-load nearby events if permission is already granted
    LaunchedEffect(Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            onLocationEnabled()
            loadNearbyEvents()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { perms ->
        val granted = (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        if (granted) {
            onLocationEnabled()
            loadNearbyEvents()
        } else {
            nearbyError = "Location permission is needed to find nearby events."
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showScanner = true
        } else {
            errorMessage = "Camera permission is required to scan QR codes."
        }
    }

    val onFindNearbyTapped = {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            onLocationEnabled()
            loadNearbyEvents()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (showScanner) {
        QRScannerDialog(
            onDismiss = { showScanner = false },
        ) { code ->
            showScanner = false
            joinEvent(code)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Welcome to Attnd", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        // Manual code entry
        OutlinedTextField(
            value = eventCode,
            onValueChange = { eventCode = it },
            label = { Text("Enter Event Code") },
            modifier = Modifier.fillMaxWidth(),
            isError = errorMessage != null,
            enabled = !isSearching,
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            showScanner = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code")
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (eventCode.isNotBlank()) {
                    joinEvent(eventCode)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSearching && eventCode.isNotBlank()
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Join Event")
            }
        }
        if (errorMessage != null) {
            Text(
                errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Divider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                "  OR  ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        // Find Nearby Events
        Button(
            onClick = onFindNearbyTapped,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoadingNearby
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Find Nearby Events")
        }

        if (nearbyError != null) {
            Text(
                nearbyError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (isLoadingNearby) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        if (nearbyEvents.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Nearby Events", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(nearbyEvents) { nearby ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                try {
                                    NetworkModule.api.joinEvent(nearby.event.id, deviceId, JoinRequest())
                                    onEventJoined(nearby.event)
                                } catch (_: Exception) {}
                            }
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                nearby.event.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                String.format(Locale.US, "%.1f mi away", nearby.distanceMiles),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (nearby.event.address.isNotEmpty()) {
                                Text(
                                    nearby.event.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QRScannerDialog(onDismiss: () -> Unit, onCodeScanned: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan QR Code") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                QRScannerView(onCodeScanned = onCodeScanned)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun QRScannerView(onCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        // Preview mode placeholder
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("Camera Preview Placeholder")
        }
        return
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
// ...

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val barcodeScanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                )

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    @SuppressLint("UnsafeOptInUsageError")
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        barcodeScanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    val rawValue = barcode.rawValue
                                    if (rawValue != null) {
                                        // Support both raw code and URL
                                        val code = if (rawValue.contains("attnd.app/join/")) {
                                            rawValue.substringAfter("attnd.app/join/").substringBefore("/")
                                        } else {
                                            rawValue
                                        }
                                        onCodeScanned(code)
                                    }
                                }
                            }
                            .addOnCompleteListener {
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
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@ComposePreview(showBackground = true)
@Composable
fun LaunchScreenPreview() {
    AttndTheme {
        LaunchScreen(deviceId = "test-id", onEventJoined = {})
    }
}

@ComposePreview(showBackground = true)
@Composable
fun QRScannerDialogPreview() {
    AttndTheme {
        QRScannerDialog(onDismiss = {}, onCodeScanned = {})
    }
}
