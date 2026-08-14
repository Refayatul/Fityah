package com.refayatul.fityah.ui.fragments.main.reducers.vpn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.refayatul.fityah.R
import com.refayatul.fityah.data.db.AllowedDomainEntity
import com.refayatul.fityah.data.db.AppDatabase
import com.refayatul.fityah.databinding.FragmentDnsListBinding
import kotlinx.coroutines.launch

class AllowlistFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "vpn_allowlist"
    }

    private var _binding: FragmentDnsListBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: AppDatabase

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDnsListBinding.inflate(inflater, container, false)
        db = AppDatabase.getInstance(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.textTitle.text = getString(R.string.vpn_allowlist)
        
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
            val list = db.dnsDao().getAllAllowedDomains()
            binding.recyclerView.adapter = AllowlistAdapter(list) { domain ->
                viewLifecycleOwner.lifecycleScope.launch {
                    db.dnsDao().deleteAllowedDomain(domain)
                    refreshList()
                }
            }
        }
    }

    private fun showAddDialog() {
        val input = EditText(requireContext())
        input.hint = "example.com"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Allow Domain")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val domain = input.text.toString().trim().lowercase()
                if (domain.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        db.dnsDao().insertAllowedDomain(AllowedDomainEntity(domain))
                        refreshList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class AllowlistAdapter(
        private val list: List<AllowedDomainEntity>,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<AllowlistAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(android.R.id.text1)
            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage("Remove ${list[pos].domain} from allowlist?")
                            .setPositiveButton("Remove") { _, _ -> onDelete(list[pos].domain) }
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
            holder.text.text = list[position].domain
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
