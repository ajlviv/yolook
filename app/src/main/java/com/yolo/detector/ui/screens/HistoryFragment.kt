package com.yolo.detector.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yolo.detector.R
import com.yolo.detector.data.HistoryEntry
import com.yolo.detector.data.HistoryItem
import com.yolo.detector.data.labelFor
import com.yolo.detector.databinding.FragmentHistoryBinding
import com.yolo.detector.ui.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History fragment: displays an in-memory list of the objects seen (grouped by
 * track so each object appears once with its aggregate stats) interleaved with
 * dispatched alert notifications.
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val adapter = HistoryAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnClearHistory.setOnClickListener {
            viewModel.clearHistory()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.historyFlow.collect { list ->
                    adapter.submitList(list)
                    binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        private var items = listOf<HistoryItem>()
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun submitList(newItems: List<HistoryItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is HistoryItem.Detection -> TYPE_DETECTION
            is HistoryItem.Alert -> TYPE_ALERT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layout = if (viewType == TYPE_ALERT) {
                R.layout.item_history_alert
            } else {
                R.layout.item_history_detection
            }
            val view = LayoutInflater.from(parent.context)
                .inflate(layout, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HistoryItem.Detection -> bindDetection(holder, item.entry)
                is HistoryItem.Alert -> bindAlert(holder, item)
            }
        }

        private fun bindDetection(holder: ViewHolder, item: HistoryEntry) {
            val label = labelFor(item.classId)
            val track = if (item.trackId >= 0) "#${item.trackId}" else "?"
            val best = "${(item.bestConfidence * 100).toInt()}%"
            holder.tvTitle.text = "$label $track · seen ${item.count}×"
            holder.tvSubtitle.text = "best $best · ${formatTimeSpan(item)}"
        }

        private fun bindAlert(holder: ViewHolder, item: HistoryItem.Alert) {
            val labels = item.classIds
                .map { labelFor(it) }
                .distinct()
                .joinToString(", ")
            val res = holder.itemView.context.resources
            holder.tvTitle.text = if (item.emailSent) {
                res.getString(R.string.history_alert_sent, labels)
            } else {
                res.getString(R.string.history_alert_failed, labels)
            }
            @Suppress("DEPRECATION")
            holder.tvTitle.setTextColor(
                res.getColor(if (item.emailSent) R.color.alert_sent else R.color.alert_failed),
            )
            holder.tvSubtitle.text = timeFormat.format(Date(item.timestampMs))
        }

        private fun formatTimeSpan(item: HistoryEntry): String {
            val start = timeFormat.format(Date(item.firstSeenMs))
            val end = timeFormat.format(Date(item.lastSeenMs))
            val secs = ((item.lastSeenMs - item.firstSeenMs) / 1000).coerceAtLeast(0)
            val minutes = secs / 60
            val remSeconds = secs % 60
            val duration = if (minutes > 0) "${minutes}m${remSeconds}s" else "${remSeconds}s"
            return "$start - $end ($duration)"
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvSubtitle: TextView = view.findViewById(R.id.tvSubtitle)
        }

        companion object {
            private const val TYPE_DETECTION = 0
            private const val TYPE_ALERT = 1
        }
    }
}
