package com.example.remote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// 앱에서 필요한 권한들을 한 곳에서 관리하는 유틸리티
// 마이크 / 카메라 / 위치 / 알림 권한 체크 & 요청

object PermissionUtils {

    // 개별/통합 요청 코드
    const val REQ_AUDIO  = 1001
    const val REQ_CAMERA = 1002
    const val REQ_ALL    = 1100
    const val REQ_POST_NOTI = 1200

    //권한 보유 여부 체크
    // 마이크 & 카메라 권한 보유 여부 확인
    // 안드로이드 13 이상에서만 알림 권한 체크
    fun hasMicPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun hasCameraPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    fun hasPostNotificationPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    //개별 요청
    fun requestMicPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_AUDIO
        )
    }

    fun requestCameraPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            REQ_CAMERA
        )
    }

    fun requestPostNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTI
            )
        }
    }

    // 앱 첫 실행 시 한번에 권한 요청
    // 마이크 / 카메라 / 위치 / 알림
    // 다 허용되면 onGranted() 콜백 실행
    fun checkAndRequestPermissions(
        activity: Activity,
        onGranted: () -> Unit,
        onDenied: () -> Unit = {}
    ) {
        val toRequest = mutableListOf<String>()

        if (!hasMicPermission(activity))    toRequest += Manifest.permission.RECORD_AUDIO
        if (!hasCameraPermission(activity)) toRequest += Manifest.permission.CAMERA

        // ▼▼▼ 이 부분을 추가하거나 확인하세요 ▼▼▼
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toRequest += Manifest.permission.ACCESS_FINE_LOCATION
        }
        // ▲▲▲ 확인 ▲▲▲

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPostNotificationPermission(activity)
        ) {
            toRequest += Manifest.permission.POST_NOTIFICATIONS
        }

        if (toRequest.isEmpty()) {
            onGranted()
        } else {
            ActivityCompat.requestPermissions(
                activity,
                toRequest.toTypedArray(),
                REQ_ALL
            )
        }
    }

    // Activity/Fragment의 onRequestPermissionsResult에서 호출
    // 결과 배열 확인 후 모두 허용되면 onGranted(), 아니면 onDenied()
    fun handlePermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        if (grantResults.isEmpty()) {
            onDenied(); return
        }

        when (requestCode) {
            REQ_ALL, REQ_AUDIO, REQ_CAMERA, REQ_POST_NOTI -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) onGranted() else onDenied()
            }
            else -> { /* no-op */ }
        }
    }

    // STT 시작 직전에 마이크 권한이 없으면 즉시 요청
    fun ensureMicPermissionOrRequest(
        activity: Activity,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        if (hasMicPermission(activity)) {
            onGranted()
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_AUDIO
            )
        }
    }
}
