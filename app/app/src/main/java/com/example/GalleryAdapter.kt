package com.example.remote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

// 갤러리 화면에서 쓸 RecyclerView 어댑터
// 서버/로컬에서 가져온 사진 목록(PhotoItem)을 표시
// 썸네일 + 촬영 시간 + 분석 여부 뱃지 출력


// 개별 사진 데이터 모델
data class PhotoItem(
    val id: String,
    val datetime: String,
    val thumbnailUrl: String,
    var analysis: String? = null
)


// 리스트 갱신 시 변경된 아이템만 효율적으로 업데이트
object PhotoDiffCallback : DiffUtil.ItemCallback<PhotoItem>() {
    override fun areItemsTheSame(oldItem: PhotoItem, newItem: PhotoItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: PhotoItem, newItem: PhotoItem): Boolean {
        return oldItem == newItem
    }
}

class GalleryAdapter(
    private val onItemClick: (PhotoItem) -> Unit
) : ListAdapter<PhotoItem, GalleryAdapter.PhotoViewHolder>(PhotoDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.gallery_item, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photoItem = getItem(position)
        holder.bind(photoItem, onItemClick)
    }

    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.photoImageView)
        private val dateView: TextView = itemView.findViewById(R.id.dateTextView)
        private val badgeView: TextView = itemView.findViewById(R.id.analysisBadge)

        // // 아이템 데이터 → UI에 바인딩

        fun bind(item: PhotoItem, onItemClick: (PhotoItem) -> Unit) {
            dateView.text = if (!item.analysis.isNullOrBlank()) {
                "${item.datetime} · 분석 완료"
            } else {
                item.datetime
            }

            Glide.with(itemView.context)
                .load(item.thumbnailUrl)
                .centerCrop()
                .placeholder(R.drawable.ic_launcher_background)
                .into(imageView)

            // 분석 결과 있음/없음에 따라 뱃지 표시
            if (!item.analysis.isNullOrBlank()) {
                badgeView.visibility = View.VISIBLE
            } else {
                badgeView.visibility = View.GONE
            }

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
