package com.example.remote

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

// 대화/시스템 로그를 RecyclerView에 뿌려주는 어댑터
// LogItem(timestamp + message) 리스트 관리
// DiffUtil로 변경된 부분만 갱신

class LogAdapter : ListAdapter<LogItem, LogAdapter.LogViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<LogItem>() {
        override fun areItemsTheSame(oldItem: LogItem, newItem: LogItem) = oldItem.timestamp == newItem.timestamp
        override fun areContentsTheSame(oldItem: LogItem, newItem: LogItem) = oldItem == newItem
    }

    // 실제 로그 한 줄을 화면에 표시하는 ViewHolder
    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logText: TextView = itemView.findViewById(R.id.logText)
        fun bind(item: LogItem) {
            logText.text = "[${item.timestamp}] ${item.message}"
        }
    }


    // ViewHolder 생성 (item_log.xml inflate)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    // ViewHolder에 실제 데이터 바인딩
    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
