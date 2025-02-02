package com.objdetection

//replace "import android.media.ExifInterface"
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import coil.load
import com.objdetection.databinding.ActivityMainBinding
import wseemann.media.FFmpegMediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


//******ImageAnalysis Function Test******//
//typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
//    private val context: Context? = null

    private val NANODET = 1
    private val YOLOV5S = 2
    private val YOLOV4_TINY = 3

    private var useModel = NANODET
    private var useGPU = false

    var threshold = 0.3f
    var nmsThreshold = 0.7f

    var videoSpeed = 1.0f
    var videoCurFrameLoc: Long = 0
    private val videoSpeedMax = 20 + 1
    private val videoSpeedMin = 1

    private val detectCamera = AtomicBoolean(false)
    private val detectPhoto = AtomicBoolean(false)
    private val detectVideo = AtomicBoolean(false)



    private var targetWidth = 0
    private var targetHeight = 0
    var width = 0
    var height = 0

    private var threadsNumber = 0
    private var startTime: Long = 0
    private var endTime: Long = 0
    private var totalFPS = 0.0
    private var FPSCount = 0.0

    private var mutableBitmap: Bitmap? = null
//    private var mReusableBitmap: Bitmap? = null
    private var detectService = Executors.newSingleThreadExecutor()
//    private lateinit var detectService : ExecutorService
    private lateinit var mCameraProvider: ProcessCameraProvider
    private lateinit var mPreview: Preview
    private var mImageCapture: ImageCapture? = null
    private var mImageAnalysis: ImageAnalysis? = null
    private var cameraExecutor = Executors.newSingleThreadExecutor()
   // private val currentExecutor = Executors.newFixedThreadPool(2)
  //  private var analyzing = true
    // Analyzer implementation
  //  private var mAnalyzer: ImageAnalysis.Analyzer? = null

//    private var pauseAnalysis = false

    private var mmr: FFmpegMediaMetadataRetriever? = null

//    var reusableBitmaps: MutableSet<SoftReference<Bitmap>>? = null
//    private lateinit var memoryCache: LruCache<String, BitmapDrawable>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //视图绑定
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //获取开始界面传送的是否使用GPU和使用模型类型的信息
        val intent = intent
        useGPU = intent.getBooleanExtra("useGPU", false)
        useModel = intent.getIntExtra("useModel", NANODET)

        initModel()
        initView()
        initViewListener()

        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
//        println(prefs.getString("numThreads", "<unset>")) //<unset>表示没有查到numThreads这个key的返回值
        //number of threads in CPU Mode
        threadsNumber = prefs.getString("numThreads", "<unset>")?.toInt()!!
//        Log.d(TAG, "numThreads: $threadsNumber")

//        Coil.setImageLoader {
//            ImageLoader(this).newBuilder()
//                .components {
//                    add(AppIconFetcherFactory(this@MainActivity))
//                }
//                .build()
//        }
//        val imageLoader = context?.let {
//            ImageLoader.Builder(it)
//                .crossfade(true)
//                .build()
//        }


        //替代StartActivityForResult()
        //选取图片
        val photoActivity =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (it.data != null && it.resultCode == Activity.RESULT_OK) {
                    runByPhoto(it.resultCode, it.data)
                } else {
                    Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show()
                }

            }
        binding.btnPhoto.setOnClickListener {
            val permission = ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            if (permission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    777
                )
            } else {
                val intentPhoto = Intent(Intent.ACTION_PICK)
                intentPhoto.type = "image/*"
                photoActivity.launch(intentPhoto)
            }
        }

        //选取视频
        val videoActivity =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (it.data != null && it.resultCode == Activity.RESULT_OK) {
                    runByVideo(it.resultCode, it.data)
                } else {
                    Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show()
                }
            }
        binding.btnVideo.setOnClickListener {
            val permission = ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            if (permission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    777
                )
            } else {
                val intentVideo = Intent(Intent.ACTION_PICK)
                intentVideo.type = "video/*"
                videoActivity.launch(intentVideo)
            }
        }
    }
    //初始化选取的模型
    private fun initModel() {
        when (useModel) {
            NANODET -> NanoDetPlus.init(assets, useGPU)
            YOLOV5S -> YOLOv5s.init(assets, useGPU)
            YOLOV4_TINY -> YOLOv4tiny.init(assets, 0, useGPU)
        }
    }
    //初始化界面
    private fun initView() {
        binding.sbVideo.visibility = View.GONE
        binding.sbVideoSpeed.min = videoSpeedMin
        binding.sbVideoSpeed.max = videoSpeedMax
        binding.sbVideoSpeed.visibility = View.GONE
        binding.btnBack.visibility = View.GONE
    }
    //初始化界面响应程序
    private fun initViewListener() {
        binding.toolBar.setNavigationIcon(R.drawable.actionbar_dark_back_icon)
        binding.toolBar.setNavigationOnClickListener { finish() }
        if (useModel != NANODET && useModel != YOLOV5S) {
            binding.nmsSeek.isEnabled = false
            binding.thresholdSeek.isEnabled = false
            binding.txtNMS.visibility = View.GONE
            binding.txtThresh.visibility = View.GONE
            binding.nmsSeek.visibility = View.GONE
            binding.thresholdSeek.visibility = View.GONE
            binding.valTxtView.visibility = View.GONE
        } else if (useModel == NANODET) {
            threshold = 0.4f
            nmsThreshold = 0.6f
        } else if (useModel == YOLOV5S) {
            threshold = 0.3f
            nmsThreshold = 0.5f
        }
        binding.nmsSeek.progress = (nmsThreshold * 100).toInt()
        binding.thresholdSeek.progress = (threshold * 100).toInt()
        val format = "THR: %.2f, NMS: %.2f"
        binding.valTxtView.text =
            String.format(Locale.ENGLISH, format, threshold, nmsThreshold)

        binding.nmsSeek.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, i: Int, b: Boolean) {
                nmsThreshold = (i / 100f)
                binding.valTxtView.text =
                    String.format(Locale.ENGLISH, format, threshold, nmsThreshold)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.thresholdSeek.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, i: Int, b: Boolean) {
                threshold = (i / 100f)
                binding.valTxtView.text =
                    String.format(Locale.ENGLISH, format, threshold, nmsThreshold)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.sbVideo.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, i: Int, b: Boolean) {
                videoCurFrameLoc = i.toLong()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                videoCurFrameLoc = seekBar.progress.toLong()
            }
        })

        binding.sbVideoSpeed.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, i: Int, b: Boolean) {
                videoSpeed = i.toFloat()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                Toast.makeText(
                    this@MainActivity,
                    "Video Speed:" + seekBar.progress,
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        if (allPermissionsGranted()) {
            startCamera(binding.viewFinder)
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    //伴生对象，定义静态成员和静态方法
    companion object {
//         Used to load the 'objdetection' library on application startup.
//         init {
//             System.loadLibrary("objdetection")
//          }
        private const val TAG = "ObjDetection"
//        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA,Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    //申请权限
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera(binding.viewFinder)
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
    
    private fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                mCameraProvider = cameraProviderFuture.get()
                bindPreview(mCameraProvider, previewView)
            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    //绑定生命周期对象
    private fun bindPreview(
        cameraProvider: ProcessCameraProvider,
        previewView: PreviewView
    ) {
        //设置PreviewView的实现模式为Texture View，避免一些问题(如setSurfaceProvider()后下方有黑边)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Image preview use case
        val previewBuilder = Preview.Builder()

        // Image capture use case
        val captureBuilder = ImageCapture.Builder()
             .setTargetRotation(previewView.display.rotation)

        mPreview = previewBuilder.build()

        mImageCapture = captureBuilder.build()
        //获取屏幕旋转方向
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {   //横屏
            targetWidth = 640
            targetHeight = 480
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT) { //竖屏
            targetWidth = 480
            targetHeight = 640
        }
        mImageAnalysis = ImageAnalysis.Builder()
            // enable the following line if RGBA output is needed.
            // .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(previewView.display.rotation)

            //   .setTargetResolution(Size(640, 480))
            //   .setTargetResolution(Size(480, 640))
            .setTargetResolution(Size(targetWidth, targetHeight))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        mImageAnalysis!!.setAnalyzer(cameraExecutor) { imageProxy ->
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            // insert your code here.
//            detectService = Executors.newSingleThreadExecutor()
            detectOnModel(imageProxy, rotationDegrees)
            // after done, release the ImageProxy object
            imageProxy.close()
        }


//******ImageAnalysis Function Test******//
//         val mImageAnalysis = ImageAnalysis.Builder()
//            .build()
//            .also {
//                it.setAnalyzer(cameraExecutor, DetectAnalyzer { luma ->
//                    Log.d(TAG, "Average luminosity: $luma")
//                })
//            }

        cameraProvider.unbindAll()
        try {
            cameraProvider.bindToLifecycle(
                this, cameraSelector,
                mPreview, mImageCapture,mImageAnalysis
            )
        // Bind the view's surface to preview use case.
        mPreview.setSurfaceProvider(previewView.surfaceProvider)
        } catch (e: java.lang.Exception) {
            Log.e("Camera", "camera provider bind error:", e)
        }
    }

    private fun detectOnModel(image: ImageProxy, rotationDegrees: Int) {
        if (detectCamera.get() || detectPhoto.get() || detectVideo.get()) {
            return
        }
//        *Test*
//        Log.d(TAG, "rotationDegrees is: $rotationDegrees")
//        val w = image.width
//        val h = image.height
//        Log.d(TAG, "Width is: $w")
//        Log.d(TAG, "Height is: $h")
        detectCamera.set(true)
        startTime = System.currentTimeMillis()
        val bitmapsrc = imageToBitmap(image) // 格式转换
        if (detectService == null) {
            detectCamera.set(false)
            return
        }

        detectService.execute {
            val matrix = Matrix()
            //if (rotationDegrees == 90)
            matrix.postRotate(rotationDegrees.toFloat())
            width = bitmapsrc.width
            height = bitmapsrc.height

            val bitmap = Bitmap.createBitmap(bitmapsrc, 0, 0, width, height, matrix, false)

            detectAndDraw(bitmap)
            showResultOnUI()
        }

    }

    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val nv21 = imageToNV21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        try {
            out.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun imageToNV21(image: ImageProxy): ByteArray {
        val planes = image.planes
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        val yBuffer = y.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        // U and V are swapped
        yBuffer[nv21, 0, ySize]
        vBuffer[nv21, ySize, vSize]
        uBuffer[nv21, ySize + vSize, uSize]
        return nv21
    }

    private fun showResultOnUI() {
        runOnUiThread {
            detectCamera.set(false)
            binding.imageView.setImageBitmap(mutableBitmap)
//            binding.imageView.load(mutableBitmap)
//            {
//                transformations(BlurTransformation())
//            }
            endTime = System.currentTimeMillis()
            val dur = endTime - startTime
//            Log.d(TAG, "dur time is: $dur")
            val fps = (1000.0 / dur).toFloat()
            //更新API后，有时fps为Infinity，正在debug
            //排除帧率过高的异常
            if (fps < 1000) {
                totalFPS = if (totalFPS == 0.0) fps.toDouble() else totalFPS + fps
                FPSCount++
            }
            else
                Log.d(TAG, "Infinity")
            val modelName: String = getModelName()
            binding.tvInfo.text = String.format(
                Locale.CHINESE,
                "%s\nSize: %dx%d\nTime: %.3f s\nFPS: %.3f\nAVG_FPS: %.3f",
                modelName, height, width, dur / 1000.0, fps, totalFPS.toFloat() / FPSCount
            )
        }
    }

    private fun detectAndDraw(image: Bitmap): Bitmap? {
        var result: Array<Box>? = null
        when (useModel) {
            NANODET -> result = NanoDetPlus.detect(image, threshold, nmsThreshold, threadsNumber)
            YOLOV5S -> result = YOLOv5s.detect(image, threshold, nmsThreshold, threadsNumber)
            YOLOV4_TINY -> result = YOLOv4tiny.detect(image, threshold, nmsThreshold, threadsNumber)
        }

        if (result == null) {
            detectCamera.set(false)
            return image
        }
        if (useModel == NANODET || useModel == YOLOV5S || useModel == YOLOV4_TINY ) {
            mutableBitmap = drawBoxRects(image, result)
        }
        return mutableBitmap
    }

    private fun getModelName(): String {
        var modelName = "NULL"
        when (useModel) {
            NANODET -> modelName = "NanoDet-Plus"
            YOLOV5S -> modelName = "YOLOv5s"
            YOLOV4_TINY -> modelName = "YOLOv4-tiny"
        }
        return if (useGPU) "[ GPU ] $modelName" else "[ CPU ] $modelName"
    }

    private fun drawBoxRects(bitmap: Bitmap, results: Array<Box>?): Bitmap {
        //if (results == null || results.size <= 0) {
        if (results == null || results.isEmpty()) {
            return bitmap
        }
        //copy，否则出错(不允许直接操作bitmap)
        val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(bitmapCopy)
        val boxPaint = Paint()
        boxPaint.alpha = 200
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = 4 * bitmapCopy.width / 800.0f
        boxPaint.textSize = 30 * bitmapCopy.width / 800.0f
        for (box in results) {
            boxPaint.color = box.getColor()
            boxPaint.style = Paint.Style.FILL
            canvas.drawText(
                box.getLabel() + java.lang.String.format(
                    Locale.CHINESE,
                    " %.3f",
                    box.getScore()
                ), box.x0 + 3, box.y0 + 30 * bitmapCopy.width / 1000.0f, boxPaint
            )
            boxPaint.style = Paint.Style.STROKE
            canvas.drawRect(box.getRect(), boxPaint)
        }
        return bitmapCopy
    }

    private fun runByPhoto(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Photo error", Toast.LENGTH_SHORT).show()
            return
        }
        if (detectVideo.get()) {
            Toast.makeText(this, "Video is running", Toast.LENGTH_SHORT).show()
            return
        }
        detectPhoto.set(true)
        val image: Bitmap? = getPicture(data.data)
        if (image == null) {
            Toast.makeText(this, "Photo is null", Toast.LENGTH_SHORT).show()
            return
        }

        mCameraProvider.unbindAll()
        binding.viewFinder.visibility = View.INVISIBLE
        binding.btnBack.visibility = View.VISIBLE
        binding.btnBack.setOnClickListener {
            if (detectVideo.get() || detectPhoto.get()) {
                detectPhoto.set(false)
                detectVideo.set(false)
                binding.sbVideo.visibility = View.GONE
                binding.sbVideoSpeed.visibility = View.GONE
                binding.viewFinder.visibility = View.VISIBLE
                binding.btnBack.visibility = View.GONE
                startCamera(binding.viewFinder)
            }
        }

        // 选取的图像太大(25000000这个上限值未求证),大概与内存有关
        // e.g.:"Error: Canvas: trying to draw too large(108000000bytes) bitmap."
        if((image.width * image.height) > 25000000){
            Toast.makeText(this, "Photo is too large", Toast.LENGTH_SHORT).show()
            return
        }

        val thread = Thread({
            val start = System.currentTimeMillis()
            val imageCopy = image.copy(Bitmap.Config.ARGB_8888, true)
            width = image.width
            height = image.height
            mutableBitmap = detectAndDraw(imageCopy)
            val dur = System.currentTimeMillis() - start
            runOnUiThread {
                val modelName = getModelName()
                //binding.imageView.setImageBitmap(mutableBitmap)
                binding.imageView.load(mutableBitmap)
//                {
//                    transformations(BlurTransformation())
//                }
                binding.tvInfo.text = String.format(
                    Locale.CHINESE, "%s\nSize: %dx%d\nTime: %.3f s\nFPS: %.3f",
                    modelName, height, width, dur / 1000.0, 1000.0f / dur
                )
            }
        }, "photo detect")
        thread.start()
    }

    private fun getPicture(selectedImage: Uri?): Bitmap? {
        val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
        val cursor: Cursor =
            //   this.getContentResolver().query(selectedImage, filePathColumn, null, null, null)
            selectedImage?.let { this.contentResolver.query(it, filePathColumn, null, null, null) }
                ?: return null
        cursor.moveToFirst()
        val columnIndex = cursor.getColumnIndex(filePathColumn[0])
        val picturePath = cursor.getString(columnIndex)
        cursor.close()
        val bitmap = BitmapFactory.decodeFile(picturePath) ?: return null
        val rotate: Int = readPictureDegree(picturePath)
        return rotateBitmapByDegree(bitmap, rotate)
    }

    private fun readPictureDegree(path: String?): Int {
        var degree = 0
        try {
            val exifInterface = ExifInterface(path!!)
            val orientation = exifInterface.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> degree = 90
                ExifInterface.ORIENTATION_ROTATE_180 -> degree = 180
                ExifInterface.ORIENTATION_ROTATE_270 -> degree = 270
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return degree
    }

    private fun rotateBitmapByDegree(bm: Bitmap, degree: Int): Bitmap {
        var returnBm: Bitmap? = null
        val matrix = Matrix()
        matrix.postRotate(degree.toFloat())
        try {
            returnBm = Bitmap.createBitmap(
                bm, 0, 0, bm.width,
                bm.height, matrix, true
            )
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
        }
        if (returnBm == null) {
            returnBm = bm
        }
        if (bm != returnBm) {
            bm.recycle()
        }
        return returnBm
    }


    private fun runByVideo(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "Video error", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = data.data
            val cursor: Cursor? = uri?.let { contentResolver.query(it, null, null, null, null) }
            if (cursor != null) {
                cursor.moveToFirst()
                // String imgNo = cursor.getString(0); // 编号
                val v_path = cursor.getString(1) // 文件路径
//                val v_size = cursor.getString(2) // 大小
//                val v_name = cursor.getString(3) // 文件名
                detectOnVideo(v_path)
            } else {
                Toast.makeText(this, "Video is null", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Video is null", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectOnVideo(path: String?) {
        if (detectVideo.get()) {
            Toast.makeText(this, "Video is running", Toast.LENGTH_SHORT).show()
            return
        }
        detectVideo.set(true)
        Toast.makeText(this@MainActivity, "FPS is not accurate!", Toast.LENGTH_SHORT).show()
        binding.sbVideo.visibility = View.VISIBLE
        binding.sbVideoSpeed.visibility = View.VISIBLE
        mCameraProvider.unbindAll()
        binding.viewFinder.visibility = View.INVISIBLE
        binding.btnBack.visibility = View.VISIBLE

        binding.btnBack.setOnClickListener {
            if (detectVideo.get() || detectPhoto.get()) {
                detectPhoto.set(false)
                detectVideo.set(false)
                binding.sbVideo.visibility = View.GONE
                binding.sbVideoSpeed.visibility = View.GONE
                binding.viewFinder.visibility = View.VISIBLE
                binding.btnBack.visibility = View.GONE
                startCamera(binding.viewFinder)
            }
        }

        val thread = Thread({
            mmr = FFmpegMediaMetadataRetriever()
            mmr!!.setDataSource(path)
            val dur: String =
                mmr!!.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_DURATION) // ms
            val sfps: String =
                mmr!!.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_FRAMERATE) // fps
            //                String sWidth = mmr.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);  // w
            //                String sHeight = mmr.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);  // h
            val rota: String =
                mmr!!.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) // rotation
            val duration = dur.toInt()
            val fps = sfps.toFloat()
//            var rotate = 0f
//            if (rota != null) {
//                rotate = rota.toFloat()
//            }
            val rotate = rota.toFloat()
            binding.sbVideo.max = duration * 1000
            var frameDis = 1.0f / fps * 1000 * 1000 * videoSpeed
            videoCurFrameLoc = 0
            while (detectVideo.get() && videoCurFrameLoc < duration * 1000) {
                videoCurFrameLoc = (videoCurFrameLoc + frameDis).toLong()
                binding.sbVideo.progress = videoCurFrameLoc.toInt()
                val b: Bitmap = mmr!!.getFrameAtTime(
                    videoCurFrameLoc,
                    FFmpegMediaMetadataRetriever.OPTION_CLOSEST
                )
                    ?: continue
                val matrix = Matrix()
                matrix.postRotate(rotate)
                width = b.width
                height = b.height
                val bitmap = Bitmap.createBitmap(b, 0, 0, width, height, matrix, false)
                startTime = System.currentTimeMillis()
                detectAndDraw(bitmap.copy(Bitmap.Config.ARGB_8888, true))
                showResultOnUI()
                frameDis = 1.0f / fps * 1000 * 1000 * videoSpeed
            }
            mmr!!.release()
            if (detectVideo.get()) {
                runOnUiThread {
                    binding.sbVideo.visibility = View.GONE
                    binding.sbVideoSpeed.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Video end!", Toast.LENGTH_LONG).show()
                }
            }
            detectVideo.set(false)
        }, "video detect")
        thread.start()
    }


    override fun onDestroy() {
        detectCamera.set(false)
        detectVideo.set(false)
        if (detectService != null) {
            detectService.shutdown()
            detectService = null
        }

        mmr?.release()

       // analyzing = false
        mCameraProvider.unbindAll()
        cameraExecutor.shutdown()
        if (cameraExecutor != null) {
            cameraExecutor.shutdown()
            cameraExecutor = null
        }

        super.onDestroy()

    }



/*
    /**
     * letterbox (slow)
     *
     * @param srcBitmap
     * @param srcWidth
     * @param srcHeight
     * @param dstWidth
     * @param dstHeight
     * @param matrix
     * @return
     */
    fun letterbox(
        srcBitmap: Bitmap?,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        matrix: Matrix
    ): Bitmap? {
        val timeStart = System.currentTimeMillis()
        val scale = Math.min(dstWidth.toFloat() / srcWidth, dstHeight.toFloat() / srcHeight)
        val nw = (srcWidth * scale).toInt()
        val nh = (srcHeight * scale).toInt()
        matrix.postScale(nw.toFloat() / srcWidth, nh.toFloat() / srcHeight)
        val bitmap = Bitmap.createBitmap(srcBitmap!!, 0, 0, srcWidth, srcHeight, matrix, false)
        val newBitmap =
            Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888) //创建和目标相同大小的空Bitmap
        val canvas = Canvas(newBitmap)
        val paint = Paint()
        // 针对绘制bitmap添加抗锯齿
        val pfd = PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.isFilterBitmap = false // 对Bitmap进行滤波处理
        paint.isAntiAlias = true // 设置抗锯齿
        canvas.drawFilter = pfd
        canvas.drawBitmap(
            bitmap, null,
            Rect(
                (dstHeight - nh) / 2, (dstWidth - nw) / 2,
                (dstHeight - nh) / 2 + nh, (dstWidth - nw) / 2 + nw
            ),
            paint
        )
        val timeDur = System.currentTimeMillis() - timeStart
        //        Log.d(TAG, "letterbox time:" + timeDur);
        return newBitmap
    }


     */
}

//******ImageAnalysis Function Test******//
//private class DetectAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
//
//
//    private fun ByteBuffer.toByteArray(): ByteArray {
//        rewind()    // Rewind the buffer to zero
//        val data = ByteArray(remaining())
//        get(data)   // Copy the buffer into a byte array
//        return data // Return the byte array
//    }
//
//    override fun analyze(image: ImageProxy) {
//
//        val buffer = image.planes[0].buffer
//        val data = buffer.toByteArray()
//        val pixels = data.map { it.toInt() and 0xFF }
//        val luma = pixels.average()
//
//        listener(luma)
//
//        image.close()
//    }
//}
