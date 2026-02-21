package fpv.joshattic.us

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CameraDiagnosticsTest {

    private val TAG = "CameraDiagnostics"

    @Test
    fun listAllCamerasAndCapabilities() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager

        // 1. Check System Features
        Log.d(TAG, "=== SYSTEM FEATURES ===")
        val hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasFront = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        val hasConcurrent = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
        
        Log.d(TAG, "Has Camera (Any): $hasCamera")
        Log.d(TAG, "Has Front Camera: $hasFront")
        Log.d(TAG, "Has Concurrent Camera Support: $hasConcurrent")

        // 2. Initialise CameraX
        Log.d(TAG, "=== CAMERAX DIAGNOSTICS ===")
        val cameraProvider = try {
            val future = ProcessCameraProvider.getInstance(context)
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CameraX", e)
            fail("CameraX initialization failed: ${e.message}")
            return@runBlocking
        }

        val cameraInfos = cameraProvider.availableCameraInfos
        Log.d(TAG, "Found ${cameraInfos.size} accessible cameras via CameraX")

        cameraInfos.forEachIndexed { index, cameraInfo ->
            val selector = CameraSelector.Builder().addCameraFilter { listOf(cameraInfo) }.build()
            
            // CameraX info
            val lensFacing = when(cameraInfo.lensFacing) {
                CameraSelector.LENS_FACING_FRONT -> "FRONT"
                CameraSelector.LENS_FACING_BACK -> "BACK"
                CameraSelector.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }
            
            // Initial log without experimental API
            Log.d(TAG, "Camera Index: $index | Lens Facing: $lensFacing | Exposure Supported: ${cameraInfo.exposureState.isExposureCompensationSupported}")

            // Camera2 Info (Experimental)
            logCamera2Details(cameraInfo)
        }
        
        // 3. Native CameraManager Diagnostics (Lower Level)
        Log.d(TAG, "=== NATIVE CAMERA MANAGER DIAGNOSTICS ===")
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "Native CameraManager found IDs: ${cameraIds.joinToString()}")
            
            cameraIds.forEach { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = when(chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                
                // Get Physical Cameras if logical
                val physicalIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    chars.physicalCameraIds.toString()
                } else {
                    "N/A"
                }

                val resolutionMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val outputSizes = resolutionMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                val maxRes = outputSizes?.maxByOrNull { it.width * it.height }
                
                Log.d(TAG, "ID: $id | Type: $facing | Physical IDs: $physicalIds | Max Res: $maxRes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect CameraManager", e)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun logCamera2Details(cameraInfo: androidx.camera.core.CameraInfo) {
        try {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val id = camera2Info.cameraId
            val level = camera2Info.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            
            val levelString = when(level) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "UNKNOWN ($level)"
            }

            Log.d(TAG, "   -> ID: $id (Camera2 API)")
            Log.d(TAG, "   -> Hardware Level: $levelString")
            
        } catch (e: Exception) {
            Log.e(TAG, "   -> Failed to extract Camera2 details", e)
        }
    }
}
