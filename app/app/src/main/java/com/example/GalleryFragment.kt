package com.example.remote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.content.Context

class GalleryFragment : Fragment() {

    private lateinit var galleryRecyclerView: RecyclerView
    private lateinit var refreshButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var galleryAdapter: GalleryAdapter

    private val ws = WebSocketManager.getInstance()
    private val uiHandler = Handler(Looper.getMainLooper())

    private val photoList = mutableListOf<PhotoItem>()

    private val fragmentWsListener: (type: String, content: String) -> Unit = { type, content ->
        uiHandler.post {
            when (type) {
                // ✅ 사진 목록 요청 응답
                "FindCapResult" -> {
                    progressBar.visibility = View.GONE
                    try {
                        val jsonObject = JSONObject(content)
                        val jsonArray = jsonObject.getJSONArray("Items")

                        photoList.clear()
                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.getJSONObject(i)
                            val id = item.getString("id")

                            // 🔹 로컬 저장된 분석 결과 불러오기
                            val prefs = requireContext().getSharedPreferences("analysis_store", Context.MODE_PRIVATE)
                            val analysisResult = prefs.getString(id, null)

                            photoList.add(
                                PhotoItem(
                                    id = id,
                                    datetime = item.getString("datetime"),
                                    thumbnailUrl = item.getString("url"),
                                    analysis = analysisResult
                                )
                            )
                        }
                        photoList.sortByDescending { it.datetime }
                        galleryAdapter.submitList(photoList.toList())
                        if (isAdded) {
                            Toast.makeText(context, "${photoList.size}개의 사진을 찾았습니다.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("GalleryFragment", "FindCapResult 파싱 오류", e)
                        if (isAdded) {
                            Toast.makeText(context, "사진 목록을 파싱하는데 실패했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // ✅ 단일 사진 요청
                "CapGetResult" -> {
                    try {
                        val jsonObject = JSONObject(content)
                        val photoUrl = jsonObject.getString("Url")

                        // 분석 결과는 아직 없으므로 null
                        PhotoViewFragment.newInstance(photoUrl, null)
                            .show(parentFragmentManager, "photo_view")
                    } catch (e: Exception) {
                        Log.e("GalleryFragment", "CapGetResult 파싱 오류", e)
                    }
                }

                // ✅ AI 분석 결과 수신
                "CapAnalysis" -> {
                    progressBar.visibility = View.GONE
                    try {
                        val json = JSONObject(content)
                        val id = json.getString("ID")
                        val analysis = json.getString("result")

                        // 🔹 로컬 저장 (SharedPreferences)
                        val prefs = requireContext().getSharedPreferences("analysis_store", Context.MODE_PRIVATE)
                        prefs.edit().putString(id, analysis).apply()

                        // 🔹 현재 리스트에도 반영
                        val index = photoList.indexOfFirst { it.id == id }
                        if (index != -1) {
                            val updatedItem = photoList[index].copy(analysis = analysis)
                            photoList[index] = updatedItem
                            galleryAdapter.submitList(photoList.toList())
                        }

                        if (isAdded) {
                            Toast.makeText(context, "사진 분석 결과가 반영되었습니다.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("GalleryFragment", "CapAnalysis 파싱 오류", e)
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_gallery, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        galleryRecyclerView = view.findViewById(R.id.galleryRecyclerView)
        refreshButton = view.findViewById(R.id.refreshButton)
        progressBar = view.findViewById(R.id.progressBar)

        setupRecyclerView()
        ws.addEventListener(fragmentWsListener)

        refreshButton.setOnClickListener {
            loadPhotoList()
        }

        // 진입 시 자동으로 목록 로드
        loadPhotoList()
    }

    private fun setupRecyclerView() {
        galleryAdapter = GalleryAdapter { photoItem ->
            PhotoViewFragment.newInstance(photoItem.thumbnailUrl, photoItem.analysis)
                .show(parentFragmentManager, "photo_view")
        }
        galleryRecyclerView.adapter = galleryAdapter
        galleryRecyclerView.layoutManager = LinearLayoutManager(context)
    }

    private fun loadPhotoList() {
        progressBar.visibility = View.VISIBLE
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val to = sdf.format(Date())
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        val from = sdf.format(cal.time)

        val findCapMessage = JsonFactory.createFindCaps(from, to, limit = 100)
        ws.sendText(findCapMessage)
        Log.d("GalleryFragment", "사진 목록 요청: $findCapMessage")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ws.removeEventListener(fragmentWsListener)
    }
}
