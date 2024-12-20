package com.example.kick.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.content.Intent

import android.graphics.RectF
import com.example.kick.R

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import com.example.kick.cv.DetectionResult
import com.example.kick.cv.EmotionModel
import com.example.kick.cv.YoloModel

class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var cameraExecutor: ExecutorService

    // Model
    private lateinit var yoloModel: YoloModel
    private lateinit var emotionModel: EmotionModel

    companion object {
        private const val TAG = "CameraActivity"
    }

    /* --- Camera Permission --- */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            finish()  // Close the activity if permission is denied
        }
    }

    private fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun checkCameras() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 전면 및 후면 카메라가 있는지 확인
            val hasFrontCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            val hasBackCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

            Log.d("Check", "Front Camera: $hasFrontCamera, Back Camera: $hasBackCamera")
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        yoloModel = YoloModel(this)
        emotionModel = EmotionModel(this)

        if (isCameraPermissionGranted()) {
            checkCameras()
            startCamera()
        } else {
            requestCameraPermission()
        }
    }


    /* --- Execute Yolo --- */
    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun resizeBitmap(bitmap: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, 640, 640, true)
    }

    private fun saveBitmapToFile(bitmap: Bitmap, fileName: String) {
        val file = File(getExternalFilesDir(null), fileName)
        var fileOutputStream: FileOutputStream? = null
        try {
            fileOutputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)  // PNG로 이미지 저장
            fileOutputStream.flush()
            Log.d("Camera", "Image saved: ${file.absolutePath}")
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e("Camera", "Failed to save image: ${e.message}")
        } finally {
            fileOutputStream?.close()
        }
    }

    private var happyCount = 0
    private fun processImage(imageProxy: ImageProxy) {
//        if (isActivityDestroyed) {
//            imageProxy.close() // ImageProxy 닫기
//            return
//        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            // Rotation 처리
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val bitmap = imageProxy.toBitmap()
            // 이미지 크기 조정 및 회전
            val resizedBitmap = resizeBitmap(bitmap)
            val rotatedBitmap = rotateBitmap(resizedBitmap, rotationDegrees)

//                val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)
//                val resizedBitmap = resizeBitmap(rotatedBitmap)
//                Log.d("Rotate", "Rotated Bitmap width: ${rotatedBitmap.width}, height: ${rotatedBitmap.height}")

            // YOLO 모델을 사용하여 얼굴 탐지
            val boxes: List<Pair<RectF, Float>> = yoloModel.runInference(rotatedBitmap, overlayView.width, overlayView.height)

            // 감정 분류 및 박스에 추가
            val detectionResults = mutableListOf<DetectionResult>()
            for ((box, confidence) in boxes) {
                // 얼굴 이미지 추출
//                    Log.d("check", "box.left:${box.left.to}" )
                val faceBitmap = Bitmap.createBitmap(
                    rotatedBitmap,
                    (box.left * rotatedBitmap.width).toInt(),
                    (box.top * rotatedBitmap.height).toInt(),
                    ((box.right - box.left) * rotatedBitmap.width).toInt(),
                    ((box.bottom - box.top) * rotatedBitmap.height).toInt()
                )
//                saveBitmapToFile(faceBitmap, "face.png")
                // 감정 분류 수행
                val emotionIndex = emotionModel.classifyEmotion(faceBitmap)
                val emotionLabel = getEmotionLabel(emotionIndex)

                if (emotionLabel == "Happy") {
                    happyCount++
                    if (happyCount >= 200) {
                        Toast.makeText(this, "웃음 챌린지 성공 ! 행복하세요 !", Toast.LENGTH_SHORT).show()
                        finish() // 3초 이상 Happy 감정이면 카메라 멈추기
                        return@post
                    }
                } else {
                    happyCount = 0 // Reset if not happy
                }

                // DetectionResult 객체에 바운딩 박스, 신뢰도, 감정 레이블 저장
                detectionResults.add(DetectionResult(box, confidence, emotionLabel))
            }

                // 감정 분류 결과와 함께 박스를 표시
            overlayView.setBoundingBoxes(detectionResults)

            imageProxy.close()  // ImageProxy 닫기
        }
    }

    // 감정 인덱스를 레이블로 변환하는 함수
    private fun getEmotionLabel(emotionIndex: Int): String {
        return when (emotionIndex) {
//            0 -> "Happy"
//            1 -> "Sad"
//            2 -> "Angry"
//            3 -> "Surprise"
//            4 -> "Fear"
//            5 -> "Disgust"
//            6 -> "Neutral"
//            else -> "Unknown"
            0 -> "Angry"
            1 -> "Disgust"
            2 -> "Fear"
            3 -> "Happy"
            4 -> "Sad"
            5 -> "Surprise"
            6 -> "Neutral"
            else -> "Unknown"
        }
    }


    /* --- Start Camera --- */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val cameraSelector = getCameraSelector()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)  // Use PreviewView's SurfaceProvider
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysisUseCase ->
                    analysisUseCase.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                        processImage(imageProxy)  // Analyze image data
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun getCameraSelector(): CameraSelector {
        val cameraProvider = ProcessCameraProvider.getInstance(this).get()
        return if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraExecutor.shutdown() // 카메라 작업 스레드 종료
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error shutting down camera executor: ${e.message}")
        }
    }

}
