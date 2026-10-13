package com.refayatul.fityah.ui.fragments.main.reducers.vpn

import android.os.Bundle
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
import com.refayatul.fityah.data.db.VpnAppRuleEntity
import com.refayatul.fityah.databinding.FragmentDnsListBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class VpnAppDetailFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "vpn_app_detail"
    }

    private var _binding: FragmentDnsListBinding? = null
    private val binding get() = _binding!!
    private var packageName: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDnsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        packageName = arguments?.getString("PACKAGE_NAME")
        val appName = arguments?.getString("APP_NAME") ?: "App Details"
        
        binding.textTitle.text = appName
        binding.btnAdd.visibility = View.GONE
        
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        refreshData()

        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun refreshData() {
        val pkg = packageName ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val logs = db.dnsDao().getLogsForApp(pkg)
            val rules = db.dnsDao().getRulesForApp(pkg)
            
            // Group by domain to show a unique list with toggles
            val domainMap = logs.groupBy { it.domain }.map { (domain, domainLogs) ->
                val trackerName = domainLogs.firstNotNullOfOrNull { it.trackerName }
                val currentRule = rules.find { it.domain == domain }?.isAllowed
                // Default to Allowed if no rule exists, unless it was blocked in logs? 
                // TC usually shows "Allow" by default for essential apps and "Block" for trackers.
                val isAllowed = currentRule ?: !domainLogs.any { it.isBlocked }
                
                AppDomainItem(domain, trackerName, isAllowed)
            }.sortedByDescending { it.trackerName != null }

            binding.recyclerView.adapter = RulesAdapter(domainMap)
        }
    }

    data class AppDomainItem(
        val domain: String,
        val trackerName: String?,
        val isAllowed: Boolean
    )

    private inner class RulesAdapter(private val list: List<AppDomainItem>) :
        RecyclerView.Adapter<RulesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textDomain: TextView = view.findViewById(R.id.text_domain)
            val textTracker: TextView = view.findViewById(R.id.text_tracker)
            val switchRule: com.google.android.material.materialswitch.MaterialSwitch = view.findViewById(R.id.switch_rule)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vpn_app_rule, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.textDomain.text = item.domain
            holder.textTracker.text = item.trackerName ?: "Direct Connection"
            
            if (item.trackerName != null) {
                holder.textDomain.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
            } else {
                holder.textDomain.setTextColor(requireContext().getColor(android.R.color.holo_blue_dark))
            }

            holder.switchRule.setOnCheckedChangeListener(null)
            holder.switchRule.isChecked = item.isAllowed
            
            holder.switchRule.setOnCheckedChangeListener { _, isChecked ->
                val pkg = packageName ?: return@setOnCheckedChangeListener
                viewLifecycleOwner.lifecycleScope.launch {
                    val db = AppDatabase.getInstance(requireContext())
                    db.dnsDao().insertAppRule(VpnAppRuleEntity(pkg, item.domain, isChecked))
                }
            }
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
