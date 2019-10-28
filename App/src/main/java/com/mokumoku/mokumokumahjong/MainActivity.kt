package com.mokumoku.mokumokumahjong

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
// Your IDE likely can auto-import these classes, but there are several
// different implementations so we list them here to disambiguate
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.graphics.Matrix
import android.util.JsonReader
import android.util.Log
import android.util.Rational
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.TimeUnit
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.google.gson.Gson
import org.json.JSONObject
import java.io.*
import java.nio.charset.Charset


// This is an arbitrary number we are using to keep tab of the permission
// request. Where an app has multiple context for requesting permission,
// this can help differentiate the different contexts
private const val REQUEST_CODE_PERMISSIONS = 10

// This is an array of all the permission specified in the manifest
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

//private val instance: MyContext? = null
//private val applicationContext: Context? = null

class MainActivity : AppCompatActivity(), LifecycleOwner {

    companion object {
        var  instance: MainActivity? =null
            private set
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Add this at the end of onCreate function

        viewFinder = findViewById(R.id.textureView)

        // Request camera permissions
        if (allPermissionsGranted()) {
            viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }


    }




    // Add this after onCreate

    private lateinit var viewFinder: TextureView

    data class Config
        (
        val accessKey:String,
        val secKey:String,
        val bucket:String
    ) {
        companion object {
            var config:Config = _parseConfig("awskey.json")

            fun get():Config {
                return config
            }

            fun _parseConfig(filePath:String):Config {
                //val source = File(filePath).readText(Charsets.UTF_8)
                val source = loadJSONFromAssets()
                return Gson().fromJson(source, Config::class.java)!!
            }

            public fun loadJSONFromAssets(): String {
                var json: String? = null
                try {
                    val inputStream = MainActivity.instance!!.getAssets().open("awskey.json")
                    val size = inputStream.available()
                    val buffer = ByteArray(size)
                    inputStream.read(buffer)
                    inputStream.close()
                    json = String(buffer, Charset.defaultCharset())
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                return json.toString()
            }


        }
    }






    private fun startCamera() {
        // TODO: Implement CameraX operations
        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetAspectRatio(Rational(1, 1))
            setTargetResolution(Size(640, 640))
        }.build()

        // Build the viewfinder use case
        val preview = Preview(previewConfig)

        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {

            // To update the SurfaceTexture, we have to remove it and re-add it
            val parent = viewFinder.parent as ViewGroup
            parent.removeView(viewFinder)
            parent.addView(viewFinder, 0)

            viewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        // Add this before CameraX.bindToLifecycle

        // Create configuration object for the image capture use case
        val imageCaptureConfig = ImageCaptureConfig.Builder()
            .apply {
                setTargetAspectRatio(Rational(1, 1))
                // We don't set a resolution for image capture; instead, we
                // select a capture mode which will infer the appropriate
                // resolution based on aspect ration and requested mode
                setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            }.build()

        // Build the image capture use case and attach button click listener
        val imageCapture = ImageCapture(imageCaptureConfig)
        findViewById<Button>(R.id.button).setOnClickListener {
            val file = File(externalMediaDirs.first(),
                "${System.currentTimeMillis()}.jpg")
            imageCapture.takePicture(file,
                object : ImageCapture.OnImageSavedListener {
                    override fun onError(error: ImageCapture.UseCaseError,
                                         message: String, exc: Throwable?) {
                        val msg = "Photo capture failed: $message"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.e("CameraXApp", msg)
                        exc?.printStackTrace()
                    }

                    override fun onImageSaved(file: File) {
                        val msg = "Photo capture succeeded: ${file.absolutePath}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.d("CameraXApp", msg)


                        // 認証情報の作成
                        val basicAWSCredentials = BasicAWSCredentials(Config.get().accessKey, Config.get().secKey)
                        Log.d("CameraXApp", "awsS3 credentials ")

                        // 作成した認証情報でクライアント接続用オブジェクトを作成
                        val s3Client = AmazonS3Client(basicAWSCredentials)
                        val transferUtility = TransferUtility(s3Client, applicationContext)
//                        Log.d("CameraXApp", "aws conect start")
                        // ファイルを指定してアップロードを行う
                        val observer = transferUtility.upload(Config.get().bucket, "${System.currentTimeMillis()}.jpg", file)

                        // コールバックを登録しておく
                        observer.setTransferListener(object : TransferListener {
                            override fun onStateChanged(id: Int, state: TransferState) {
                                Log.d("AwsSample", "status: $state")
                            }

                            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                                Log.d("AwsSample", "progress: $id bytesCurrent:$bytesCurrent bytesTotal:$bytesTotal")
                            }

                            override fun onError(id: Int, ex: Exception) {
                                ex.printStackTrace()
                            }
                        })
                    }
                })
        }
        // Bind use cases to lifecycle
        // If Android Studio complains about "this" being not a LifecycleOwner
        // try rebuilding the project or updating the appcompat dependency to
        // version 1.1.0 or higher.
        CameraX.bindToLifecycle(this, preview,imageCapture)
    }

    private fun updateTransform() {
        // TODO: Implement camera viewfinder transformations
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when(viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix)
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

}
