package com.wordwatcher

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wordwatcher.data.AppDatabase
import com.wordwatcher.data.WatchItem
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: WatchItemAdapter
    private val items = mutableListOf<WatchItem>()
    private lateinit var tvStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            updateStatusUI()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getInstance(this)
        tvStatus = findViewById(R.id.tvStatus)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = WatchItemAdapter(items) { item -> deleteItem(item) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.btnAdd).setOnClickListener { addEntry() }
        findViewById<Button>(R.id.btnToggleService).setOnClickListener { 
            checkPermissionsAndStartService() 
            requestIgnoreBatteryOptimizations()
        }
        findViewById<Button>(R.id.btnTestAlert).setOnClickListener {
            val intent = Intent(this, WatcherService::class.java).apply {
                action = WatcherService.ACTION_TEST_ALERT
            }
            startService(intent)
        }

        loadItems()
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusUpdater)
    }

    private fun updateStatusUI() {
        if (isServiceRunning(WatcherService::class.java)) {
            tvStatus.text = "● SERVICE ACTIVE"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            tvStatus.text = "○ SERVICE STOPPED"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun addEntry() {
        val url = findViewById<EditText>(R.id.etUrl).text.toString().trim()
        val keyword = findViewById<EditText>(R.id.etKeyword).text.toString().trim()
        if (url.isEmpty() || keyword.isEmpty()) return

        val formattedUrl = if (!url.startsWith("http")) "https://$url" else url
        CoroutineScope(Dispatchers.IO).launch {
            db.watchItemDao().insert(WatchItem(url = formattedUrl, keyword = keyword))
            loadItems()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                return
            }
        }
        startService()
    }

    private fun startService() {
        val intent = Intent(this, WatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun loadItems() {
        CoroutineScope(Dispatchers.IO).launch {
            val all = db.watchItemDao().getAll()
            withContext(Dispatchers.Main) {
                items.clear()
                items.addAll(all)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun deleteItem(item: WatchItem) {
        CoroutineScope(Dispatchers.IO).launch {
            db.watchItemDao().delete(item)
            loadItems()
        }
    }
}
