package com.example.image_manager

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Fragment
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.image_manager.AspectRatioConstant.*
import com.example.image_manager.ImageSizeConstant.*
import com.soundcloud.android.crop.Crop
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class ImageManager {
    
    private var mTakePhotoTempFile: File? = null
    private var mPhotoFile: File? = null
    private var mVideoFile: File? = null
    private var mPhotoPath: String? = null
    private var mPhotoUrl: String? = null
    private var mVideoPath: String? = null
    private var mFragmentSupport: Fragment? = null
    private var mFragment: Fragment? = null
    private var mActivity: Activity? = null
    private var imageSizeConstant = IMAGE_DEFAULT_SIZE
    private var aspectRatioConstant = RATIO_CUSTOM
    
    private val authority: String
    private val mCtx: Context
    private val directoryName: String
    
    constructor(fragment: Fragment, authority: String, directoryName: String) {
        mFragmentSupport = fragment
        mCtx = fragment.activity
        this.authority = authority
        this.directoryName = directoryName
    }
    
    constructor(activity: Activity, authority: String, directoryName: String) {
        mActivity = activity
        mCtx = activity
        this.authority = authority
        this.directoryName = directoryName
    }
    
    /**
     * set aspect ratio for the crop
     *
     * @param aspectRatioConstant ratio for cropping
     */
    fun setAspectRatio(aspectRatioConstant: AspectRatioConstant): ImageManager {
        this.aspectRatioConstant = aspectRatioConstant
        return this
    }
    
    /**
     * set image size after crop
     *
     * @param imageSizeConstant size for image
     */
    fun setImageSize(imageSizeConstant: ImageSizeConstant): ImageManager {
        this.imageSizeConstant = imageSizeConstant
        return this
    }
    
    /**
     * Build intent to perform user crop image
     *
     * @param picUri - image to be cropped
     */
    private fun cropImage(picUri: Uri) {
        startCropActivity(picUri, RequestCodes.CROP)
        Log.d("myLog", "Going to crop image with URI = $picUri")
    }
    
    /**
     * Init system picker to pick image from camera or gallery
     */
    @SuppressLint("IntentReset")
    fun getImage(fileName: String, chooserTitle: String = "Take or select a picture") {
        val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        pickIntent.type = MIME_TYPE_IMAGE
        var uri: Uri? = null
        kotlin.runCatching {
            uri = FileProvider.getUriForFile(
                mCtx.applicationContext, authority, createImageFile(fileName)
            )
        }
        val takePhotoIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        takePhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        
        val chooserIntent = Intent.createChooser(pickIntent, chooserTitle)
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePhotoIntent))
        startActivityForResult(chooserIntent, RequestCodes.IMAGE)
    }
    
    fun getVideoFromCamera(fileName: String) {
        var uri: Uri? = null
        kotlin.runCatching {
            uri = FileProvider.getUriForFile(mCtx, authority, createVideoFile(fileName))
        }
        val takeVideoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        takeVideoIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        startActivityForResult(takeVideoIntent, RequestCodes.VIDEO)
    }
    
    fun getVideoPath(): String? {
        return mVideoPath
    }
    
    fun getImagePath(): String? {
        return mPhotoUrl
    }
    
    fun getImageFile(): File? {
        return mPhotoFile
    }
    
    fun getVideoFile(): File? {
        return mVideoFile
    }
    
    @SuppressLint("IntentReset")
    fun getImageFromGallery() {
        val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        pickIntent.type = MIME_TYPE_IMAGE
        startActivityForResult(pickIntent, RequestCodes.GALLERY)
    }
    
    fun getImageFromCamera(fileName: String) {
        var uri: Uri? = null
        kotlin.runCatching {
            uri = FileProvider.getUriForFile(mCtx, authority, createImageFile(fileName))
        }
        val takePhotoIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        takePhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        startActivityForResult(takePhotoIntent, RequestCodes.IMAGE)
    }
    
    /**
     * add water mark for image
     */
    private fun markImage(file: File?, waterMarkResId: Int): File {
        val src = BitmapFactory.decodeFile(file.toString())
        val w = src.width
        val h = src.height
        val result = Bitmap.createBitmap(w, h, src.config)
        val canvas = Canvas(result)
        canvas.drawBitmap(src, 0f, 0f, null)
        val waterMark = BitmapFactory.decodeResource(mCtx.resources, waterMarkResId)
        canvas.drawBitmap(waterMark, 20f, 20f, null)
        val date = Calendar.getInstance().time
        val simpleDateFormat = SimpleDateFormat("E yyyy.MM.dd HH:mm", Locale.getDefault())
        val waterText = simpleDateFormat.format(date)
        val paint = Paint()
        paint.textSize = 100f
        paint.isAntiAlias = true
        val width = (w / 3.5).toInt()
        canvas.drawText(waterText, width.toFloat(), (h - 10).toFloat(), paint)
        return writeFileFromBitmap(result)
    }
    
    /**
     * get image and then rotate it if it needed and start crop activity
     *
     * @param resultCode result code
     * @param data       data which given from intent
     */
    fun handleFullSizeImage(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val bitmap: Bitmap
            if (data != null && data.data != null) {
                val selectedPicture = data.data ?: return
                val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
                val cursor = mCtx.contentResolver.query(selectedPicture, filePathColumn, null, null, null) ?: return
                cursor.moveToFirst()
                val columnIndex = cursor.getColumnIndex(filePathColumn[0])
                val picturePath = cursor.getString(columnIndex)
                cursor.close()
                val loadedBitmap = BitmapFactory.decodeFile(picturePath)
                val exif: ExifInterface? = try {
                    val pictureFile = File(picturePath)
                    ExifInterface(pictureFile.absolutePath)
                } catch (ignored: Exception) {
                    null
                }
                var orientation = ExifInterface.ORIENTATION_NORMAL
                if (exif != null)
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                bitmap = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(loadedBitmap, 90)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(loadedBitmap, 180)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(loadedBitmap, 270)
                    else -> loadedBitmap
                }
                mTakePhotoTempFile = writeFileFromBitmap(bitmap)
                cropImage(Uri.fromFile(mTakePhotoTempFile))
            } else {
                val loadedBitmap: Bitmap
                if (mTakePhotoTempFile != null) {
                    loadedBitmap = getBitmapFromUri(Uri.parse(mTakePhotoTempFile!!.absolutePath)) ?: return
                } else {
                    val imageUri = Uri.parse(mPhotoUrl)
                    mTakePhotoTempFile = File(imageUri.path.toString())
                    loadedBitmap = try {
                        val ims: InputStream = FileInputStream(mTakePhotoTempFile)
                        BitmapFactory.decodeStream(ims)
                    } catch (e: FileNotFoundException) {
                        return
                    }
                }
                val ei: ExifInterface = try {
                    ExifInterface(mTakePhotoTempFile!!.absolutePath)
                } catch (e: Exception) {
                    return
                }
                val orientation = ei.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
                )
                bitmap = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(loadedBitmap, 90)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(loadedBitmap, 180)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(loadedBitmap, 270)
                    else -> loadedBitmap
                }
                mTakePhotoTempFile = writeFileFromBitmap(bitmap)
                cropImage(Uri.fromFile(mTakePhotoTempFile))
            }
        }
    }
    
    /**
     * @param uri uri of image
     * @return return bitmap of image from uri
     */
    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        mCtx.contentResolver.notifyChange(uri, null)
        val cr = mCtx.contentResolver
        val bitmap: Bitmap
        return try {
            bitmap = MediaStore.Images.Media.getBitmap(cr, uri)
            bitmap
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * @param mBitmap bitmap which need to be saved in cache or gallery dir
     * @return return .jpg file of bitmap
     */
    private fun writeFileFromBitmap(mBitmap: Bitmap): File {
        val fileCreated: Boolean
        val f = File(createDirectory(), "image_" + System.currentTimeMillis() + ".jpg")
        try {
            fileCreated = f.createNewFile()
            Log.i("IMAGE_MANAGER", "file created : = $fileCreated")
        } catch (ignored: IOException) {
        }
        val bos = ByteArrayOutputStream()
        mBitmap.compress(Bitmap.CompressFormat.JPEG, 100 /*ignored for PNG*/, bos)
        val bitmapdata = bos.toByteArray()
        val fos: FileOutputStream
        try {
            fos = FileOutputStream(f)
            fos.write(bitmapdata)
            fos.flush()
            fos.close()
        } catch (ignored: Exception) {
        }
        return f
    }
    
    /**
     * Handle cropped image and cache it
     *
     * @return path of cropped image in cache directory
     */
    fun handleCroppedImage(resultCode: Int, mark: Boolean, waterMarkResId: Int = 0, imageTitle: String = ""): String? {
        if (resultCode == Activity.RESULT_OK) {
            val absoulutePath: String = if (mark) {
                addPhoto(markImage(mTakePhotoTempFile, waterMarkResId), imageTitle).toString()
            } else {
                val src = BitmapFactory.decodeFile(mTakePhotoTempFile.toString())
                addPhoto(writeFileFromBitmap(src), imageTitle).toString()
            }
            mPhotoFile?.delete()
            mTakePhotoTempFile?.delete()
            mTakePhotoTempFile = null
            return absoulutePath
        }
        return null
    }
    
    /**
     * Start activity based on root element
     *
     * @param intent      configured intent to start activity
     * @param requestCode request code
     */
    private fun startActivityForResult(intent: Intent, requestCode: Int) {
        if (mActivity != null)
            mActivity?.startActivityForResult(intent, requestCode)
        else if (mFragment != null)
            mFragment?.startActivityForResult(intent, requestCode)
        else if (mFragmentSupport != null)
            mFragmentSupport?.startActivityForResult(intent, requestCode)
    }
    
    /**
     * Start crop activity base on root element and crop image
     *
     * @param picUri      uri of image that need to be cropped
     * @param requestCode request code
     */
    private fun startCropActivity(picUri: Uri, requestCode: Int) {
        val weight = getHeight()
        val height = getWeight()
        if (weight == 0 || height == 0) {
            cropWithMaxSize(picUri, requestCode, weight, height)
        } else {
            cropWithCurrentSize(picUri, requestCode)
        }
    }
    
    private fun getHeight(): Int {
        return when (imageSizeConstant) {
            IMAGE_640_360 -> 360
            IMAGE_854_480 -> 480
            IMAGE_1280_720 -> 720
            IMAGE_1920_1080 -> 1080
            IMAGE_2560_1440 -> 1440
            IMAGE_SQUARE_256 -> 256
            IMAGE_SQUARE_512 -> 512
            IMAGE_SQUARE_640 -> 640
            IMAGE_SQUARE_720 -> 720
            IMAGE_SQUARE_1024 -> 1024
            IMAGE_SQUARE_2048 -> 2048
            IMAGE_DEFAULT_SIZE -> 0
        }
    }
    
    private fun getWeight(): Int {
        return when (imageSizeConstant) {
            IMAGE_640_360 -> 640
            IMAGE_854_480 -> 854
            IMAGE_1280_720 -> 1280
            IMAGE_1920_1080 -> 1920
            IMAGE_2560_1440 -> 2560
            IMAGE_SQUARE_256 -> 256
            IMAGE_SQUARE_512 -> 512
            IMAGE_SQUARE_640 -> 640
            IMAGE_SQUARE_720 -> 720
            IMAGE_SQUARE_1024 -> 1024
            IMAGE_SQUARE_2048 -> 2048
            IMAGE_DEFAULT_SIZE -> 0
        }
    }
    
    private fun cropWithMaxSize(picUri: Uri, requestCode: Int, weight: Int, height: Int) {
        when (aspectRatioConstant) {
            RATIO_1_1 -> cropSquare(picUri, requestCode, weight, height)
            RATIO_4_3 -> cropRectangle(picUri, requestCode, weight, height, 4, 3)
            RATIO_16_9 -> cropRectangle(picUri, requestCode, weight, height, 16, 9)
            RATIO_CUSTOM -> crop(picUri, requestCode, weight, height)
        }
    }
    
    private fun cropWithCurrentSize(picUri: Uri, requestCode: Int) {
        when (aspectRatioConstant) {
            RATIO_1_1 -> cropSquare(picUri, requestCode)
            RATIO_4_3 -> cropRectangle(picUri, requestCode, 4, 3)
            RATIO_16_9 -> cropRectangle(picUri, requestCode, 16, 9)
            RATIO_CUSTOM -> crop(picUri, requestCode)
        }
    }
    
    private fun cropSquare(picUri: Uri, requestCode: Int, weight: Int, height: Int) {
        if (mActivity != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).asSquare().withMaxSize(weight, height)
                .start(mActivity, requestCode)
        } else if (mFragment != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).asSquare().withMaxSize(weight, height)
                .start(mFragment!!.activity, mFragment, requestCode)
        } else if (mFragmentSupport != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).asSquare().withMaxSize(weight, height)
                .start(mFragmentSupport!!.activity, mFragmentSupport, requestCode)
        }
    }
    
    private fun cropSquare(picUri: Uri, requestCode: Int) {
        if (mActivity != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).asSquare()
                .start(mActivity, requestCode)
        } else if (mFragment != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).asSquare()
                .start(mFragment!!.activity, mFragment, requestCode)
        } else if (mFragmentSupport != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).asSquare()
                .start(mFragmentSupport!!.activity, mFragmentSupport, requestCode)
        }
    }
    
    private fun cropRectangle(picUri: Uri, requestCode: Int, weight: Int, height: Int, x: Int, y: Int) {
        if (mActivity != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).withAspect(x, y).withMaxSize(weight, height)
                .start(mActivity, requestCode)
        } else if (mFragment != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).withAspect(x, y).withMaxSize(weight, height)
                .start(mFragment!!.activity, mFragment, requestCode)
        } else if (mFragmentSupport != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).withAspect(x, y).withMaxSize(weight, height)
                .start(mFragmentSupport!!.activity, mFragmentSupport, requestCode)
        }
    }
    
    private fun cropRectangle(picUri: Uri, requestCode: Int, x: Int, y: Int) {
        if (mActivity != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).withAspect(x, y)
                .start(mActivity, requestCode)
        } else if (mFragment != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).withAspect(x, y)
                .start(mFragment!!.activity, mFragment, requestCode)
        } else if (mFragmentSupport != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).withAspect(x, y)
                .start(mFragmentSupport!!.activity, mFragmentSupport, requestCode)
        }
    }
    
    private fun crop(picUri: Uri, requestCode: Int, weight: Int, height: Int) {
        if (mActivity != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).withMaxSize(weight, height)
                .start(mActivity, requestCode)
        } else if (mFragment != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).withMaxSize(weight, height)
                .start(mFragment!!.activity, mFragment, requestCode)
        } else if (mFragmentSupport != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile)).withMaxSize(weight, height)
                .start(mFragmentSupport!!.activity, mFragmentSupport, requestCode)
        }
    }
    
    private fun crop(picUri: Uri, requestCode: Int) {
        if (mActivity != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile))
                .start(mActivity, requestCode)
        } else if (mFragment != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile))
                .start(mFragment!!.activity, mFragment, requestCode)
        } else if (mFragmentSupport != null) {
            Crop.of(picUri, Uri.fromFile(mTakePhotoTempFile))
                .start(mFragmentSupport!!.activity, mFragmentSupport, requestCode)
        }
    }
    
    /**
     * @param bitmap  bitmap which need to rotate
     * @param degrees how much degrees we need to rotate needed bitmap
     * @return return rotated bitmap
     */
    private fun rotateBitmap(bitmap: Bitmap?, degrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap!!, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    private fun addPhoto(imageFile: File, title: String): Uri? {
        val values = ContentValues(3)
        values.put(MediaStore.Images.Media.TITLE, title)
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpg")
        values.put(MediaStore.Images.Media.DATA, imageFile.absolutePath)
        return mCtx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }
    
    private fun createDirectory(): File {
        val storageDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            directoryName
        )
        if (!storageDir.exists())
            storageDir.mkdirs()
        return storageDir
    }
    
    @Throws(IOException::class)
    private fun createVideoFile(fileName: String): File {
        val video = File.createTempFile(
            fileName,  /* prefix */
            ".mp4",  /* suffix */
            createDirectory() /* directory */
        )
        mVideoFile = video
        return video
    }
    
    @Throws(IOException::class)
    private fun createImageFile(fileName: String): File {
        val image = File.createTempFile(
            fileName,  /* prefix */
            ".jpg",  /* suffix */
            createDirectory() /* directory */
        )
        // Save a file: path for use with ACTION_VIEW intents
        mPhotoUrl = "file:" + image.absolutePath
        mPhotoFile = image
        /*
          if we need to save the original photo we use this code below, and change returned type to Url
         */
//        Uri uri = addPhoto(image);
//        Log.d("myTag", "addImage: " + uri);
//        mPhotoPath = String.valueOf(uri);
        return image
    }
    
    fun checkPermissions(requestCode: Int) {
        if (mActivity != null)
            checkPermissionsActivity(requestCode)
        else if (mFragment != null)
            checkPermissionsFragment(requestCode)
        else if (mFragmentSupport != null)
            checkPermissionsFragmentSupport(requestCode)
    }
    
    private fun checkPermissionsFragment(requestCode: Int) {
        if (isPermissionsDenied()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                mFragment?.requestPermissions(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ),
                    requestCode
            )
        }
    }
    
    private fun checkPermissionsActivity(requestCode: Int) {
        if (isPermissionsDenied()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                mActivity?.requestPermissions(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ),
                    requestCode
            )
        }
    }
    
    private fun checkPermissionsFragmentSupport(requestCode: Int) {
        if (isPermissionsDenied()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                mFragmentSupport?.requestPermissions(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ),
                    requestCode
            )
        }
    }
    
    fun isPermissionsDenied(): Boolean =
        ActivityCompat.checkSelfPermission(mCtx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(mCtx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(mCtx, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    
    companion object {
        private const val MIME_TYPE_IMAGE = "image/*"
    }
}