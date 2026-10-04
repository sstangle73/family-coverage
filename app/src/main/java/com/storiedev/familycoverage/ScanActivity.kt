package com.storiedev.familycoverage

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executor

/**
 * Scans the household's QR code with the camera (Camera2 and ZXing, no Google services) and returns its text. Any
 * other QR code is ignored. The camera is used only while this screen is open; frames never leave memory.
 */
class ScanActivity : Activity() {
    private lateinit var texture: TextureView
    private lateinit var hint: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val qr = QRCodeReader()
    @Volatile private var done = false
    private var size = Size(1280, 720)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        texture = TextureView(this)
        hint = Ui.text(this, "Point the camera at the QR code on the other phone (Share this household).", 16f).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            val p = Ui.dp(this@ScanActivity, 16)
            setPadding(p, p, p, p)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        Ui.show(this, root, Color.BLACK)
        // Light icons over the black camera screen, whatever the theme says.
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onPause() {
        stop()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            hint.text = "Without the camera, go back and paste the setup code instead (the other phone can share it as text)."
        }
    }

    private fun start() {
        if (thread != null) return
        thread = HandlerThread("scan").also { it.start() }
        bg = Handler(thread!!.looper)
        if (texture.isAvailable) {
            open()
        } else {
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = open()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val handler = bg ?: return
        val cm = getSystemService(CameraManager::class.java)
        try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull()
            if (id == null) {
                hint.text = "This device has no camera: paste the setup code instead."
                return
            }
            val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            size = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888))
            layoutPreview()
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (!done) decode(img)
                    } finally {
                        img.close()
                    }
                }, handler)
            }
            cm.openCamera(id, stateCallback, handler)
        } catch (e: Exception) {
            hint.text = "The camera couldn't start (${e.javaClass.simpleName}): paste the setup code instead."
        }
    }

    /**
     * Sizes the preview to the camera's shape for the way the phone is held, and turns it upright: the camera framework
     * corrects for how the sensor is mounted, not for the display's rotation (Camera2's usual transform).
     */
    private fun layoutPreview() {
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val metrics = resources.displayMetrics
        val (w, h) = if (sideways) {
            metrics.heightPixels * size.width / size.height to metrics.heightPixels
        } else {
            metrics.widthPixels to metrics.widthPixels * size.width / size.height
        }
        texture.layoutParams = (texture.layoutParams as FrameLayout.LayoutParams).apply {
            width = w
            height = h
        }
        val matrix = Matrix()
        val view = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val cx = view.centerX()
        val cy = view.centerY()
        if (sideways) {
            val buffer = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
            buffer.offset(cx - buffer.centerX(), cy - buffer.centerY())
            matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL)
            val scale = maxOf(h.toFloat() / size.height, w.toFloat() / size.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        texture.setTransform(matrix)
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
            camera = cam
            startSession(cam)
        }

        override fun onDisconnected(cam: CameraDevice) {
            cam.close()
            camera = null
        }

        override fun onError(cam: CameraDevice, error: Int) {
            cam.close()
            camera = null
            runOnUiThread { hint.text = "The camera stopped (error $error): paste the setup code instead." }
        }
    }

    private fun startSession(cam: CameraDevice) {
        val st = texture.surfaceTexture ?: return
        val r = reader ?: return
        val handler = bg ?: return
        st.setDefaultBufferSize(size.width, size.height)
        val preview = Surface(st)
        try {
            val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(preview), OutputConfiguration(r.surface)),
                Executor { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        runCatching { s.setRepeatingRequest(request, null, handler) }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        runOnUiThread { hint.text = "The camera couldn't start: paste the setup code instead." }
                    }
                },
            )
            cam.createCaptureSession(config)
        } catch (e: Exception) {
            runOnUiThread { hint.text = "The camera couldn't start (${e.javaClass.simpleName}): paste the setup code instead." }
        }
    }

    private fun decode(img: Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        // The last row can stop short of the stride; pad so the luminance source can index every row.
        val y = if (data.size >= rowStride * img.height) data else data.copyOf(rowStride * img.height)
        val text = Qr.decode(y, rowStride, img.width, img.height, qr) ?: return
        if (!text.contains(Household.CODE_PREFIX)) return // someone else's QR code: keep looking
        done = true
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, text))
            finish()
        }
    }

    private fun stop() {
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { reader?.close() }
        reader = null
        thread?.quitSafely()
        thread = null
        bg = null
    }

    companion object {
        const val EXTRA_CODE = "code"

        /** 1280x720 if the camera has it, else the largest 16:9 size up to 1920x1080, else its first size. */
        fun pickSize(sizes: Array<Size>): Size {
            sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return it }
            return sizes.filter { it.width <= 1920 && it.width * 9 == it.height * 16 }.maxByOrNull { it.width }
                ?: sizes.firstOrNull() ?: Size(1280, 720)
        }
    }
}
