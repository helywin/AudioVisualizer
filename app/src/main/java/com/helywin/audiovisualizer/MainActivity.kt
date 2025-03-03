package com.helywin.audiovisualizer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // 不再直接使用 mediaProjection 变量，而从 state 获取
    private val mediaProjectionState = mutableStateOf<MediaProjection?>(null)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                val mp = mediaProjectionManager.getMediaProjection(result.resultCode, intent)
                if (mp == null) {
                    Log.d("MainActivity", "mediaProjection 为空")
                } else {
                    Log.d("MainActivity", "mediaProjection 非空")
                }
                mediaProjectionState.value = mp
            }
        } else {
            Log.d("MainActivity", "用户拒绝了录屏权限")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求 RECORD_AUDIO 权限
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        // 请求 FOREGROUND_SERVICE
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.FOREGROUND_SERVICE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.FOREGROUND_SERVICE),
                1
            )
        }

        // 请求 FOREGROUND_SERVICE_MEDIA_PROJECTION
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION),
                1
            )
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceIntent = Intent(this, MediaProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            mediaProjectionLauncher.launch(captureIntent)
        }

        setContent {
            MaterialTheme {
                WaveformScreen(mediaProjection = mediaProjectionState.value)
            }
        }
    }

    @Composable
    fun WaveformView(pcmData: ShortArray, modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val width = size.width
            val height = size.height
            // 如果 pcmData 都为 0 或空，则用 1f 避免除 0
            val maxAmplitude = pcmData.maxOrNull()?.toFloat()?.takeIf { it > 0 } ?: 1f
            val scaleY = height / 2f / maxAmplitude
            // 根据压缩系数调整步长，compressFactor 小于 1 意味着 x 轴压缩
            val compressFactor = 1f
            val stepX = if (pcmData.isNotEmpty()) width / pcmData.size.toFloat() else 0f

            drawIntoCanvas { canvas ->
                val paint = androidx.compose.ui.graphics.Paint().apply {
                    color = Color.Green
                    strokeWidth = 2f
                    style = PaintingStyle.Stroke
                }
                for (i in pcmData.indices) {
                    // 对 x 坐标应用压缩系数
                    val x = i * stepX * compressFactor
                    val y = height / 2f - pcmData[i] * scaleY
                    canvas.drawLine(Offset(x, height / 2f), Offset(x, y), paint)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Composable
    fun WaveformScreen(mediaProjection: MediaProjection?) {
        val pcmData = remember { mutableStateOf(shortArrayOf()) }
        // 使用 mediaProjection 作为 key，若 mediaProjection 更新将重新执行 LaunchedEffect
        LaunchedEffect(mediaProjection) {
            if (mediaProjection == null) {
                Log.d("WaveformScreen", "mediaProjection 为空，captureAudioFlow 未调用")
            } else {
                Log.d("WaveformScreen", "mediaProjection 非空，启动 captureAudioFlow")
                captureAudioFlow(mediaProjection).collect { data ->
                    pcmData.value = data
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text("PCM Waveform")
                Spacer(modifier = Modifier.height(16.dp))
                WaveformView(
                    pcmData = pcmData.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Black)
                )
                Spacer(modifier = Modifier.height(16.dp))
                SpectrumView(
                    pcmData = pcmData.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Black)
                )
            }
        }
    }

    // 在 MainActivity 中新增一个持续捕获音频数据的 Flow
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun captureAudioFlow(mediaProjection: MediaProjection): Flow<ShortArray> = flow {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setChannelMask(channelConfig)
                    .setSampleRate(sampleRate)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        Log.d("captureAudioFlow", "调用 startRecording()")
        audioRecord.startRecording()

        val audioData = ShortArray(bufferSize)
        try {
            while (true) {
                val read = audioRecord.read(audioData, 0, bufferSize)
                Log.d("captureAudioFlow", "read 返回值：$read")
                if (read > 0) {
                    val data = audioData.copyOf(read)
                    Log.d("captureAudioFlow", "读取到数据：${data.joinToString()}")
                    emit(data)
                } else {
                    Log.d("captureAudioFlow", "未读取到数据或发生错误")
                }
            }
        } finally {
            Log.d("captureAudioFlow", "停止录音并释放资源")
            audioRecord.stop()
            audioRecord.release()
        }
    }.flowOn(Dispatchers.IO)

    @Composable
    fun SpectrumView(pcmData: ShortArray, modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val width = size.width
            val height = size.height

            if (pcmData.isEmpty()) return@Canvas

            // 取数据长度为 2 的幂，避免过长数据
            val fftSize = pcmData.size.coerceAtMost(1024)
            val samples = FloatArray(fftSize) { i -> pcmData.getOrElse(i) { 0 }.toFloat() }

            // 初始化实部和虚部数组
            val real = samples.copyOf()
            val imag = FloatArray(fftSize) { 0f }

            // 调用 FFT 算法
            fft(real, imag)

            // 只取前半部分结果
            val magnitudes = FloatArray(fftSize / 2)
            for (i in magnitudes.indices) {
                magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
            }
            val maxMagnitude = magnitudes.maxOrNull() ?: 1f
            val barWidth = width / magnitudes.size.toFloat()
            drawIntoCanvas { canvas ->
                val paint = androidx.compose.ui.graphics.Paint().apply {
                    color = Color.Green
                    strokeWidth = 2f
                    style = PaintingStyle.Stroke
                }
                for (i in magnitudes.indices) {
                    val x = i * barWidth + barWidth / 2
                    val barHeight = (magnitudes[i] / maxMagnitude) * height
                    canvas.drawLine(
                        Offset(x, height),
                        Offset(x, height - barHeight),
                        paint = paint
                    )
                }
            }
        }
    }

    /**
     * 简单的 FFT 实现，要求数据长度为 2 的幂。
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n == 0) return
        val bits = (log2(n.toDouble())).toInt()

        // 位反转排序
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
        }
        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val tableStep = n / size
            for (i in 0 until n step size) {
                for (j in 0 until halfSize) {
                    val k = j * tableStep
                    val angle = -2 * Math.PI * k / n
                    val cosVal = cos(angle).toFloat()
                    val sinVal = sin(angle).toFloat()
                    val tpre = real[i + j + halfSize] * cosVal - imag[i + j + halfSize] * sinVal
                    val tpim = real[i + j + halfSize] * sinVal + imag[i + j + halfSize] * cosVal
                    real[i + j + halfSize] = real[i + j] - tpre
                    imag[i + j + halfSize] = imag[i + j] - tpim
                    real[i + j] += tpre
                    imag[i + j] += tpim
                }
            }
            size *= 2
        }
    }
}