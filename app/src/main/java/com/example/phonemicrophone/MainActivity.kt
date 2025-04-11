package com.example.phonemicrophone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private var isConnected = false
    private lateinit var toggleButton: Button
    private lateinit var settingsButton: Button
    private lateinit var hostInput: EditText
    private var socket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private var sampleRate = 48000
    private var bufferSize = 0
    private var inSettings = false
    private var hostname = "mic.local"

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

        val chunkDurationMs = 5  // Match AudioWorklet-style chunk size
        bufferSize = (sampleRate * chunkDurationMs / 1000.0).toInt() * 2  // 2 bytes/sample for 16-bit mono

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        toggleButton = Button(this).apply {
            text = getString(R.string.mic_off)
            setBackgroundColor(Color.RED)
            setTextColor(Color.BLACK)
            textSize = 40f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
            setOnClickListener {
                if (!inSettings) {
                    Log.d("MicApp", "Toggle button clicked")
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
        }

        hostInput = EditText(this).apply {
            hint = "hostname:port"
            setText(hostname)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = EditText.GONE
        }

        settingsButton = Button(this).apply {
            text = getString(R.string.settings)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                inSettings = !inSettings
                if (inSettings) {
                    hostInput.visibility = EditText.VISIBLE
                    text = getString(R.string.save)
                } else {
                    hostInput.visibility = EditText.GONE
                    hostname = hostInput.text.toString()
                    text = getString(R.string.settings)
                }
            }
        }

        layout.addView(toggleButton)
        layout.addView(hostInput)
        layout.addView(settingsButton)

        setContentView(layout)
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
        Log.w("MicApp", "No preferred sample rate found, using default 48000")
        return 48000
    }

    private fun toggleWebSocket() {
        if (isConnected) {
            Log.d("MicApp", "Closing WebSocket")
            isConnected = false
            stopAudioRecording()
            socket?.close(1000, "User closed connection")
            toggleButton.setBackgroundColor(Color.RED)
            toggleButton.text = getString(R.string.mic_off)
        } else {
            val wsUrl = "ws://$hostname/ws"
            Log.d("MicApp", "Opening WebSocket to $wsUrl")
            val request = Request.Builder().url(wsUrl).build()

            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("MicApp", "WebSocket opened")
                    runOnUiThread {
                        isConnected = true
                        toggleButton.setBackgroundColor(Color.GREEN)
                        toggleButton.text = getString(R.string.mic_on)
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
                        toggleButton.setBackgroundColor(Color.RED)
                        toggleButton.text = getString(R.string.mic_off)

                        val errorMsg = if (t.message?.contains("Unable to resolve host") == true) {
                            "Could not resolve host: $hostname"
                        } else {
                            "WebSocket failed: ${t.message}"
                        }

                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                    }
                    stopAudioRecording()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("MicApp", "WebSocket closed: $reason")
                    runOnUiThread {
                        isConnected = false
                        toggleButton.setBackgroundColor(Color.RED)
                        toggleButton.text = getString(R.string.mic_off)
                    }
                    stopAudioRecording()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("MicApp", "Message received: $text")
                    if (text == "rejected") {
                        Log.w("MicApp", "Rejected by server")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Mic is already in use", Toast.LENGTH_SHORT).show()
                            isConnected = false
                            toggleButton.setBackgroundColor(Color.RED)
                            toggleButton.text = getString(R.string.mic_off)
                        }
                        stopAudioRecording()
                        webSocket.close(1000, "Rejected by server")
                    }
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
                val bytesPerSecond = sampleRate * 2
                val targetDelayNs = 1_000_000_000L * bufferSize / bytesPerSecond
                var nextTime = System.nanoTime()

                while (isRecording && audioRecord != null) {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        socket?.send(buffer.toByteString(0, read))
                    }

                    nextTime += targetDelayNs
                    val sleepNs = nextTime - System.nanoTime()
                    if (sleepNs > 0) {
                        Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                    } else {
                        nextTime = System.nanoTime() // fell behind; reset
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