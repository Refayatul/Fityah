package com.refayatul.fityah.ui.fragments.main.reducers.vpn

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.refayatul.fityah.R
import com.refayatul.fityah.data.db.AppDatabase
import com.refayatul.fityah.data.db.VpnAppStats
import com.refayatul.fityah.databinding.FragmentDnsListBinding
import com.refayatul.fityah.ui.activity.FragmentActivity
import kotlinx.coroutines.launch

class VpnAppsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "vpn_apps"
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
        binding.textTitle.text = "Apps Activity"
        binding.btnAdd.visibility = View.GONE
        
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        refreshList()

        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun refreshList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val stats = db.dnsDao().getAppStats()
            binding.recyclerView.adapter = AppsAdapter(stats)
        }
    }

    private inner class AppsAdapter(private val list: List<VpnAppStats>) :
        RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.text_name)
            val stats: TextView = view.findViewById(R.id.text_stats)
            val trackerCount: TextView = view.findViewById(R.id.text_tracker_count)

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                            putExtra("fragment", VpnAppDetailFragment.FRAGMENT_ID)
                            putExtra("PACKAGE_NAME", list[pos].packageName)
                            putExtra("APP_NAME", list[pos].appName)
                        }
                        startActivity(intent)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vpn_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.name.text = item.appName
            holder.stats.text = "${item.totalRequests} requests • ${item.blockedRequests} blocked"
            
            if (item.trackerRequests > 0) {
                holder.trackerCount.visibility = View.VISIBLE
                holder.trackerCount.text = "${item.trackerRequests} trackers"
            } else {
                holder.trackerCount.visibility = View.GONE
            }

            try {
                val icon = requireContext().packageManager.getApplicationIcon(item.packageName)
                holder.icon.setImageDrawable(icon)
            } catch (e: Exception) {
                holder.icon.setImageResource(R.drawable.logo)
            }
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
