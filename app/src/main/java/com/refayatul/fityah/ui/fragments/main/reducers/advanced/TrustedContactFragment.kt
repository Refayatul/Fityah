package com.refayatul.fityah.ui.fragments.main.reducers.advanced

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.refayatul.fityah.R
import com.refayatul.fityah.databinding.FragmentTrustedContactBinding
import com.refayatul.fityah.utils.ApprovalUtils
import com.refayatul.fityah.utils.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TrustedContactFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "trusted_contact"
    }

    private var _binding: FragmentTrustedContactBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataStoreManager: DataStoreManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrustedContactBinding.inflate(inflater, container, false)
        dataStoreManager = DataStoreManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            dataStoreManager.settings.collect { settings ->
                binding.editContactName.setText(settings.trustedContactName)
                binding.editContactEmail.setText(settings.trustedContactEmail)
                binding.textPinStatus.text = if (settings.guardianPinHash != null) "PIN Set" else "Not Set"
            }
        }

        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.btnSave.setOnClickListener {
            val name = binding.editContactName.text.toString().trim()
            val email = binding.editContactEmail.text.toString().trim()

            if (name.isEmpty()) {
                binding.editContactName.error = "Name is required"
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                dataStoreManager.updateTrustedContact(name, email)
                Toast.makeText(requireContext(), R.string.added_successfully, Toast.LENGTH_SHORT).show()
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        binding.btnSetPin.setOnClickListener {
            showSetPinDialog()
        }
    }

    private fun showSetPinDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter 4-8 digit PIN"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set Guardian PIN")
            .setMessage("Ask your contact to enter a secret PIN. You will not see this PIN.")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..8) {
                    val hash = ApprovalUtils.hashPin(pin)
                    viewLifecycleOwner.lifecycleScope.launch {
                        dataStoreManager.updateGuardianPinHash(hash)
                        Toast.makeText(requireContext(), "Guardian PIN set successfully", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "PIN must be 4-8 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
