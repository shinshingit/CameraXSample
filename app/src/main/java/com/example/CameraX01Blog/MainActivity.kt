package com.example.CameraX01Blog

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import com.example.CameraX01Blog.databinding.ActivityMainBinding
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.Surface.ROTATION_0
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias MyListener = (bmpImage: Bitmap) -> Unit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var binding: ActivityMainBinding

    // CameraX関連
    //private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    var isCapturing2:Boolean = false
    var customLifecycle = CustomLifecycle()
    // カメラの設定用(AF/AWBなど)
    lateinit var captureRequestBuilder: CaptureRequest.Builder
    var captureSession:CameraCaptureSession? = null
    // UIスレッド更新用
    val handler = HandlerCompat.createAsync(Looper.myLooper()!!)

    // ★OpenCVを使用するために追加：CallBack
    private val mLoaderCallback: BaseLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                SUCCESS -> {
                    Log.i("OpenCV", "OpenCV loaded successfully")
                    Log.d("openCV", OpenCVLoader.OPENCV_VERSION)
                }
                else -> {
                    super.onManagerConnected(status)
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        //region// permission 関連
        if (allPermissionsGranted()) {
            //startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        //endregion

        //ボタンのリスナクラスのインスタンス作成
        val btnListener = BtnListener()
        // リスナを設定
        binding.btnStartCapture2.setOnClickListener(btnListener)

        //region //CameraX関連
        cameraExecutor = Executors.newSingleThreadExecutor()
        setUpCamera()
        //endregion

    }

    // ★OpenCVを使用するために追加
    override fun onResume() {
        super.onResume()
        if (!OpenCVLoader.initDebug()) {
            Log.d(
                "OpenCV",
                "Internal OpenCV library not found. Using OpenCV Manager for initialization"
            )
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback)
        } else {
            Log.d("OpenCV", "OpenCV library found inside package. Using it!")
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        }
    }

    // permission関連
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    //ボタンのリスナクラス
    private inner class BtnListener: View.OnClickListener{
        override fun onClick(v: View){
            when(v.id){
                binding.btnStartCapture2.id -> {
                    if (!isCapturing2) {
                        // キャプチャ開始
                        customLifecycle.doStart()
                        isCapturing2 = true
                    }else{
                        customLifecycle.doPause()
                        isCapturing2 = false
                    }

                }
            }
        }
    }


    //region CameraX関連
    private fun setUpCamera(){
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)



        cameraProviderFuture.addListener(Runnable {
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // ◆PreView
//            val preview = Preview.Builder().build()
//            preview.setSurfaceProvider(binding.ppvCapture2.surfaceProvider)

            // ◆Capture(今回なし)

            // ◆Analysis
            imageAnalyzer = ImageAnalysis.Builder().setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build().also {
                it.setAnalyzer(cameraExecutor, MyAnalyzer{
                    bmpImage ->

                    // GUIの更新
                    val postExecutor = UpdateUI(bmpImage)
                    handler.post(postExecutor)

                })
            }

            var camera = cameraProvider.bindToLifecycle(customLifecycle, cameraSelector, imageAnalyzer)

        }, ContextCompat.getMainExecutor(this))
    }


    private inner class MyAnalyzer(
        private val listener: MyListener
    ) : ImageAnalysis.Analyzer {

        // 画像格納用変数
        lateinit var bmp_ori: Bitmap
        lateinit var mat_ori: Mat
        lateinit var mat_output: Mat
        lateinit var bmp_output: Bitmap

        // 画像変換用(Image -> Bitmap)
        fun Image.toBitmap():Bitmap{

            val yBuffer = planes[0].buffer // Y
            val uvBuffer = planes[2].buffer // UV

            val ySize = yBuffer.remaining()
            val uvSize = uvBuffer.remaining()

            val nv21 = ByteArray(ySize + uvSize )

            yBuffer.get(nv21, 0, ySize)
            uvBuffer.get(nv21, ySize, uvSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
            val imageBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }

        // 毎フレーム呼ばれる
        override fun analyze(imageProxy: ImageProxy) {

            // バッファの読み取り位置の固定
            imageProxy.planes[0].buffer.rewind()
            imageProxy.planes[1].buffer.rewind()
            imageProxy.planes[2].buffer.rewind()

            // ImageProxy を Image に変換
            val image = imageProxy.image

            // Image をBitmapに変換
            bmp_ori = image!!.toBitmap()

            // BitmapをMatに変換
            mat_ori = Mat()
            Utils.bitmapToMat(bmp_ori, mat_ori)

            // Mat画像の回転(スマホタテ向きに合わせる。必要に応じて各自変更)
            Core.rotate(mat_ori, mat_ori, ROTATION_0)

            //画像処理など(今回はただの複製)
            mat_output = mat_ori.clone()

            // MatをBitmapに変換
            bmp_output = Bitmap.createBitmap(mat_ori.width(), mat_ori.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat_output, bmp_output)

            // UI更新のためにリスナにBitmapを渡す
            listener(bmp_output)

            imageProxy.close()
        }
    }

    // UI更新用
    private inner class UpdateUI(bmpImage: Bitmap ):Runnable{
        val bmpImg = bmpImage
        override fun run() {
            binding.ivAnalysisImage.setImageBitmap(bmpImg)
        }
    }
    //endregion

    inner class CustomLifecycle : LifecycleOwner {
        private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

        init {
            lifecycleRegistry.markState(Lifecycle.State.CREATED)
        }

        fun doOnResume() {
            lifecycleRegistry.markState(Lifecycle.State.RESUMED)
        }

        fun doStart() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun doPause() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

        override fun getLifecycle(): Lifecycle {
            return lifecycleRegistry
        }
    }


}
