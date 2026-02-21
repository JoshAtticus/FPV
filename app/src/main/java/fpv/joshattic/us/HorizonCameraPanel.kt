package fpv.joshattic.us

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "HorizonCameraApp"
private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

@Composable
fun HorizonCameraPanel() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // Camera Executor
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // --- Permissions Handling ---
    val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    var hasPermissions by remember {
        mutableStateOf(
            permissionsToRequest.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms ->
            hasPermissions = perms.values.all { it }
        }
    )

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            launcher.launch(permissionsToRequest.toTypedArray())
        }
    }

    if (!hasPermissions) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("Camera and Audio permissions are required.", color = Color.White)
        }
        return
    }

    // --- State ---
    var selectedMode by remember { mutableStateOf(CameraMode.PHOTO) }
    // Default to Left Eye (50) for Passthrough
    var currentCameraId by remember { mutableStateOf("50") } 
    var isRecording by remember { mutableStateOf(false) }
    var isTimeLapseRunning by remember { mutableStateOf(false) }
    
    // Camera Use Cases
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var videoCapture: VideoCapture<Recorder>? by remember { mutableStateOf(null) }
    var recording: Recording? by remember { mutableStateOf(null) }

    // Lens ID Logic
    LaunchedEffect(selectedMode) {
        if (selectedMode == CameraMode.AVATAR) {
            // Avatar is exclusively ID 1
            currentCameraId = "1"
        } else {
            // If switching AWAY from Avatar, default back to Left Eye (50) if we were on Avatar (1)
            // If we were already on 50 or 51, stay there.
            if (currentCameraId == "1") {
                currentCameraId = "50"
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // --- Navigation Rail ---
        NavigationRail(
            modifier = Modifier.width(96.dp),
            containerColor = Color(0xFF1C1C1E),
            contentColor = Color.White
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            
            CameraMode.entries.forEach { mode ->
                NavigationRailItem(
                    selected = (selectedMode == mode),
                    onClick = {
                        if (!isRecording && !isTimeLapseRunning) {
                            selectedMode = mode
                        } 
                    },
                    label = { 
                        Text(
                            mode.label, 
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selectedMode == mode) FontWeight.Bold else FontWeight.Normal
                        ) 
                    },
                    icon = { Icon(mode.icon, contentDescription = mode.label) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = Color(0xFF333333),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    ),
                    enabled = !isRecording && !isTimeLapseRunning
                )
            }
        }

        // --- Camera Preview & Controls ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF121212))
        ) {
            CameraPreviewWithMessage(
                cameraId = currentCameraId,
                mode = selectedMode,
                onImageCaptureInit = { imageCapture = it },
                onVideoCaptureInit = { videoCapture = it },
                cameraExecutor = cameraExecutor
            )
            
            // --- Overlay Controls ---
            
            // Lens Switcher (Only if NOT Avatar mode)
            if (selectedMode != CameraMode.AVATAR) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color(0x80000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("50", "51").forEach { id ->
                        val isSelected = currentCameraId == id
                        Text(
                            text = if (id == "50") "L" else "R",
                            color = if (isSelected) Color.Yellow else Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { currentCameraId = id }
                                .padding(8.dp)
                        )
                    }
                }
            }

            // Shutter Button Area
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 32.dp)
            ) {
                ShutterButton(
                    mode = selectedMode,
                    isRecording = isRecording,
                    isTimeLapseRunning = isTimeLapseRunning,
                    onClick = {
                        when (selectedMode) {
                            CameraMode.PHOTO -> capturePhoto(context, imageCapture, cameraExecutor)
                            CameraMode.VIDEO -> {
                                if (isRecording) {
                                    recording?.stop()
                                    isRecording = false
                                } else {
                                    val newRecording = captureVideo(context, videoCapture, cameraExecutor) { event ->
                                        if (event is VideoRecordEvent.Finalize) {
                                            isRecording = false
                                        }
                                    }
                                    // captureVideo returns null if videoCapture is null
                                    if (newRecording != null) {
                                        recording = newRecording
                                        isRecording = true
                                    }
                                }
                            }
                            CameraMode.TIME_LAPSE -> {
                                isTimeLapseRunning = !isTimeLapseRunning
                                if (isTimeLapseRunning) {
                                    scope.launch {
                                        while (isTimeLapseRunning) {
                                            capturePhoto(context, imageCapture, cameraExecutor, isTimeLapse = true)
                                            delay(2000) // 2 sec interval
                                        }
                                    }
                                }
                            }
                            CameraMode.PANORAMA -> {
                                capturePhoto(context, imageCapture, cameraExecutor)
                                Toast.makeText(context, "Panorama (Standard Capture)", Toast.LENGTH_SHORT).show()
                            }
                            CameraMode.AVATAR -> {
                                // Avatar mode can take photos too
                                capturePhoto(context, imageCapture, cameraExecutor)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ShutterButton(
    mode: CameraMode,
    isRecording: Boolean,
    isTimeLapseRunning: Boolean,
    onClick: () -> Unit
) {
    val outerColor = if (isRecording || isTimeLapseRunning) Color.Red else Color.White
    val innerColor = if (isRecording || isTimeLapseRunning) Color.Red else Color.White
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .clickable(onClick = onClick)
    ) {
        // Ring
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(4.dp, outerColor, CircleShape)
        )
        // Inner Circle
        Box(
            modifier = Modifier
                .size(if (isRecording) 40.dp else 60.dp)
                .clip(if (isRecording) RoundedCornerShape(4.dp) else CircleShape)
                .background(innerColor)
        )
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreviewWithMessage(
    cameraId: String,
    mode: CameraMode,
    onImageCaptureInit: (ImageCapture) -> Unit,
    onVideoCaptureInit: (VideoCapture<Recorder>) -> Unit,
    cameraExecutor: ExecutorService
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Fix for Avatar camera being inverted
                // Using ID 1 for Avatar
                if (cameraId == "1") {
                    scaleY = -1f
                } else {
                    scaleY = 1f
                }
            },
        factory = { ctx ->
            PreviewView(ctx).apply {
                // Use COMPATIBLE implementation mode to avoid surface issues on some devices
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Filter logic to find EXACT camera ID string
                val customCameraSelector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        cameraInfos.filter {
                            val info = Camera2CameraInfo.from(it)
                            info.cameraId == cameraId
                        }
                    }
                    .build()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                
                // Determine use case based on mode
                val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
                
                if (mode == CameraMode.VIDEO) {
                     val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build()
                    val videoCap = VideoCapture.withOutput(recorder)
                    useCases.add(videoCap)
                    onVideoCaptureInit(videoCap)
                } else {
                    // Default to ImageCapture for Photo, TimeLapse, Panorama, Avatar
                    val imageCap = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    useCases.add(imageCap)
                    onImageCaptureInit(imageCap)
                }

                try {
                    cameraProvider.unbindAll()
                    if (cameraProvider.hasCamera(customCameraSelector)) {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            customCameraSelector,
                            *useCases.toTypedArray()
                        )
                    } else {
                        Log.e(TAG, "Camera ID $cameraId not found.")
                        Toast.makeText(context, "Camera ID $cameraId unavailable", Toast.LENGTH_SHORT).show()
                    }
                } catch (exc: Exception) {
                    Log.e(TAG, "Use case binding failed", exc)
                }

            }, ContextCompat.getMainExecutor(context))
        }
    )
}

// --- Actions ---

fun capturePhoto(context: Context, imageCapture: ImageCapture?, executor: ExecutorService, isTimeLapse: Boolean = false) {
    val imageCapture = imageCapture ?: return

    val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HorizonFPV")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val msg = "Photo capture succeeded: ${output.savedUri}"
                if (!isTimeLapse) {
                    // Toast on main thread
                    ContextCompat.getMainExecutor(context).execute {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                Log.d(TAG, msg)
            }
        }
    )
}

fun captureVideo(
    context: Context, 
    videoCapture: VideoCapture<Recorder>?, 
    executor: ExecutorService,
    listener: (VideoRecordEvent) -> Unit
): Recording? {
    val videoCapture = videoCapture ?: return null
    val recording = videoCapture.output
        .prepareRecording(context, MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).build())
        .apply {
            if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
                withAudioEnabled()
            }
        }
        .start(ContextCompat.getMainExecutor(context), listener)
        
    return recording
}

// --- Data Models ---

enum class CameraMode(val label: String, val icon: ImageVector) {
    PHOTO("Photo", Icons.Default.CameraAlt),
    VIDEO("Video", Icons.Default.Videocam),
    TIME_LAPSE("Time Lapse", Icons.Default.Timer),
    AVATAR("Avatar", Icons.Default.Person),
    PANORAMA("Panorama", Icons.Default.Landscape)
}