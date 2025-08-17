package com.example.remote

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNavigationView: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNavigationView = findViewById(R.id.bottom_navigation)

        // ⭐️ 앱 시작 시 한 번만 PorcupineService 실행 (중복 실행해도 시스템이 하나만 유지)
        startPorcupineServiceOnce()

        // 초기 화면: nav_graph의 startDestination에 맞춤
        if (savedInstanceState == null) {
            replaceFragment(HomeFragment()) // 기본적으로 HomeFragment로 설정
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    replaceFragment(HomeFragment())
                    true
                }
                R.id.nav_remote -> {
                    replaceFragment(RemoteFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()
    }

    // ⭐️ 서비스 중복 실행 방지 목적 (앱 최초 1회만 실행)
    private fun startPorcupineServiceOnce() {
        val intent = Intent(this, PorcupineService::class.java).apply {
            action = PorcupineService.ACTION_START_FOREGROUND_SERVICE
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selected_nav_item", bottomNavigationView.selectedItemId)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val navId = savedInstanceState.getInt("selected_nav_item")
        bottomNavigationView.selectedItemId = navId
    }
}