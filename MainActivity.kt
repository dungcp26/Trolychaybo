package com.quangdung.trolychaybo

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra("isRunning", false) ?: false
            val currentStep = intent?.getIntExtra("currentStep", 0) ?: 0
            val timeLeft = intent?.getIntExtra("timeLeft", 0) ?: 0
            webView.post {
                webView.evaluateJavascript("onNativeStateUpdate($isRunning, $currentStep, $timeLeft)", null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Xin quyền thông báo trên Android 13+ (Galaxy A17 chạy Android 14/15/16)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Xin tắt tối ưu hóa pin để Samsung OneUI không giết tiến trình chạy ngầm
        requestIgnoreBatteryOptimizations()

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(AndroidBridge(), "AndroidNative")
        webView.webViewClient = WebViewClient()
        setContentView(webView)

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(RunningService.BROADCAST_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateReceiver)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun testVoice() {
            startService(Intent(this@MainActivity, RunningService::class.java).apply {
                action = RunningService.ACTION_TEST_VOICE
            })
        }

        @JavascriptInterface
        fun loadPlan(jsonStr: String) {
            val arr = JSONArray(jsonStr)
            val names = ArrayList<String>()
            val voices = ArrayList<String>()
            val durations = ArrayList<Int>()
            val types = ArrayList<String>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                names.add(obj.getString("name"))
                voices.add(obj.getString("voice"))
                durations.add(obj.getInt("duration"))
                types.add(obj.getString("type"))
            }

            startService(Intent(this@MainActivity, RunningService::class.java).apply {
                action = RunningService.ACTION_LOAD_PLAN
                putStringArrayListExtra("names", names)
                putStringArrayListExtra("voices", voices)
                putIntegerArrayListExtra("durations", durations)
                putStringArrayListExtra("types", types)
            })
        }

        @JavascriptInterface
        fun startWorkout() {
            val intent = Intent(this@MainActivity, RunningService::class.java).apply {
                action = RunningService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        @JavascriptInterface
        fun pauseWorkout() {
            startService(Intent(this@MainActivity, RunningService::class.java).apply {
                action = RunningService.ACTION_PAUSE
            })
        }

        @JavascriptInterface
        fun resetWorkout() {
            startService(Intent(this@MainActivity, RunningService::class.java).apply {
                action = RunningService.ACTION_RESET
            })
        }

        @JavascriptInterface
        fun jumpToStep(index: Int) {
            startService(Intent(this@MainActivity, RunningService::class.java).apply {
                action = RunningService.ACTION_JUMP_STEP
                putExtra("stepIndex", index)
            })
        }
    }
}
