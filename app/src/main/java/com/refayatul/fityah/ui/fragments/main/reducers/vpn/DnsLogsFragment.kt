package com.refayatul.fityah.ui.fragments.main.reducers.vpn

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.refayatul.fityah.R
import com.refayatul.fityah.data.db.AppDatabase
import com.refayatul.fityah.data.db.DnsRequestLogEntity
import com.refayatul.fityah.databinding.FragmentDnsListBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DnsLogsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "vpn_dns_logs"
    }

    private var _binding: FragmentDnsListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDnsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.textTitle.text = "Recent DNS Activity"
        binding.btnAdd.visibility = View.GONE
        
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        refreshLogs()

        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun refreshLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val logs = db.dnsDao().getRecentLogs()
            Log.d("DnsLogsFragment", "Fetched ${logs.size} logs from database")
            binding.recyclerView.adapter = LogsAdapter(logs)
        }
    }

    private inner class LogsAdapter(private val logs: List<DnsRequestLogEntity>) :
        RecyclerView.Adapter<LogsAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textDomain: TextView = view.findViewById(android.R.id.text1)
            val textApp: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val log = logs[position]
            val time = dateFormat.format(Date(log.timestamp))
            
            if (log.trackerName != null) {
                holder.textDomain.text = "⚠️ ${log.trackerName}"
                holder.textApp.text = "${log.domain} • ${log.appName} • $time"
            } else {
                holder.textDomain.text = log.domain
                holder.textApp.text = "${log.appName} • $time"
            }
            
            if (log.isBlocked) {
                holder.textDomain.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
            } else {
                holder.textDomain.setTextColor(requireContext().getColor(android.R.color.holo_blue_dark))
            }
        }

        override fun getItemCount() = logs.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
