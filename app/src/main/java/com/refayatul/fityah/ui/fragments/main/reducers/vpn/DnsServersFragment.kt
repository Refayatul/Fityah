package com.refayatul.fityah.ui.fragments.main.reducers.vpn

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.refayatul.fityah.R
import com.refayatul.fityah.data.models.DnsServer
import com.refayatul.fityah.data.models.DnsType
import com.refayatul.fityah.databinding.FragmentDnsListBinding
import com.refayatul.fityah.services.vpn.DnsVpnService
import com.refayatul.fityah.utils.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DnsServersFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "vpn_dns_servers"
    }

    private var _binding: FragmentDnsListBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataStoreManager: DataStoreManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDnsListBinding.inflate(inflater, container, false)
        dataStoreManager = DataStoreManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.textTitle.text = getString(R.string.vpn_dns_server)
        
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        refreshList()

        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.btnAdd.setOnClickListener {
            showAddDialog()
        }
    }

    private fun refreshList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = dataStoreManager.settings.first().vpnConfig
            binding.recyclerView.adapter = DnsAdapter(config.dnsServers) { updatedList ->
                viewLifecycleOwner.lifecycleScope.launch {
                    dataStoreManager.updateVpnConfig(config.copy(dnsServers = updatedList))
                    refreshList()
                    // Task: Notify service to reload configuration
                    if (config.isEnabled) {
                        startVpnService()
                    }
                }
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(requireContext(), DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
        }
        requireContext().startService(intent)
    }

    private fun showAddDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val addressInput = EditText(requireContext()).apply { 
            hint = "e.g. 9.9.9.9 or https://dns.nextdns.io/222jhg" 
        }
        layout.addView(addressInput)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Upstream DNS")
            .setView(layout)
            .setNeutralButton("Secure (DoH/DoT)") { _, _ ->
                val addr = addressInput.text.toString().trim()
                if (addr.startsWith("https://")) {
                    addServer(addr, DnsType.DOH)
                } else {
                    addServer(addr, DnsType.DOT)
                }
            }
            .setPositiveButton("Plain (IP)") { _, _ -> 
                addServer(addressInput.text.toString(), DnsType.PLAIN) 
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addServer(address: String, type: DnsType) {
        val addr = address.trim()
        if (addr.isEmpty()) return

        // Task 10: Reject IP-based entries for secure protocols (DoH/DoH3/DoT)
        if ((type == DnsType.DOH || type == DnsType.DOH3 || type == DnsType.DOT) && 
            addr.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$"))) {
            Toast.makeText(requireContext(), "Secure DNS requires a hostname or URL", Toast.LENGTH_LONG).show()
            return
        }

        if ((type == DnsType.DOH || type == DnsType.DOH3) && !addr.startsWith("https://")) {
            Toast.makeText(requireContext(), "DoH URL must start with https://", Toast.LENGTH_LONG).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val config = dataStoreManager.settings.first().vpnConfig
            val newList = config.dnsServers + DnsServer(addr, type)
            dataStoreManager.updateVpnConfig(config.copy(dnsServers = newList))
            refreshList()
            if (config.isEnabled) {
                startVpnService()
            }
        }
    }

    private inner class DnsAdapter(
        private val list: List<DnsServer>,
        private val onUpdate: (List<DnsServer>) -> Unit
    ) : RecyclerView.Adapter<DnsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textAddress: TextView = view.findViewById(R.id.text_dns_address)
            val textType: TextView = view.findViewById(R.id.text_dns_type)
            val btnRemove: View = view.findViewById(R.id.btn_remove)
            val switchEnabled: com.google.android.material.materialswitch.MaterialSwitch = view.findViewById(R.id.switch_enabled)

            init {
                btnRemove.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage("Remove ${list[pos].address}?")
                            .setPositiveButton("Remove") { _, _ ->
                                val newList = list.toMutableList().apply { removeAt(pos) }
                                onUpdate(newList)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        if (list[pos].isEnabled != isChecked) {
                            val newList = list.toMutableList().apply {
                                this[pos] = this[pos].copy(isEnabled = isChecked)
                            }
                            onUpdate(newList)
                        }
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dns_server, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.textAddress.text = item.address
            holder.textType.text = item.type.name
            
            holder.switchEnabled.setOnCheckedChangeListener(null)
            holder.switchEnabled.isChecked = item.isEnabled
            // Re-setup listener after setting initial state
            holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val newList = list.toMutableList().apply {
                        this[pos] = this[pos].copy(isEnabled = isChecked)
                    }
                    onUpdate(newList)
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
