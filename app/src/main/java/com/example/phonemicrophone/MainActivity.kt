package com.example.phonemicrophone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private var isConnected = false
    private lateinit var button: Button
    private var socket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private var sampleRate = 48000
    private var bufferSize = 0

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("MicApp", "RECORD_AUDIO permission granted")
                toggleWebSocket()
            } else {
                Log.e("MicApp", "RECORD_AUDIO permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MicApp", "onCreate called")

        sampleRate = getSupportedSampleRate()
        bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        button = Button(this).apply {
            text = getString(R.string.mic_off)
            setBackgroundColor(Color.RED)
            setOnClickListener {
                Log.d("MicApp", "Button clicked")
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    toggleWebSocket()
                }
            }
        }

        setContentView(button)
    }

    private fun getSupportedSampleRate(): Int {
        val candidates = listOf(48000, 44100)
        for (rate in candidates) {
            val result = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (result > 0) {
                Log.d("MicApp", "Using supported sample rate: $rate")
                return rate
            }
        }
        Log.w("MicApp", "No preferred sample rate found, using default 44100")
        return 44100
    }

    private fun toggleWebSocket() {
        if (isConnected) {
            Log.d("MicApp", "Closing WebSocket")
            isConnected = false
            stopAudioRecording()
            socket?.close(1000, "User closed connection")
            button.setBackgroundColor(Color.RED)
            button.text = getString(R.string.mic_off)
        } else {
            Log.d("MicApp", "Opening WebSocket")
            val request = Request.Builder()
                .url("ws://alan-laptop-arch:2000/ws")
                .build()

            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("MicApp", "WebSocket opened")
                    runOnUiThread {
                        isConnected = true
                        button.setBackgroundColor(Color.GREEN)
                        button.text = getString(R.string.mic_on)
                    }
                    webSocket.send("request")
                    val config = JSONObject()
                    config.put("type", "config")
                    config.put("rate", sampleRate)
                    webSocket.send(config.toString())
                    startAudioRecording()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("MicApp", "WebSocket failed: ${t.message}", t)
                    runOnUiThread {
                        isConnected = false
                        button.setBackgroundColor(Color.RED)
                        button.text = getString(R.string.mic_off)
                    }
                    stopAudioRecording()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("MicApp", "WebSocket closed: $reason")
                    runOnUiThread {
                        isConnected = false
                        button.setBackgroundColor(Color.RED)
                        button.text = getString(R.string.mic_off)
                    }
                    stopAudioRecording()
                }
            })
        }
    }

    private fun startAudioRecording() {
        Log.d("MicApp", "Starting audio recording")
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true

            Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording && audioRecord != null) {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        socket?.send(buffer.toByteString(0, read))
                    }
                }
            }.start()
        } catch (e: SecurityException) {
            Log.e("MicApp", "SecurityException while starting recording", e)
        }
    }

    private fun stopAudioRecording() {
        Log.d("MicApp", "Stopping audio recording")
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.e("MicApp", "Error stopping audio recording", e)
        }
        audioRecord?.release()
        audioRecord = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MicApp", "onDestroy called - closing WebSocket")
        socket?.close(1000, null)
        stopAudioRecording()
    }
}