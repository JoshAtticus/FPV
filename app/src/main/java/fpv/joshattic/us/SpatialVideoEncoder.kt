package fpv.joshattic.us

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class SpatialVideoEncoder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val videoBitrate: Int,
    private val outputFile: String
) {
    private val TAG = "SpatialVideoEncoder"

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var mediaRecorder: MediaRecorder? = null
    private var inputSurface: Surface? = null

    private var leftTextureId = -1
    private var rightTextureId = -1
    var leftSurfaceTexture: SurfaceTexture? = null
    var rightSurfaceTexture: SurfaceTexture? = null
    var leftSurface: Surface? = null
    var rightSurface: Surface? = null

    private var programId = -1
    private var aPositionLocation = -1
    private var aTextureCoordLocation = -1
    private var uMVPMatrixLocation = -1
    private var uSTMatrixLocation = -1

    private val leftTransformMatrix = FloatArray(16)
    private val rightTransformMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var leftFrameAvailable = false
    private var rightFrameAvailable = false

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    // Left quad: x from -1 to 0
    private val leftVertexData = floatArrayOf(
        -1.0f, -1.0f, 0.0f,  0.0f, 0.0f,
         0.0f, -1.0f, 0.0f,  1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f,  0.0f, 1.0f,
         0.0f,  1.0f, 0.0f,  1.0f, 1.0f
    )

    // Right quad: x from 0 to 1
    private val rightVertexData = floatArrayOf(
         0.0f, -1.0f, 0.0f,  0.0f, 0.0f,
         1.0f, -1.0f, 0.0f,  1.0f, 0.0f,
         0.0f,  1.0f, 0.0f,  0.0f, 1.0f,
         1.0f,  1.0f, 0.0f,  1.0f, 1.0f
    )

    private val leftVertexBuffer: FloatBuffer
    private val rightVertexBuffer: FloatBuffer

    init {
        leftVertexBuffer = ByteBuffer.allocateDirect(leftVertexData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(leftVertexData)
        leftVertexBuffer.position(0)

        rightVertexBuffer = ByteBuffer.allocateDirect(rightVertexData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(rightVertexData)
        rightVertexBuffer.position(0)
        
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    fun prepare(onReady: () -> Unit) {
        handlerThread = HandlerThread("SpatialVideoEncoder").apply { start() }
        handler = Handler(handlerThread!!.looper)

        handler?.post {
            try {
                setupMediaRecorder()
                setupEGL()
                setupGL()
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare SpatialVideoEncoder", e)
            }
        }
    }

    private fun setupMediaRecorder() {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile)
                
                setVideoEncodingBitRate(videoBitrate)
                setAudioEncodingBitRate(256000)
                setAudioSamplingRate(48000)
                setAudioChannels(2)
                
                setVideoFrameRate(30)
                setVideoSize(width, height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                
                prepare()
                inputSurface = surface
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaRecorder", e)
            throw e
        }
    }

    private fun setupEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("unable to get EGL14 display")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)

        val attrib_list = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attrib_list, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], inputSurface, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun setupGL() {
        programId = createProgram(vertexShaderCode, fragmentShaderCode)
        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordLocation = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        uMVPMatrixLocation = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uSTMatrixLocation = GLES20.glGetUniformLocation(programId, "uSTMatrix")

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        leftTextureId = textures[0]
        rightTextureId = textures[1]

        setupTexture(leftTextureId)
        setupTexture(rightTextureId)

        leftSurfaceTexture = SurfaceTexture(leftTextureId).apply {
            setDefaultBufferSize(width / 2, height)
            setOnFrameAvailableListener {
                handler?.post {
                    try {
                        updateTexImage()
                        getTransformMatrix(leftTransformMatrix)
                        leftFrameAvailable = true
                        drawFrameIfReady()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating left texture", e)
                    }
                }
            }
        }
        leftSurface = Surface(leftSurfaceTexture)

        rightSurfaceTexture = SurfaceTexture(rightTextureId).apply {
            setDefaultBufferSize(width / 2, height)
            setOnFrameAvailableListener {
                handler?.post {
                    try {
                        updateTexImage()
                        getTransformMatrix(rightTransformMatrix)
                        rightFrameAvailable = true
                        drawFrameIfReady()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating right texture", e)
                    }
                }
            }
        }
        rightSurface = Surface(rightSurfaceTexture)
    }

    private fun setupTexture(textureId: Int) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun drawFrameIfReady() {
        if (!leftFrameAvailable || !rightFrameAvailable) return

        leftFrameAvailable = false
        rightFrameAvailable = false

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)

        // Draw Left
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, leftTextureId)
        drawQuad(leftVertexBuffer, leftTransformMatrix)

        // Draw Right
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, rightTextureId)
        drawQuad(rightVertexBuffer, rightTransformMatrix)

        val timestamp = leftSurfaceTexture?.timestamp ?: 0L
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawQuad(vertexBuffer: FloatBuffer, transformMatrix: FloatArray) {
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLocation, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLocation)

        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTextureCoordLocation)

        GLES20.glUniformMatrix4fv(uMVPMatrixLocation, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixLocation, 1, false, transformMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun start() {
        handler?.post {
            mediaRecorder?.start()
        }
    }

    fun stop(onStopped: () -> Unit) {
        handler?.post {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaRecorder", e)
            }
            mediaRecorder?.release()
            mediaRecorder = null

            leftSurface?.release()
            rightSurface?.release()
            leftSurfaceTexture?.release()
            rightSurfaceTexture?.release()

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE

            handlerThread?.quitSafely()
            
            Handler(android.os.Looper.getMainLooper()).post {
                onStopped()
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }
}
