package com.example.customdocumentcropper.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.customdocumentcropper.R
import com.example.customdocumentcropper.databinding.ActivityCameraBinding
import com.example.customdocumentcropper.ui.custom.VerticalSeekBar
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraActivity : AppCompatActivity() {
    private var audioPlayer: MediaPlayer?=null
    private var focusView: ImageView?=null
    private lateinit var cameraControl: CameraControl
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var flashState:Boolean=false
    private var rearCameraEnabled:Boolean=true;


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        if (allPermissionsGranted()) {
            validateCameraBeforeStarting()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        audioPlayer = MediaPlayer.create(this, R.raw.camera_click_sound);
        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupClickListeners()
        setupTouchListeners()
        //auto focus after every 2 seconds
        autofocusAfterFixedInterval();
        setupZoom();
    }

    /*
    Zooming up to 10x in the camera. The zoom values that can be provided to cameraControl can only be between 0 to 1.
    The seekbar calculation has been made in such a way to support that.
    */
    private fun setupZoom() {
        val max = 100
        val min = 10
        val step = 1
        binding.zoomSeek.max = (max-min)/step;

        binding.zoomSeek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekbar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value: Int = min + progress * step
                cameraControl.setLinearZoom(value / 100f)
                binding.txtZoomScale.text = ((value) / 10f).toString() + "x"
            }
            override fun onStartTrackingTouch(seek: SeekBar) {

            }

            override fun onStopTrackingTouch(seek: SeekBar) {

            }
        })
    }


    private fun autofocusAfterFixedInterval() {
        Handler(Looper.getMainLooper()).postDelayed({
            binding.viewFinder.afterMeasured {
                val autoFocusPoint = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    .createPoint(.5f, .5f)
                try {
                    val autoFocusAction = FocusMeteringAction.Builder(
                        autoFocusPoint,
                        FocusMeteringAction.FLAG_AF
                    ).apply {
                        //start auto-focusing after 2 seconds
                        setAutoCancelDuration(2, TimeUnit.SECONDS)
                    }.build()
                    cameraControl.startFocusAndMetering(autoFocusAction)
                    autofocusAfterFixedInterval()
                } catch (e: CameraInfoUnavailableException) {
                    Log.d("ERROR", "cannot access camera", e)
                }
            }
        }, 2000)
    }

    //Focusing on the touched point on the preview window
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListeners() {
        binding.viewFinder.afterMeasured {
            binding.viewFinder.setOnTouchListener { _, event ->
                return@setOnTouchListener when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                            binding.viewFinder.width.toFloat(), binding.viewFinder.height.toFloat()
                        )
                        val autoFocusPoint = factory.createPoint(event.x, event.y)
                        try {
                            cameraControl.startFocusAndMetering(
                                FocusMeteringAction.Builder(
                                    autoFocusPoint,
                                    FocusMeteringAction.FLAG_AF
                                ).apply {
                                    //focus only when the user tap the preview
                                    disableAutoCancel()
                                }.build()
                            )
                            displayFocusPoint(event.x, event.y)
                        } catch (e: CameraInfoUnavailableException) {
                            Log.d("ERROR", "cannot access camera", e)
                        }
                        true
                    }
                    else -> false // Unhandled event.
                }
            }
        }
    }

    //To display a square where the user focuses the camera
    private fun displayFocusPoint(x: Float, y: Float) {
        if(focusView!=null)  binding.layoutMain.removeView(focusView)
        focusView = ImageView(this)
        focusView?.setImageResource(R.drawable.icon_square)
        val params = RelativeLayout.LayoutParams(80,80);
        params.leftMargin = x.toInt()-40;
        params.topMargin = y.toInt()-40;
        focusView?.layoutParams = params
        binding.layoutMain.addView(focusView)
        focusView?.requestLayout()

        Handler(Looper.getMainLooper()).postDelayed({
            if (focusView!=null) binding.layoutMain.removeView(focusView)
            focusView = null
        }, 1000)

    }

    private fun setupClickListeners() {
        //capturing image
        binding.btnCapturePhoto.setOnClickListener {
            takePhoto()
            audioPlayer?.start()
            animateShutter()
        }

        //handling flash toggle
        binding.btnFlashToggle.setOnClickListener{
            flashState = !flashState
            if (flashState) binding.btnFlashToggle.setImageResource(R.drawable.icon_flash_on) else binding.btnFlashToggle.setImageResource(R.drawable.icon_flash_off)
            val enableTorchLF: ListenableFuture<Void> = cameraControl.enableTorch(flashState)
            enableTorchLF.addListener({
                try {
                    // At this point, the torch has been successfully enabled
                } catch (exception: Exception) {
                    // Handle any potential errors
                }
            }, cameraExecutor /* Executor where the runnable callback code is run */)
        }

        //changing camera
        binding.btnChangeCamera.setOnClickListener{
            rearCameraEnabled = !rearCameraEnabled
            if (rearCameraEnabled) startCamera(CameraSelector.DEFAULT_BACK_CAMERA) else startCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        }
    }

    private fun animateShutter() {
        binding.btnCapturePhoto.animate()
            .scaleX(.7f)
            .scaleY(.7f)
            .setListener(object: Animator.AnimatorListener{
                override fun onAnimationStart(p0: Animator?) {
                }

                override fun onAnimationEnd(p0: Animator?) {
                    binding.btnCapturePhoto.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setInterpolator(AccelerateDecelerateInterpolator()).duration = 150
                }

                override fun onAnimationCancel(p0: Animator?) {

                }

                override fun onAnimationRepeat(p0: Animator?) {

                }
            })
            .setInterpolator(AccelerateDecelerateInterpolator()).duration = 150

    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg")

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            })
    }

    private fun checkCameraFront(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }

    private fun checkCameraRear(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }

    private fun startCamera(cameraSelector: CameraSelector) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)


        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            // Preview
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            imageCapture = ImageCapture.Builder()
                .build()

            // Select back camera as a default
            val cameraSelector = cameraSelector

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

                cameraControl = camera.cameraControl

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                validateCameraBeforeStarting()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun validateCameraBeforeStarting() {
        if(checkCameraRear() && checkCameraFront()){
            rearCameraEnabled = true
            startCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        }else{
            when {
                checkCameraFront() -> {
                    rearCameraEnabled = false
                    startCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                }
                checkCameraRear() -> {
                    rearCameraEnabled = true
                    startCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                }
                else -> {
                    Toast.makeText(this, "Error starting camera!", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }

    }

    private inline fun View.afterMeasured(crossinline block: () -> Unit) {
        if (measuredWidth > 0 && measuredHeight > 0) {
            block()
        } else {
            viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (measuredWidth > 0 && measuredHeight > 0) {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                        block()
                    }
                }
            })
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}