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
import com.yolo.detector.data.Detection
import com.yolo.detector.data.labelFor
import com.yolo.detector.databinding.FragmentHistoryBinding
import com.yolo.detector.ui.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History fragment: displays an in-memory list of recent detections.
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
                    adapter.submitList(list.reversed()) // show newest first
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
        private var items = listOf<Detection>()
        private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        fun submitList(newItems: List<Detection>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_detection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val label = labelFor(item.classId)
            val track = if (item.trackId >= 0) "#${item.trackId}" else "?"
            val conf = "${(item.confidence * 100).toInt()}%"
            val time = dateFormat.format(Date(item.timestampMs))

            holder.tvTitle.text = "$label ($track) - $conf"
            holder.tvSubtitle.text = "Time: $time | Box: [${"%.2f".format(item.bbox.left)}, ${"%.2f".format(item.bbox.top)}, ${"%.2f".format(item.bbox.right)}, ${"%.2f".format(item.bbox.bottom)}]"
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvSubtitle: TextView = view.findViewById(R.id.tvSubtitle)
        }
    }
}
