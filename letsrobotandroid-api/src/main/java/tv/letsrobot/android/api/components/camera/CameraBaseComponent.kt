package tv.letsrobot.android.api.components.camera

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.github.hiteshsondhi88.libffmpeg.FFmpeg
import com.github.hiteshsondhi88.libffmpeg.FFmpegExecuteResponseHandler
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException
import com.google.common.util.concurrent.RateLimiter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import tv.letsrobot.android.api.enums.ComponentStatus
import tv.letsrobot.android.api.interfaces.Component
import tv.letsrobot.android.api.models.CameraSettings
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Abstracted class for different camera implementations
 */
abstract class CameraBaseComponent(context: Context, val config: CameraSettings) : Component(context), FFmpegExecuteResponseHandler {
    internal var ffmpegRunning = AtomicBoolean(false)
    protected var ffmpeg : FFmpeg = FFmpeg.getInstance(context)
    protected var UUID = java.util.UUID.randomUUID().toString()
    var process : Process? = null
    protected var port: String? = null
    protected var host: String? = null
    protected var streaming = AtomicBoolean(false)
    protected var previewRunning = false
    protected var width = config.width
    protected var height = config.height
    protected var bitrateKb = config.bitrate

    //limits pushes to ffmpeg
    protected var limiter = RateLimiter.create(config.frameRate.toDouble())
    protected val cameraActive = AtomicBoolean(false)
    private val cameraPacketNumber = AtomicLong(1)
    protected var successCounter: Int = 0

    private var handlerThread = HandlerThread("CameraProcessing")
    var handler: Handler
    init {
        handlerThread.start()
        handler = Handler(handlerThread.looper){ message ->
            when(message.what){
                CAMERA_PUSH -> {
                    (message.obj as? CameraPackage)?.takeIf {
                        it.packetId == cameraPacketNumber.get()
                    }?.let {
                        if(streaming.get() && limiter.tryAcquire()) {
                            if (!ffmpegRunning.getAndSet(true)) {
                                bootFFMPEG()
                            }
                            process?.let { _process ->
                                (it.b as? ByteArray)?.let { _ ->
                                    when (it.format) {
                                        ImageFormat.JPEG -> {
                                            _process.outputStream.write(it.b)
                                        }
                                        ImageFormat.NV21 -> {
                                            val im = YuvImage(it.b, it.format, width, height, null)
                                            try {
                                                im.compressToJpeg(it.r, 100, _process.outputStream)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                        else -> {
                                        }
                                    }
                                } ?: (it.b as? Bitmap)?.let { image ->
                                    try {
                                        image.compress(Bitmap.CompressFormat.JPEG, 100, _process.outputStream)
                                    } catch (e: Exception) {
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
            return@Handler true
        }
    }


    //override getName so all of the camera classes have the same name
    override fun getName(): String {
        return CameraBaseComponent.EVENTNAME
    }

    override fun enableInternal() {
        try {
            val client = OkHttpClient.Builder()
                    .build()
            var call = client.newCall(Request.Builder().url(String.format("https://letsrobot.tv/get_video_port/%s", config.cameraId)).build())
            var response = call.execute()
            if (response.body() != null) {
                val `object` = JSONObject(response.body()!!.string())
                Log.d("ROBOT", `object`.toString())
                port = `object`.getString("mpeg_stream_port")
            }
            call = client.newCall(Request.Builder().url(String.format("https://letsrobot.tv/get_websocket_relay_host/%s", config.cameraId)).build())
            response = call.execute()
            if (response.body() != null) {
                val `object` = JSONObject(response.body()!!.string())
                Log.d("ROBOT", `object`.toString())
                host = `object`.getString("host")
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        if(host == null || port == null){
            status = ComponentStatus.ERROR
        }
        else
            streaming.set(true)
    }

    override fun disableInternal() {
        streaming.set(false)
    }

    private data class CameraPackage(val b : Any?,val format : Int,val r : Rect?, val packetId : Long)

    fun push(b : Any?, format : Int, r : Rect?){
        handler.obtainMessage(CAMERA_PUSH,
                CameraPackage(b, format, r, cameraPacketNumber.incrementAndGet())).sendToTarget()
    }

    /**
     * Allow overlay of images. Can mess around with canvas drawing too
     */
    private fun overlay(bmp1: Bitmap, bmp2: Bitmap?): Bitmap {
        val bmOverlay = Bitmap.createBitmap(bmp1.width, bmp1.height, bmp1.config)
        val canvas = Canvas(bmOverlay)
        canvas.drawBitmap(bmp1, Matrix(), null)
        bmp2?.let {
            canvas.drawBitmap(bmp2, Matrix(), null)
        }
        /*var msg = "Testing Camera..."
        var paint = Paint()
        paint.color = Color.RED
        paint.textSize = 20f
        canvas.drawText(msg, 100f, 100f, paint)*/
        return bmOverlay
    }

    fun byteArrayPush(b : ByteArray, format : Int, r : Rect){

    }

    fun bootFFMPEG(){
        if(!streaming.get()){
            ffmpegRunning.set(false)
            status = ComponentStatus.DISABLED
            return
        }
        successCounter = 0
        status = ComponentStatus.CONNECTING
        try {
            // to execute "ffmpeg -version" command you just need to pass "-version"
            val xres = width
            val yres = height

            val rotationOption = config.orientation.ordinal //leave blank
            val builder = StringBuilder()
            for (i in 0..rotationOption){
                if(i == 0) builder.append("-vf transpose=1")
                else builder.append(",transpose=1")
            }
            print("\"$builder\"")
            val kbps = bitrateKb
            val video_host = host
            val video_port = port
            val stream_key = config.pass
            //TODO hook up with resolution prefs
            val command = "-f image2pipe -codec:v mjpeg -i - -f mpegts -framerate ${config.frameRate} -codec:v mpeg1video -b ${kbps}k -minrate ${kbps}k -maxrate ${kbps}k -bufsize ${kbps/1.5}k -bf 0 -tune zerolatency -preset ultrafast -pix_fmt yuv420p $builder http://$video_host:$video_port/$stream_key/$xres/$yres/"
            ffmpeg.execute(UUID, null, command.split(" ").toTypedArray(), this)
        } catch (e: FFmpegCommandAlreadyRunningException) {
            e.printStackTrace()
            // Handle if FFmpeg is already running
        }
    }

    override fun onStart() {
        ffmpegRunning.set(true)
        @Suppress("ConstantConditionIf")
        if(shouldLog)
            Log.d(LOGTAG, "onStart")
    }

    override fun onProgress(message: String?) {
        @Suppress("ConstantConditionIf")
        if(shouldLog)
            Log.d(LOGTAG, "onProgress : $message")
        successCounter++
        status = when {
            successCounter > 5 -> ComponentStatus.STABLE
            successCounter > 2 -> ComponentStatus.INTERMITTENT
            else -> ComponentStatus.CONNECTING
        }
    }

    override fun onFailure(message: String?) {
        Log.e(LOGTAG, "progress : $message")
        status = ComponentStatus.ERROR
    }

    override fun onSuccess(message: String?) {
        @Suppress("ConstantConditionIf")
        if(shouldLog)
            Log.d(LOGTAG, "onSuccess : $message")
    }

    override fun onFinish() {
        @Suppress("ConstantConditionIf")
        if(shouldLog)
            Log.d(LOGTAG, "onFinish")
        ffmpegRunning.set(false)
        process?.destroy()
        process = null
        status = ComponentStatus.DISABLED
    }

    override fun onProcess(p0: Process?) {
        @Suppress("ConstantConditionIf")
        if(shouldLog)
            Log.d(LOGTAG, "onProcess")
        this.process = p0
    }

    companion object {
        const val LOGTAG = "CameraComponent"
        protected const val shouldLog = true
        const val EVENTNAME = "CameraComponent"
        private const val CAMERA_PUSH = 0
    }
}