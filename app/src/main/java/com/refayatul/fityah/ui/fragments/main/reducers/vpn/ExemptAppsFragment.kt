package com.refayatul.fityah.ui.fragments.main.reducers.vpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.refayatul.fityah.R
import com.refayatul.fityah.databinding.FragmentDnsListBinding
import com.refayatul.fityah.ui.activity.SelectAppsActivity
import com.refayatul.fityah.utils.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ExemptAppsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "vpn_exempt_apps"
    }

    private var _binding: FragmentDnsListBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataStoreManager: DataStoreManager

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                updateExemptApps(apps.toSet())
            }
        }
    }

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
        binding.textTitle.text = "Exempt Apps"
        
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        refreshList()

        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.btnAdd.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val current = dataStoreManager.settings.first().vpnConfig.exemptPackages
                val intent = Intent(requireContext(), SelectAppsActivity::class.java).apply {
                    putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(current))
                }
                selectAppsLauncher.launch(intent)
            }
        }
    }

    private fun refreshList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = dataStoreManager.settings.first().vpnConfig
            binding.recyclerView.adapter = ExemptAdapter(config.exemptPackages.toList()) { updatedSet ->
                updateExemptApps(updatedSet)
            }
        }
    }

    private fun updateExemptApps(newSet: Set<String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = dataStoreManager.settings.first().vpnConfig
            dataStoreManager.updateVpnConfig(config.copy(exemptPackages = newSet))
            refreshList()
        }
    }

    private inner class ExemptAdapter(
        private val list: List<String>,
        private val onUpdate: (Set<String>) -> Unit
    ) : RecyclerView.Adapter<ExemptAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(android.R.id.text1)
            val icon: ImageView = view.findViewById(android.R.id.icon) // simple_list_item_1 doesn't have icon, but some others do
            
            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val pkg = list[pos]
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage("Remove $pkg from exemptions?")
                            .setPositiveButton("Remove") { _, _ ->
                                val newSet = list.toMutableSet().apply { remove(pkg) }
                                onUpdate(newSet)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val pkg = list[position]
            val pm = requireContext().packageManager
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                holder.text.text = pm.getApplicationLabel(info)
            } catch (e: Exception) {
                holder.text.text = pkg
            }
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
