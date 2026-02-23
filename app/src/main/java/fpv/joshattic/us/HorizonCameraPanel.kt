package fpv.joshattic.us

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "HorizonCameraApp"
private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

enum class CameraMode(val label: String, val icon: ImageVector) {
    PHOTO("Photo", Icons.Default.CameraAlt),
    VIDEO("Video", Icons.Default.Videocam),
    AVATAR("Avatar", Icons.Default.Person),
    SPATIAL("Spatial", Icons.Default.ViewInAr)
}

@Composable
fun HorizonCameraPanel() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
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
    val settings = remember { AppSettings(context) }
    
    val allowedModes = remember {
        val model = Build.MODEL
        if (model.contains("Quest 3")) {
            CameraMode.entries
        } else if (model.contains("Quest")) {
            listOf(CameraMode.AVATAR)
        } else {
            emptyList()
        }
    }

    LaunchedEffect(allowedModes) {
        if (allowedModes.isEmpty()) {
            Toast.makeText(context, "Device not supported", Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            (context as? android.app.Activity)?.finish()
        }
    }

    if (allowedModes.isEmpty()) {
        return
    }

    var selectedMode by remember { 
        mutableStateOf(
            if (settings.rememberMode && allowedModes.contains(try { CameraMode.valueOf(settings.lastMode) } catch (e: Exception) { CameraMode.PHOTO })) {
                try { CameraMode.valueOf(settings.lastMode) } catch (e: Exception) { allowedModes.first() }
            } else {
                allowedModes.first()
            }
        ) 
    }
    var currentCameraId by remember { mutableStateOf(if (selectedMode == CameraMode.AVATAR) "1" else settings.defaultCamera) } 
    var isRecording by remember { mutableStateOf(false) }
    var isSpatialVideo by remember { mutableStateOf(false) }

    // Settings logic
    var showSettings by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf<Size?>(null) }
    var availableVideoResolutions by remember { mutableStateOf(emptyList<Size>()) }
    var availablePhotoResolutions by remember { mutableStateOf(emptyList<Size>()) }
    var recordingDurationMillis by remember { mutableLongStateOf(0L) }

    // Helpers
    val sound = remember { MediaActionSound() }
    val vibrator = context.getSystemService(Vibrator::class.java)
    val performHaptic = remember {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        }
    }

    DisposableEffect(Unit) {
        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
        onDispose {
            sound.release()
        }
    }

    // Timer logic
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (isRecording) {
                recordingDurationMillis = System.currentTimeMillis() - startTime
                delay(100)
            }
        } else {
            recordingDurationMillis = 0L
        }
    }

    // Fetch Resolutions using Camera2
    LaunchedEffect(currentCameraId, selectedMode, showSettings) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val videoSizes = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
                val photoSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
                
                availableVideoResolutions = videoSizes.sortedByDescending { it.width * it.height }
                availablePhotoResolutions = photoSizes.sortedByDescending { it.width * it.height }
                
                val sortedSizes = if (selectedMode == CameraMode.VIDEO) availableVideoResolutions else availablePhotoResolutions
                
                if (selectedMode == CameraMode.AVATAR) {
                    selectedResolution = sortedSizes.firstOrNull()
                } else if (selectedMode == CameraMode.SPATIAL) {
                    // Force 16:9 max resolution for Spatial mode
                    val filtered = filterResolutionsByAspectRatio(sortedSizes, "16:9")
                    selectedResolution = filtered.firstOrNull() ?: sortedSizes.firstOrNull()
                } else {
                    val aspectRatio = if (selectedMode == CameraMode.VIDEO) settings.videoAspectRatio else settings.photoAspectRatio
                    val filtered = filterResolutionsByAspectRatio(sortedSizes, aspectRatio)
                    val index = if (selectedMode == CameraMode.VIDEO) settings.videoResolutionIndex else settings.photoResolutionIndex
                    selectedResolution = filtered.getOrNull(index) ?: filtered.firstOrNull() ?: sortedSizes.firstOrNull()
                }
            } else {
                availableVideoResolutions = emptyList()
                availablePhotoResolutions = emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching resolutions", e)
            availableVideoResolutions = emptyList()
            availablePhotoResolutions = emptyList()
        }
    }

    // Lens ID Logic
    LaunchedEffect(selectedMode) {
        settings.lastMode = selectedMode.name
        if (selectedMode == CameraMode.AVATAR) {
            currentCameraId = "1"
        } else {
            if (currentCameraId == "1") {
                currentCameraId = settings.defaultCamera
            }
        }
    }

    // Camera Controller
    val cameraController = remember { Camera2Controller(context) }
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                cameraController.closeCamera()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                if (selectedResolution != null) {
                    cameraController.openCamera(currentCameraId, selectedResolution!!, selectedMode)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraController.closeCamera()
        }
    }

    LaunchedEffect(currentCameraId, selectedResolution, selectedMode) {
        if (selectedResolution != null) {
            cameraController.openCamera(currentCameraId, selectedResolution!!, selectedMode)
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
            
            allowedModes.forEach { mode ->
                NavigationRailItem(
                    selected = (selectedMode == mode),
                    onClick = {
                        if (!isRecording) {
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
                    icon = { 
                        Icon(
                            mode.icon, 
                            contentDescription = mode.label,
                            tint = if (selectedMode == mode) Color(0xFFA5D6A7) else Color.White
                        ) 
                    },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = Color(0xFFA5D6A7),
                        unselectedIconColor = Color.White,
                        indicatorColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // --- Main Camera Area ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .clipToBounds() // Prevent camera preview from bleeding over the sidebar
        ) {
            // Camera Preview
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1f
                        scaleY = 1f
                    },
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = cameraController.surfaceTextureListener
                        cameraController.textureView = this
                    }
                }
            )

            // Top Center: Recording Timer
            if (isRecording) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                        .background(Color(0x80000000), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color.Red, CircleShape)
                    )
                    val seconds = (recordingDurationMillis / 1000) % 60
                    val minutes = (recordingDurationMillis / 1000) / 60
                    Text(
                        text = String.format(Locale.US, "%02d:%02d", minutes, seconds),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Top Right: Lens Switcher
            if (selectedMode != CameraMode.AVATAR && selectedMode != CameraMode.SPATIAL) {
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
                            color = if (isSelected) Color(0xFFA5D6A7) else Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { 
                                    if (!isRecording) {
                                        performHaptic()
                                        currentCameraId = id 
                                    }
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }

            // Bottom Left: Settings & Stats
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = { 
                    if (!isRecording) {
                        performHaptic()
                        showSettings = true 
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                Column {
                    val modeText = when {
                        selectedMode == CameraMode.VIDEO -> "Video Mode"
                        selectedMode == CameraMode.SPATIAL && isSpatialVideo -> "Spatial Video"
                        selectedMode == CameraMode.SPATIAL && !isSpatialVideo -> "Spatial Photo"
                        selectedMode == CameraMode.AVATAR -> "Avatar Mode"
                        else -> "Photo Mode"
                    }
                    Text(
                        text = modeText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (selectedResolution != null) {
                        Text(
                            text = "${selectedResolution!!.width}x${selectedResolution!!.height}", 
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Bottom Center: Spatial Photo/Video Toggle
            if (selectedMode == CameraMode.SPATIAL) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .background(Color(0xFF333333), RoundedCornerShape(50)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val photoBg = if (!isSpatialVideo) Color(0xFFA5D6A7) else Color.Transparent
                    val videoBg = if (isSpatialVideo) Color(0xFFA5D6A7) else Color.Transparent
                    val photoIconTint = if (!isSpatialVideo) Color(0xFF1C1C1E) else Color.White
                    val videoIconTint = if (isSpatialVideo) Color(0xFF1C1C1E) else Color.White

                    Box(
                        modifier = Modifier
                            .size(64.dp, 48.dp)
                            .clip(RoundedCornerShape(50))
                            .background(photoBg)
                            .clickable { if (!isRecording) isSpatialVideo = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Spatial Photo", tint = photoIconTint)
                    }
                    Box(
                        modifier = Modifier
                            .size(64.dp, 48.dp)
                            .clip(RoundedCornerShape(50))
                            .background(videoBg)
                            .clickable { if (!isRecording) isSpatialVideo = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = "Spatial Video", tint = videoIconTint)
                    }
                }
            }

            // Right Center: Shutter Button
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 32.dp)
            ) {
                ShutterButton(
                    mode = selectedMode,
                    isRecording = isRecording,
                    onClick = {
                        performHaptic()
                        when (selectedMode) {
                            CameraMode.PHOTO, CameraMode.AVATAR -> {
                                sound.play(MediaActionSound.SHUTTER_CLICK)
                                cameraController.takePicture()
                            }
                            CameraMode.SPATIAL -> {
                                if (isSpatialVideo) {
                                    if (isRecording) {
                                        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                                        cameraController.stopSpatialRecording()
                                        isRecording = false
                                    } else {
                                        sound.play(MediaActionSound.START_VIDEO_RECORDING)
                                        cameraController.startSpatialRecording()
                                        isRecording = true
                                    }
                                } else {
                                    sound.play(MediaActionSound.SHUTTER_CLICK)
                                    cameraController.takePicture()
                                }
                            }
                            CameraMode.VIDEO -> {
                                if (isRecording) {
                                    sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                                    cameraController.stopRecording()
                                    isRecording = false
                                } else {
                                    sound.play(MediaActionSound.START_VIDEO_RECORDING)
                                    cameraController.startRecording()
                                    isRecording = true
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            onDismiss = { showSettings = false },
            availableVideoResolutions = availableVideoResolutions,
            availablePhotoResolutions = availablePhotoResolutions,
            onSettingsChanged = {
                // Trigger a re-evaluation of the selected resolution
                val sortedSizes = if (selectedMode == CameraMode.VIDEO) availableVideoResolutions else availablePhotoResolutions
                if (selectedMode != CameraMode.AVATAR) {
                    val aspectRatio = if (selectedMode == CameraMode.VIDEO) settings.videoAspectRatio else settings.photoAspectRatio
                    val filtered = filterResolutionsByAspectRatio(sortedSizes, aspectRatio)
                    val index = if (selectedMode == CameraMode.VIDEO) settings.videoResolutionIndex else settings.photoResolutionIndex
                    selectedResolution = filtered.getOrNull(index) ?: filtered.firstOrNull() ?: sortedSizes.firstOrNull()
                }
            }
        )
    }
}

@Composable
fun ShutterButton(
    mode: CameraMode,
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val outerColor = if (isRecording) Color.Red else Color.White
    val innerColor = if (isRecording) Color.Red else Color.White
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(4.dp, outerColor, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(if (isRecording) 40.dp else 60.dp)
                .clip(if (isRecording) RoundedCornerShape(4.dp) else CircleShape)
                .background(innerColor)
        )
    }
}



class Camera2Controller(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    
    // Dual camera state for SPATIAL mode
    private var cameraDeviceLeft: CameraDevice? = null
    private var cameraDeviceRight: CameraDevice? = null
    private var captureSessionLeft: CameraCaptureSession? = null
    private var captureSessionRight: CameraCaptureSession? = null
    private var imageReaderLeft: ImageReader? = null
    private var imageReaderRight: ImageReader? = null
    
    private var mediaRecorder: MediaRecorder? = null
    private var spatialVideoEncoder: SpatialVideoEncoder? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    var textureView: TextureView? = null
    private var currentCameraId: String? = null
    private var currentResolution: Size? = null
    private var currentMode: CameraMode = CameraMode.PHOTO
    
    private var isRecordingVideo = false
    private var nextVideoAbsolutePath: String? = null

    val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
            openCameraInternal()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val textureView = textureView ?: return
        val resolution = currentResolution ?: return
        val cameraId = currentCameraId ?: return

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation

        val surfaceRotation = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val isFrontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val sign = if (isFrontFacing) 1 else -1
        val rotationToApply = (sensorOrientation + sign * surfaceRotation + 360) % 360

        val isSwapped = rotationToApply == 90 || rotationToApply == 270
        val rotatedWidth = if (isSwapped) resolution.height else resolution.width
        val rotatedHeight = if (isSwapped) resolution.width else resolution.height

        val matrix = android.graphics.Matrix()
        
        // 1. Undo TextureView's implicit scaling to get a 1:1 mapping
        matrix.setScale(
            resolution.width.toFloat() / viewWidth.toFloat(),
            resolution.height.toFloat() / viewHeight.toFloat(),
            viewWidth / 2f,
            viewHeight / 2f
        )

        // 2. Apply rotation
        if (rotationToApply != 0) {
            matrix.postRotate(rotationToApply.toFloat(), viewWidth / 2f, viewHeight / 2f)
        }

        // 3. Scale to fill the view (CENTER_CROP)
        val scale = Math.max(
            viewWidth.toFloat() / rotatedWidth.toFloat(),
            viewHeight.toFloat() / rotatedHeight.toFloat()
        )
        matrix.postScale(scale, scale, viewWidth / 2f, viewHeight / 2f)

        textureView.setTransform(matrix)
    }

    fun openCamera(cameraId: String, resolution: Size, mode: CameraMode) {
        currentCameraId = cameraId
        currentResolution = resolution
        currentMode = mode
        if (textureView?.isAvailable == true) {
            configureTransform(textureView!!.width, textureView!!.height)
            openCameraInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal() {
        closeCamera()
        startBackgroundThread()
        
        val resolution = currentResolution ?: return
        
        if (currentMode == CameraMode.SPATIAL) {
            openDualCameras()
            return
        }
        
        val cameraId = currentCameraId ?: return
        
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openDualCameras() {
        var leftOpened = false
        var rightOpened = false

        val checkBothOpened = {
            if (leftOpened && rightOpened) {
                startDualPreview()
            }
        }

        try {
            cameraManager.openCamera("50", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDeviceLeft = camera
                    leftOpened = true
                    checkBothOpened()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDeviceLeft = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDeviceLeft = null
                }
            }, backgroundHandler)

            cameraManager.openCamera("51", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDeviceRight = camera
                    rightOpened = true
                    checkBothOpened()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDeviceRight = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDeviceRight = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open dual cameras", e)
        }
    }

    private fun startDualPreview() {
        val cameraLeft = cameraDeviceLeft ?: return
        val cameraRight = cameraDeviceRight ?: return
        val texture = textureView?.surfaceTexture ?: return
        val resolution = currentResolution ?: return

        texture.setDefaultBufferSize(resolution.width, resolution.height)
        val surface = Surface(texture)

        try {
            // Setup Left Camera
            val surfacesLeft = mutableListOf<Surface>(surface)
            val builderLeft = cameraLeft.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builderLeft.addTarget(surface)

            imageReaderLeft = ImageReader.newInstance(resolution.width, resolution.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    image?.let {
                        handleSpatialImage(it, isLeft = true)
                    }
                }, backgroundHandler)
            }
            surfacesLeft.add(imageReaderLeft!!.surface)

            cameraLeft.createCaptureSession(surfacesLeft, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSessionLeft = session
                    try {
                        session.setRepeatingRequest(builderLeft.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start left camera preview", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure left camera session")
                }
            }, backgroundHandler)

            // Setup Right Camera (No preview surface, just ImageReader)
            val surfacesRight = mutableListOf<Surface>()
            val builderRight = cameraRight.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            imageReaderRight = ImageReader.newInstance(resolution.width, resolution.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    image?.let {
                        handleSpatialImage(it, isLeft = false)
                    }
                }, backgroundHandler)
            }
            surfacesRight.add(imageReaderRight!!.surface)
            builderRight.addTarget(imageReaderRight!!.surface)

            cameraRight.createCaptureSession(surfacesRight, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSessionRight = session
                    try {
                        session.setRepeatingRequest(builderRight.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start right camera preview", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure right camera session")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start dual preview", e)
        }
    }

    private var pendingLeftImage: android.media.Image? = null
    private var pendingRightImage: android.media.Image? = null
    private val spatialImageLock = Object()

    private fun handleSpatialImage(image: android.media.Image, isLeft: Boolean) {
        synchronized(spatialImageLock) {
            if (isLeft) {
                pendingLeftImage?.close()
                pendingLeftImage = image
            } else {
                pendingRightImage?.close()
                pendingRightImage = image
            }

            if (pendingLeftImage != null && pendingRightImage != null) {
                val left = pendingLeftImage!!
                val right = pendingRightImage!!
                pendingLeftImage = null
                pendingRightImage = null
                
                // Process images on a background thread to avoid blocking the ImageReader
                backgroundHandler?.post {
                    processAndSaveSpatialImage(left, right)
                }
            }
        }
    }

    private fun processAndSaveSpatialImage(leftImage: android.media.Image, rightImage: android.media.Image) {
        try {
            val leftBitmap = imageToBitmap(leftImage)
            val rightBitmap = imageToBitmap(rightImage)
            
            leftImage.close()
            rightImage.close()

            if (leftBitmap == null || rightBitmap == null) {
                Log.e(TAG, "Failed to convert images to bitmaps")
                return
            }

            // Create SBS Bitmap
            val sbsWidth = leftBitmap.width + rightBitmap.width
            val sbsHeight = Math.max(leftBitmap.height, rightBitmap.height)
            val sbsBitmap = android.graphics.Bitmap.createBitmap(sbsWidth, sbsHeight, android.graphics.Bitmap.Config.ARGB_8888)
            
            val canvas = android.graphics.Canvas(sbsBitmap)
            canvas.drawBitmap(leftBitmap, 0f, 0f, null)
            canvas.drawBitmap(rightBitmap, leftBitmap.width.toFloat(), 0f, null)

            leftBitmap.recycle()
            rightBitmap.recycle()

            // Save to MediaStore
            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + "_3D_LR"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HorizonFPV")
                }
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    sbsBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                }
                Handler(context.mainLooper).post {
                    Toast.makeText(context, "Spatial Photo saved", Toast.LENGTH_SHORT).show()
                }
            }
            sbsBitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing spatial image", e)
            leftImage.close()
            rightImage.close()
        }
    }

    private fun imageToBitmap(image: android.media.Image): android.graphics.Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val texture = textureView?.surfaceTexture ?: return
        val resolution = currentResolution ?: return
        
        texture.setDefaultBufferSize(resolution.width, resolution.height)
        val surface = Surface(texture)
        
        try {
            val surfaces = mutableListOf<Surface>(surface)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            
            if (currentMode == CameraMode.PHOTO || currentMode == CameraMode.AVATAR) {
                imageReader = ImageReader.newInstance(resolution.width, resolution.height, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage()
                        image?.let {
                            saveImage(it)
                            it.close()
                        }
                    }, backgroundHandler)
                }
                surfaces.add(imageReader!!.surface)
            }

            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start camera preview", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure camera session")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview", e)
        }
    }

    private fun setupMediaRecorder(resolution: Size) {
        val settings = AppSettings(context)
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val tempFile = java.io.File(context.cacheDir, "$name.mp4")
        nextVideoAbsolutePath = tempFile.absolutePath
        
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(nextVideoAbsolutePath)
            
            if (currentMode == CameraMode.AVATAR) {
                setVideoEncodingBitRate(14000000)
                setAudioEncodingBitRate(256000)
                setAudioSamplingRate(48000)
                setAudioChannels(2)
                setVideoFrameRate(30)
            } else {
                setVideoEncodingBitRate(settings.videoBitrate * 1000000)
                setAudioEncodingBitRate(settings.audioBitrate)
                setAudioSamplingRate(settings.audioSampleRate)
                setAudioChannels(settings.audioChannels)
                setVideoFrameRate(settings.videoFps)
            }
            
            setVideoSize(resolution.width, resolution.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    fun takePicture() {
        if (currentMode == CameraMode.SPATIAL) {
            takeSpatialPicture()
            return
        }
        
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            session.capture(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take picture", e)
        }
    }

    private fun takeSpatialPicture() {
        val cameraLeft = cameraDeviceLeft ?: return
        val cameraRight = cameraDeviceRight ?: return
        val sessionLeft = captureSessionLeft ?: return
        val sessionRight = captureSessionRight ?: return
        val readerLeft = imageReaderLeft ?: return
        val readerRight = imageReaderRight ?: return

        try {
            val builderLeft = cameraLeft.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builderLeft.addTarget(readerLeft.surface)
            sessionLeft.capture(builderLeft.build(), null, backgroundHandler)

            val builderRight = cameraRight.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builderRight.addTarget(readerRight.surface)
            sessionRight.capture(builderRight.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take spatial picture", e)
        }
    }

    private fun saveImage(image: android.media.Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HorizonFPV")
            }
        }
        
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                out.write(bytes)
            }
            Handler(context.mainLooper).post {
                Toast.makeText(context, "Photo saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startSpatialRecording() {
        val cameraLeft = cameraDeviceLeft ?: return
        val cameraRight = cameraDeviceRight ?: return
        val resolution = currentResolution ?: return
        val texture = textureView?.surfaceTexture ?: return

        val settings = AppSettings(context)
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + "_3D_LR"
        val tempFile = java.io.File(context.cacheDir, "$name.mp4")
        nextVideoAbsolutePath = tempFile.absolutePath

        // The final video width is 2x the resolution width
        val sbsWidth = resolution.width * 2
        val sbsHeight = resolution.height

        spatialVideoEncoder = SpatialVideoEncoder(
            context,
            sbsWidth,
            sbsHeight,
            settings.videoBitrate * 1000000,
            settings.videoFps,
            nextVideoAbsolutePath!!
        )

        spatialVideoEncoder?.prepare {
            backgroundHandler?.post {
                try {
                    // Setup Left Camera
                    val builderLeft = cameraLeft.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    val surfacesLeft = mutableListOf<Surface>()
                    
                    texture.setDefaultBufferSize(resolution.width, resolution.height)
                    val previewSurface = Surface(texture)
                    surfacesLeft.add(previewSurface)
                    builderLeft.addTarget(previewSurface)

                    val leftEncoderSurface = spatialVideoEncoder!!.leftSurface!!
                    surfacesLeft.add(leftEncoderSurface)
                    builderLeft.addTarget(leftEncoderSurface)

                    cameraLeft.createCaptureSession(surfacesLeft, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSessionLeft = session
                            try {
                                session.setRepeatingRequest(builderLeft.build(), null, backgroundHandler)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start left camera recording", e)
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure left camera recording session")
                        }
                    }, backgroundHandler)

                    // Setup Right Camera
                    val builderRight = cameraRight.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    val surfacesRight = mutableListOf<Surface>()

                    val rightEncoderSurface = spatialVideoEncoder!!.rightSurface!!
                    surfacesRight.add(rightEncoderSurface)
                    builderRight.addTarget(rightEncoderSurface)

                    cameraRight.createCaptureSession(surfacesRight, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSessionRight = session
                            try {
                                session.setRepeatingRequest(builderRight.build(), null, backgroundHandler)
                                spatialVideoEncoder?.start()
                                isRecordingVideo = true
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start right camera recording", e)
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure right camera recording session")
                        }
                    }, backgroundHandler)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start spatial recording", e)
                }
            }
        }
    }

    fun stopSpatialRecording() {
        if (!isRecordingVideo) return
        isRecordingVideo = false
        try {
            captureSessionLeft?.stopRepeating()
            captureSessionLeft?.abortCaptures()
            captureSessionRight?.stopRepeating()
            captureSessionRight?.abortCaptures()
            
            val videoPathToSave = nextVideoAbsolutePath
            nextVideoAbsolutePath = null
            
            spatialVideoEncoder?.stop {
                saveVideoToMediaStore(videoPathToSave)
                // Restart preview
                startDualPreview()
            }
            spatialVideoEncoder = null
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop spatial recording", e)
        }
    }

    fun startRecording() {
        if (cameraDevice == null || !textureView!!.isAvailable || currentResolution == null) return
        
        try {
            setupMediaRecorder(currentResolution!!)
            
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val surfaces = mutableListOf<Surface>()
            
            val texture = textureView!!.surfaceTexture!!
            texture.setDefaultBufferSize(currentResolution!!.width, currentResolution!!.height)
            val previewSurface = Surface(texture)
            surfaces.add(previewSurface)
            builder.addTarget(previewSurface)
            
            val recorderSurface = mediaRecorder!!.surface
            surfaces.add(recorderSurface)
            builder.addTarget(recorderSurface)
            
            cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        mediaRecorder?.start()
                        isRecordingVideo = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start recording", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure recording session")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    fun stopRecording() {
        if (!isRecordingVideo) return
        isRecordingVideo = false
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            
            saveVideoToMediaStore(nextVideoAbsolutePath)
            nextVideoAbsolutePath = null
            
            // Restart preview
            startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
        }
    }

    private fun saveVideoToMediaStore(videoPath: String?) {
        if (videoPath == null) return
        val file = java.io.File(videoPath)
        if (!file.exists()) return

        val name = file.name
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HorizonFPV")
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    java.io.FileInputStream(file).use { input ->
                        input.copyTo(out)
                    }
                }
                file.delete()
                Handler(context.mainLooper).post {
                    Toast.makeText(context, "Video saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save video to MediaStore", e)
            }
        }
    }

    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            
            // Close dual cameras
            captureSessionLeft?.close()
            captureSessionLeft = null
            cameraDeviceLeft?.close()
            cameraDeviceLeft = null
            imageReaderLeft?.close()
            imageReaderLeft = null
            
            captureSessionRight?.close()
            captureSessionRight = null
            cameraDeviceRight?.close()
            cameraDeviceRight = null
            imageReaderRight?.close()
            imageReaderRight = null
            
            spatialVideoEncoder?.stop {}
            spatialVideoEncoder = null
            
            mediaRecorder?.release()
            mediaRecorder = null
            stopBackgroundThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
}