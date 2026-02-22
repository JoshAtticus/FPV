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
    AVATAR("Avatar", Icons.Default.Person)
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
    var selectedMode by remember { mutableStateOf(CameraMode.PHOTO) }
    var currentCameraId by remember { mutableStateOf("50") } 
    var isRecording by remember { mutableStateOf(false) }

    // Settings logic
    var showSettings by remember { mutableStateOf(false) }
    var selectedResolution by remember { mutableStateOf<Size?>(null) }
    var availableResolutions by remember { mutableStateOf(emptyList<Size>()) }
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
    LaunchedEffect(currentCameraId, selectedMode) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = cameraManager.getCameraCharacteristics(currentCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val sizes = if (selectedMode == CameraMode.VIDEO) {
                    map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
                } else {
                    map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
                }
                // Sort by area descending
                val sortedSizes = sizes.sortedByDescending { it.width * it.height }
                availableResolutions = sortedSizes
                if (selectedResolution == null || !sortedSizes.contains(selectedResolution)) {
                    selectedResolution = sortedSizes.firstOrNull()
                }
            } else {
                availableResolutions = emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching resolutions", e)
            availableResolutions = emptyList()
        }
    }

    // Lens ID Logic
    LaunchedEffect(selectedMode) {
        if (selectedMode == CameraMode.AVATAR) {
            currentCameraId = "1"
        } else {
            if (currentCameraId == "1") {
                currentCameraId = "50"
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
            
            CameraMode.entries.forEach { mode ->
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
                            tint = if (selectedMode == mode) Color.Yellow else Color.White
                        ) 
                    },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = Color.Yellow,
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
                    Text(
                        text = if (selectedMode == CameraMode.VIDEO) "Video Mode" else "Photo Mode",
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
        SettingsDialog(
            currentResolution = selectedResolution,
            availableResolutions = availableResolutions,
            onResolutionSelected = { 
                selectedResolution = it
                performHaptic()
            },
            onDismiss = { showSettings = false }
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

@Composable
fun SettingsDialog(
    currentResolution: Size?,
    availableResolutions: List<Size>,
    onResolutionSelected: (Size) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text(
                    text = "Resolution",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (availableResolutions.isEmpty()) {
                    Text("No resolutions found.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    availableResolutions.forEach { size ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onResolutionSelected(size) } 
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (currentResolution == size),
                                onClick = { onResolutionSelected(size) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "${size.width}x${size.height}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

class Camera2Controller(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    
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
        
        val cameraId = currentCameraId ?: return
        val resolution = currentResolution ?: return
        
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
            } else if (currentMode == CameraMode.VIDEO) {
                setupMediaRecorder(resolution)
                surfaces.add(mediaRecorder!!.surface)
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
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HorizonFPV")
            }
        }
        
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        val pfd = uri?.let { context.contentResolver.openFileDescriptor(it, "w") }
        
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (pfd != null) {
                setOutputFile(pfd.fileDescriptor)
            }
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(resolution.width, resolution.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    fun takePicture() {
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

    fun startRecording() {
        if (cameraDevice == null || !textureView!!.isAvailable || currentResolution == null) return
        
        try {
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
            
            Handler(context.mainLooper).post {
                Toast.makeText(context, "Video saved", Toast.LENGTH_SHORT).show()
            }
            
            // Restart preview
            startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
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