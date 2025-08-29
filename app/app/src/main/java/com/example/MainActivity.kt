package com.example.remote

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "app_prefs"
        const val KEY_MODE = "app_mode" // "rc" | "controller"
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)  // DrawerLayout 기반 XML

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.navigation_view)

        ensureAppModeChosen {
            PermissionUtils.checkAndRequestPermissions(
                activity = this,
                onGranted = {
                    WebSocketManager.getInstance().connect()
                    startPorcupineServiceOnce()
                },
                onDenied = { /* 필요시 안내 */ }
            )

            setupNavigationDrawer()

            if (savedInstanceState == null) {
                replaceFragment(HomeFragment())
            }
        }
    }

    private fun setupNavigationDrawer() {
        val isControllerMode = (getAppMode() == "controller")

        // 갤러리 메뉴 모드에 따라 표시/숨김
        navigationView.menu.findItem(R.id.nav_gallery).isVisible = isControllerMode

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> replaceFragment(HomeFragment())
                R.id.nav_remote -> {
                    val fragment: Fragment = if (isControllerMode) ControllerFragment() else RcFragment()
                    replaceFragment(fragment)
                }
                R.id.nav_gallery -> replaceFragment(GalleryFragment())
            }
            drawerLayout.closeDrawers()
            true
        }

        // 툴바 연결
        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // 햄버거 아이콘 토글
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.open_drawer, R.string.close_drawer
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        toggle.drawerArrowDrawable.color = ContextCompat.getColor(this, android.R.color.white)
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketManager.getInstance().disconnect()
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
    }

    private fun startPorcupineServiceOnce() {
        val intent = Intent(this, PorcupineService::class.java).apply {
            action = PorcupineService.ACTION_START_FOREGROUND_SERVICE
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionUtils.handlePermissionsResult(
            requestCode, permissions, grantResults,
            onGranted = {
                WebSocketManager.getInstance().connect()
                startPorcupineServiceOnce()
            },
            onDenied  = { /* 필요시 안내 */ }
        )
    }

    private fun ensureAppModeChosen(next: () -> Unit) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getString(KEY_MODE, null)
        if (saved == null) {
            AlertDialog.Builder(this)
                .setTitle("앱 모드 선택")
                .setItems(arrayOf("RC 모드", "조종기 모드")) { _, which ->
                    val mode = if (which == 0) "rc" else "controller"
                    prefs.edit().putString(KEY_MODE, mode).apply()
                    WebSocketManager.setClientId(if (mode == "rc") "android_rc" else "android_ctrl")

                    // ✅ RPi WebSocket 연결
                    val clientId = if (mode == "rc") "android_rc" else "android_ctrl"
                    RpiWebSocketManager.connect(BuildConfig.RPI_IP, BuildConfig.RPI_PORT.toInt(), clientId)

                    next()
                }
                .setCancelable(false)
                .show()
        } else {
            WebSocketManager.setClientId(if (saved == "rc") "android_rc" else "android_ctrl")

            // ✅ RPi WebSocket 연결
            val clientId = if (saved == "rc") "android_rc" else "android_ctrl"
            RpiWebSocketManager.connect("192.168.137.234", 9080, clientId)

            next()
        }
    }

    private fun getAppMode(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return prefs.getString(KEY_MODE, "controller") ?: "controller"
    }
}
