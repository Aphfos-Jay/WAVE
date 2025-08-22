package com.example.remote

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "app_prefs"
        const val KEY_MODE = "app_mode" // "rc" | "controller"
    }

    private lateinit var bottomNavigationView: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNavigationView = findViewById(R.id.bottom_navigation)

        // 1) 모드 먼저 결정 (client_id 세팅 포함) → 그 다음 단계로 진행
        ensureAppModeChosen {
            // 2) 권한 확인 → 허용 즉시 connect + 서비스 시작 (권한 이미 허용된 경우에도 onGranted가 즉시 호출됨)
            PermissionUtils.checkAndRequestPermissions(
                activity = this,
                onGranted = {
                    WebSocketManager.getInstance().connect()  // ✅ 항상 여기서 연결 보장
                    startPorcupineServiceOnce()               // STT/TTS 서비스
                },
                onDenied = { /* 필요시 안내 */ }
            )

            // 초기 화면
            if (savedInstanceState == null) {
                replaceFragment(HomeFragment())
            }

            bottomNavigationView.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> { replaceFragment(HomeFragment()); true }
                    R.id.nav_remote -> {
                        val mode = getAppMode()
                        val fragment: Fragment = if (mode == "rc") RcFragment() else ControllerFragment()
                        replaceFragment(fragment); true
                    }
                    else -> false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketManager.getInstance().disconnect() // 앱 종료 시 연결 해제
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

    // 권한 요청 API 콜백도 안전망으로 유지(최초 설치 직후 등)
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

    // 모드 선택 → client_id 세팅 → 완료 후 next() 실행
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
                    next() // ✅ 선택 직후 다음 단계 진행
                }
                .setCancelable(false)
                .show()
        } else {
            WebSocketManager.setClientId(if (saved == "rc") "android_rc" else "android_ctrl")
            next()
        }
    }

    private fun getAppMode(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return prefs.getString(KEY_MODE, "controller") ?: "controller"
    }
}
