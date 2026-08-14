package com.refayatul.fityah.ui.fragments.main.reducers.vpn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.refayatul.fityah.R
import com.refayatul.fityah.data.db.AppDatabase
import com.refayatul.fityah.data.db.BlocklistSourceEntity
import com.refayatul.fityah.databinding.FragmentDnsListBinding
import kotlinx.coroutines.launch

class BlocklistSourcesFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "vpn_blocklist_sources"
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
        binding.textTitle.text = getString(R.string.vpn_add_source)
        
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
            val list = db.dnsDao().getAllSources()
            binding.recyclerView.adapter = SourcesAdapter(list) { url ->
                viewLifecycleOwner.lifecycleScope.launch {
                    db.dnsDao().deleteSource(url)
                    db.dnsDao().deleteBlockedDomainsBySource(url)
                    refreshList()
                }
            }
        }
    }

    private fun showAddDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val labelInput = EditText(requireContext()).apply { hint = "Label (e.g. Ads)" }
        val urlInput = EditText(requireContext()).apply { hint = "URL" }
        layout.addView(labelInput)
        layout.addView(urlInput)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Blocklist Source")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val label = labelInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (label.isNotEmpty() && url.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        db.dnsDao().insertSource(BlocklistSourceEntity(url, label))
                        refreshList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class SourcesAdapter(
        private val list: List<BlocklistSourceEntity>,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<SourcesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(android.R.id.text1)
            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage("Remove ${list[pos].label}?")
                            .setPositiveButton("Remove") { _, _ -> onDelete(list[pos].url) }
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
            val item = list[position]
            holder.text.text = String.format("%s\n%s", item.label, item.url)
        }

        override fun getItemCount() = list.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
