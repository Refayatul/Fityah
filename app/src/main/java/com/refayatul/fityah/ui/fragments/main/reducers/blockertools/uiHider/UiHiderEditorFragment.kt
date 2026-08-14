package com.refayatul.fityah.ui.fragments.main.reducers.blockertools.uiHider

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.refayatul.fityah.R
import com.refayatul.fityah.data.models.UiHiderScript
import com.refayatul.fityah.databinding.FragmentUiHiderEditorBinding
import com.refayatul.fityah.hardcoded.isPresetUiHiderScript

class UiHiderEditorFragment : Fragment() {

    private var _binding: FragmentUiHiderEditorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UiHiderViewModel by activityViewModels()

    private var scriptId: String? = null
    private var existingIsEnabled: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUiHiderEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scriptId = arguments?.getString(EXTRA_SCRIPT_ID)
        val isEditing = scriptId != null
        val isPreset = scriptId?.let { isPresetUiHiderScript(it) } == true

        if (isEditing) {
            existingIsEnabled = arguments?.getBoolean(EXTRA_IS_ENABLED, true) ?: true
            binding.editPackage.setText(arguments?.getString(EXTRA_PACKAGE_NAME).orEmpty())
            binding.editLabel.setText(arguments?.getString(EXTRA_LABEL).orEmpty())
            binding.editSource.setText(arguments?.getString(EXTRA_SOURCE).orEmpty())
        }

        if (isPreset) {
            binding.editPackage.isEnabled = false
            binding.editLabel.isEnabled = false
            binding.editSource.isEnabled = false
            binding.textPresetNote.visibility = View.VISIBLE
            binding.btnSave.visibility = View.GONE
            binding.btnDelete.visibility = View.GONE
            return
        }

        binding.btnDelete.visibility = if (isEditing) View.VISIBLE else View.GONE

        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener {
            val id = scriptId ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.deleteScript(id)
                requireActivity().finish()
            }
        }
    }

    private fun save() {
        val packageName = binding.editPackage.text?.toString()?.trim().orEmpty()
        val source = binding.editSource.text?.toString().orEmpty()
        val label = binding.editLabel.text?.toString()?.trim().orEmpty()

        if (packageName.isEmpty()) {
            Toast.makeText(requireContext(), R.string.uihider_enter_package, Toast.LENGTH_SHORT).show()
            return
        }

        val error = viewModel.validate(source)
        if (error != null) {
            binding.textOutput.text = error
            binding.textOutput.visibility = View.VISIBLE
            return
        }

        val script = UiHiderScript(
            id = scriptId ?: viewModel.newScriptId(),
            packageName = packageName,
            label = label.ifEmpty { packageName.substringAfterLast('.') },
            source = source,
            isEnabled = existingIsEnabled
        )
        // Finish only after the write lands; finishing earlier cancels the ViewModel scope
        // and with it the save.
        binding.btnSave.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.upsertScript(script)
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "ui_hider_editor"
        const val EXTRA_SCRIPT_ID = "scriptId"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_IS_ENABLED = "isEnabled"
    }
}
