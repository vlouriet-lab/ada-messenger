package com.ada.messenger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private const val TAG = "QrScannerScreen"

/**
 * Full-screen QR code scanner.
 *
 * Requests CAMERA permission if not yet granted.
 * Calls [onResult] with the raw QR string and exits as soon as one is detected.
 * [onClose] is triggered by the X button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
    promptText: String = "Наведите камеру на QR-код контакта",
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Camera permission result: granted=$granted")
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        Log.d(TAG, "QrScannerScreen launched, hasCameraPermission=$hasCameraPermission")
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (promptText == "Наведите камеру на QR-код контакта") "Сканировать QR контакт" else "Сканировать QR ПК") },
                navigationIcon = {
                    IconButton(onClick = {
                        Log.d(TAG, "QrScanner closed by user")
                        onClose()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (hasCameraPermission) {
                CameraPreviewWithScanner(onResult = onResult)
                // Overlay hint
                Text(
                    text = promptText,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Нет разрешения на камеру.\nОткройте настройки приложения и предоставьте доступ.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text("Запросить разрешение")
                    }
                }
            }
        }
    }
}

@androidx.camera.core.ExperimentalGetImage
@Composable
private fun CameraPreviewWithScanner(onResult: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var hasScanned by remember { mutableStateOf(false) }

    // Keep a reference to the provider so we can unbindAll() on dispose or scan success.
    // This prevents CameraDeviceClient buffer errors when the composable is removed from
    // the tree while the camera is still active.
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "CameraPreviewWithScanner disposing — unbinding camera + shutting down executor")
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                Log.d(TAG, "CameraProvider ready, binding preview + analysis")

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val scanner = BarcodeScanning.getClient(options)

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (hasScanned) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(
                            mediaImage, imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val value = barcodes.firstOrNull()?.rawValue
                                // K5: Do not log scanned content — may contain peer_id / public key.
                                Log.d(TAG, "ML Kit scan result: barcodes=${barcodes.size}, valid=${!value.isNullOrBlank()}")
                                if (!hasScanned && !value.isNullOrBlank()) {
                                    hasScanned = true
                                    // Unbind camera immediately so buffers are released
                                    // BEFORE calling onResult (which triggers navigation).
                                    Log.i(TAG, "QR scanned successfully — unbinding camera")
                                    provider.unbindAll()
                                    onResult(value)
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.w(TAG, "ML Kit scan failed: ${e.message}")
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                    Log.d(TAG, "Camera bound to lifecycle")
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind failed: ${e.message}", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}
