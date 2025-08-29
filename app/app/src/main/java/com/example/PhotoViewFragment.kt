package com.example.remote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import android.widget.TextView

class PhotoViewFragment : DialogFragment() {

    companion object {
        private const val ARG_URL = "photo_url"
        private const val ARG_ANALYSIS = "analysis"
        fun newInstance(url: String, analysis: String?): PhotoViewFragment {
            val fragment = PhotoViewFragment()
            val args = Bundle()
            args.putString(ARG_URL, url)
            args.putString(ARG_ANALYSIS, analysis)
            fragment.arguments = args
            return fragment

        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_photo_view, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imageView: ImageView = view.findViewById(R.id.fullScreenImageView)
        val progressBar: ProgressBar = view.findViewById(R.id.photoProgressBar)
        val analysisView: TextView = view.findViewById(R.id.analysisTextView) // ✅ 새로 추가된 뷰

        val url = arguments?.getString(ARG_URL)
        val analysis = arguments?.getString(ARG_ANALYSIS)

        if (url != null) {
            progressBar.visibility = View.VISIBLE
            Glide.with(this)
                .load(url)
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        progressBar.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource?,
                        isFirstResource: Boolean
                    ): Boolean {
                        progressBar.visibility = View.GONE
                        return false
                    }
                })
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(imageView)
        }

        // ✅ 분석 결과 표시
        if (!analysis.isNullOrBlank()) {
            analysisView.text = analysis
            analysisView.visibility = View.VISIBLE
        } else {
            analysisView.visibility = View.GONE
        }

        // 이미지를 클릭하면 다이얼로그 닫기
        imageView.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        // 다이얼로그를 전체 화면 크기로 설정
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
}