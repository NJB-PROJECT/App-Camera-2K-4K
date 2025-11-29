package com.example.supercamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.ContentValues
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.supercamera.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Camera2 variables
    private var cameraId: String = ""
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var captureRequest: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null // For DNG

    // Video variables
    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingVideo = false
    private var videoSize: Size? = null

    // Background Thread
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // State
    private var isFlashSupported = false
    private var sensorOrientation = 0
    private var isFrontCamera = false
    private var isRawSupported = false
    private var activeArraySize: Rect? = null

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.btnCapture.setOnClickListener { takePicture() }
        binding.btnRecord.setOnClickListener {
            if (isRecordingVideo) stopRecordingVideo() else startRecordingVideo()
        }
        binding.btnSwitch.setOnClickListener { switchCamera() }

        binding.sliderFocus.addOnChangeListener { _, value, _ ->
            updateProSettings(focusDistance = value)
        }
        binding.sliderIso.addOnChangeListener { _, value, _ ->
            updateProSettings(iso = value.toInt())
        }

        binding.btnResetAuto.setOnClickListener { resetToAuto() }

        binding.texture.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                touchToFocus(event.x, event.y, view.width, view.height)
                view.performClick()
            }
            true
        }
    }

    private fun resetToAuto() {
        if (cameraDevice == null || captureRequest == null || cameraCaptureSession == null) return
        try {
            captureRequest?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureRequest?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureRequest?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            captureRequest?.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            captureRequest?.set(CaptureRequest.CONTROL_AE_REGIONS, null)

            cameraCaptureSession?.setRepeatingRequest(captureRequest!!.build(), null, backgroundHandler)
            runOnUiThread {
                Toast.makeText(this, "Reset to Auto Focus & Exposure", Toast.LENGTH_SHORT).show()
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun touchToFocus(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (cameraDevice == null || activeArraySize == null) return

        val rect = calculateTapArea(x, y, viewWidth, viewHeight)
        val meteringRect = MeteringRectangle(rect, 1000)

        try {
            captureRequest?.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            captureRequest?.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            captureRequest?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            captureRequest?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            captureRequest?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            cameraCaptureSession?.capture(captureRequest!!.build(), null, backgroundHandler)

            // Reset triggers for repeating request
            captureRequest?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            captureRequest?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            cameraCaptureSession?.setRepeatingRequest(captureRequest!!.build(), null, backgroundHandler)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateTapArea(x: Float, y: Float, width: Int, height: Int): Rect {
        val sensorRect = activeArraySize ?: return Rect(0,0,0,0)

        // Simple mapping (Assuming aspect ratio matches or is close enough for simple tap)
        // A robust implementation requires full matrix transformation matching configureTransform
        // Here we do a simplified relative mapping for speed
        val relX = x / width
        val relY = y / height

        // Handle rotation roughly (Assuming 90 deg landscape sensor on portrait)
        // Note: This needs to match the sensor orientation logic perfectly

        val sensorW = sensorRect.width()
        val sensorH = sensorRect.height()

        val newX = (relY * sensorW).toInt()
        val newY = ((1.0 - relX) * sensorH).toInt()

        val halfSize = 150
        val rect = Rect(
             (newX - halfSize).coerceIn(0, sensorW),
             (newY - halfSize).coerceIn(0, sensorH),
             (newX + halfSize).coerceIn(0, sensorW),
             (newY + halfSize).coerceIn(0, sensorH)
        )
        return rect
    }

    private fun updateProSettings(focusDistance: Float? = null, iso: Int? = null) {
        if (cameraDevice == null || captureRequest == null || cameraCaptureSession == null) return

        try {
            // Setup Auto Focus to Off for manual control
            captureRequest?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)

            if (focusDistance != null) {
                captureRequest?.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }

            if (iso != null) {
                captureRequest?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                captureRequest?.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                // Need to set exposure time if AE is OFF. Default to something safe or keep current.
                // For simplicity, let's set a fixed exposure time or calculate logic.
                // Setting 1/50s (20ms) = 20000000ns roughly as a default
                captureRequest?.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 20000000L)
            }

            cameraCaptureSession?.setRepeatingRequest(captureRequest!!.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (binding.texture.isAvailable) {
            openCamera(binding.texture.width, binding.texture.height)
        } else {
            binding.texture.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun requestCameraPermissions() {
         val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
             permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // Add others if needed
        requestPermissions(permissions.toTypedArray(), 101)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (binding.texture.isAvailable) {
                    openCamera(binding.texture.width, binding.texture.height)
                }
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun switchCamera() {
        isFrontCamera = !isFrontCamera
        closeCamera()
        if (binding.texture.isAvailable) {
            openCamera(binding.texture.width, binding.texture.height)
        } else {
            binding.texture.surfaceTextureListener = surfaceTextureListener
        }
    }

    private fun openCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermissions()
            return
        }
        setUpCameraOutputs(width, height)
        configureTransform(width, height)
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            cameraOpenCloseLock.release()
            e.printStackTrace()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != cameraCaptureSession) {
                cameraCaptureSession!!.close()
                cameraCaptureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice!!.close()
                cameraDevice = null
            }
            if (null != imageReader) {
                imageReader!!.close()
                imageReader = null
            }
            if (null != mediaRecorder) {
                mediaRecorder!!.release()
                mediaRecorder = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun setUpCameraOutputs(width: Int, height: Int) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (isFrontCamera && facing == CameraCharacteristics.LENS_FACING_BACK) continue
                if (!isFrontCamera && facing == CameraCharacteristics.LENS_FACING_FRONT) continue

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                // RAW Support Check
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                isRawSupported = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

                // Find the highest resolution available for JPEG
                val largest = Collections.max(
                    Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)),
                    CompareSizesByArea()
                )

                // Setup ImageReader for JPEG
                imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2)
                imageReader?.setOnImageAvailableListener({ reader ->
                    backgroundHandler?.post {
                         val image = reader.acquireNextImage()
                         val buffer = image.planes[0].buffer
                         val bytes = ByteArray(buffer.remaining())
                         buffer.get(bytes)
                         saveImage(bytes)
                         image.close()
                    }
                }, backgroundHandler)

                // Setup ImageReader for RAW (if supported)
                if (isRawSupported) {
                    val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                    if (rawSizes != null && rawSizes.isNotEmpty()) {
                        val largestRaw = Collections.max(Arrays.asList(*rawSizes), CompareSizesByArea())
                        rawImageReader = ImageReader.newInstance(largestRaw.width, largestRaw.height, ImageFormat.RAW_SENSOR, 2)
                        rawImageReader?.setOnImageAvailableListener({ reader ->
                             backgroundHandler?.post {
                                 val image = reader.acquireNextImage()
                                 saveRawImage(image, characteristics)
                                 image.close()
                             }
                        }, backgroundHandler)
                    }
                }

                // Find highest resolution for Video
                val videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
                this.videoSize = videoSize

                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                // Set aspect ratio
                if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    binding.texture.setAspectRatio(largest.width, largest.height)
                } else {
                    binding.texture.setAspectRatio(largest.height, largest.width)
                }

                // Show info to user
                runOnUiThread {
                    binding.tvStatus.text = "Cam: $cameraId | Max Photo: ${largest.width}x${largest.height} | Max Video: ${videoSize.width}x${videoSize.height}"
                }

                this.cameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the device this code runs.
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = binding.texture.surfaceTexture!!

            texture.setDefaultBufferSize(videoSize!!.width, videoSize!!.height)

            val surface = Surface(texture)
            captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequest!!.addTarget(surface)

            // Stabilization
            captureRequest!!.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)

            // Check HDR Toggle (for Preview/Photo)
            if (binding.switchHdr.isChecked) {
                // Try Scene Mode HDR
                captureRequest!!.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
                captureRequest!!.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            }

            val targets = mutableListOf(surface, imageReader!!.surface)
            if (rawImageReader != null) {
                targets.add(rawImageReader!!.surface)
            }

            cameraDevice!!.createCaptureSession(targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        if (null == cameraDevice) return
                        this@MainActivity.cameraCaptureSession = cameraCaptureSession
                        try {
                            captureRequest!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            this@MainActivity.cameraCaptureSession!!.setRepeatingRequest(captureRequest!!.build(), null, backgroundHandler)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }
                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                         // Failed
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startRecordingVideo() {
        if (null == cameraDevice || !binding.texture.isAvailable || null == videoSize) {
            return
        }
        try {
            closePreviewSession()
            setUpMediaRecorder()

            val texture = binding.texture.surfaceTexture!!
            texture.setDefaultBufferSize(videoSize!!.width, videoSize!!.height)
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface

            val surfaces = ArrayList<Surface>()
            surfaces.add(previewSurface)
            surfaces.add(recorderSurface)

            captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequest!!.addTarget(previewSurface)
            captureRequest!!.addTarget(recorderSurface)

            // Stabilization
            captureRequest!!.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)


            cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    updatePreview()
                    runOnUiThread {
                        binding.btnRecord.text = "STOP"
                        isRecordingVideo = true
                        mediaRecorder?.start()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    // Failed
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRecordingVideo() {
        isRecordingVideo = false
        binding.btnRecord.text = "REC"
        try {
            cameraCaptureSession?.stopRepeating()
            cameraCaptureSession?.abortCaptures()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        mediaRecorder?.stop()
        mediaRecorder?.reset()
        Toast.makeText(this, "Video Saved to Gallery", Toast.LENGTH_SHORT).show()
        createCameraPreviewSession()
    }

    private fun closePreviewSession() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession!!.close()
            cameraCaptureSession = null
        }
    }

    // Logic to force High Quality
    private fun chooseVideoSize(choices: Array<Size>): Size {
        // Just pick the largest one.
        // If user wants to "Force" 4K/8K, we search for it.
        // We filter for 16:9 usually or just max area

        return Collections.max(
            Arrays.asList(*choices),
            CompareSizesByArea()
        )
    }

    private fun setUpMediaRecorder() {
        if (null == this) return
        mediaRecorder = MediaRecorder()
        mediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)

        // Attempt to find 8K/4K profile
        var profile: CamcorderProfile? = null
        if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_8K)) {
             profile = CamcorderProfile.get(CamcorderProfile.QUALITY_8K)
        } else if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_2160P)) { // 4K
             profile = CamcorderProfile.get(CamcorderProfile.QUALITY_2160P)
        } else if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_1080P)) {
             profile = CamcorderProfile.get(CamcorderProfile.QUALITY_1080P)
        } else {
             profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
        }

        mediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        // Use MediaStore for Public Gallery Visibility
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SuperCamera")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create new MediaStore record.")

        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Failed to open file descriptor.")

        mediaRecorder!!.setOutputFile(pfd.fileDescriptor)

        // FORCE HIGH BITRATE (100 Mbps) if 4K
        val bitRate = if (profile.videoFrameWidth >= 3840) 100000000 else 50000000

        mediaRecorder!!.setVideoEncodingBitRate(bitRate)
        mediaRecorder!!.setVideoFrameRate(30)
        mediaRecorder!!.setVideoSize(videoSize!!.width, videoSize!!.height)
        mediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

        val rotation = windowManager.defaultDisplay.rotation
        val orientation = ORIENTATIONS.get(rotation)
        mediaRecorder!!.setOrientationHint((orientation + sensorOrientation + 270) % 360)

        mediaRecorder!!.prepare()
    }

    private fun takePicture() {
        if (null == cameraDevice) return
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)

            // Capture RAW if toggle is on and supported
            if (binding.switchRaw.isChecked && isRawSupported && rawImageReader != null) {
                captureBuilder.addTarget(rawImageReader!!.surface)
            }

            // Stabilization & High Quality
            captureBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.0.toByte()) // 100% Quality

            if (binding.switchHdr.isChecked) {
                captureBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
                captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            }

            // Sync with current manual settings if any (simplified)
            // Ideally we copy parameters from the current preview request

            // Orientation
            val rotation = windowManager.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360)

            cameraCaptureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                 override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                     lastCaptureResult = result
                     runOnUiThread {
                        var msg = "Saved JPEG!"
                        if (binding.switchRaw.isChecked && isRawSupported) msg += " + RAW (DNG)"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                     }
                     createCameraPreviewSession() // Restart preview
                 }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun saveRawImage(image: android.media.Image, characteristics: CameraCharacteristics) {
        // Wait for capture result (simplified sync)
        var attempts = 0
        while (lastCaptureResult == null && attempts < 10) {
            try { Thread.sleep(50) } catch (e: InterruptedException) {}
            attempts++
        }

        if (lastCaptureResult == null) return // Failed to sync

        val dngCreator = DngCreator(characteristics, lastCaptureResult!!)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "RAW_${System.currentTimeMillis()}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SuperCamera")
        }

        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri).use { output ->
                     if (output != null) {
                         dngCreator.writeImage(output, image)
                     }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // Global variable for this simple implementation
    private var lastCaptureResult: TotalCaptureResult? = null

    private fun saveImage(bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SuperCamera")
        }

        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri).use { output ->
                    output?.write(bytes)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (null == binding.texture || null == videoSize) {
            return
        }
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, videoSize!!.height.toFloat(), videoSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / videoSize!!.height,
                viewWidth.toFloat() / videoSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        binding.texture.setTransform(matrix)
    }

    private fun updatePreview() {
        if (null == cameraDevice) return
        try {
            captureRequest!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            cameraCaptureSession!!.setRepeatingRequest(captureRequest!!.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }

    companion object {
        private val ORIENTATIONS = SparseIntArray()
        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }
}
