package com.refayatul.fityah.ui.fragments.main.reducers.anti_stimulants.mindful_messages

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.refayatul.fityah.R
import com.refayatul.fityah.databinding.FragmentMindfulMessagesBinding
import com.refayatul.fityah.ui.activity.SelectAppsActivity
import com.refayatul.fityah.ui.overlay.OverlayDragHelper

class MindfulMessagesFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "MINDFUL_MESSAGES"
    }

    private var _binding: FragmentMindfulMessagesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MindfulMessagesViewModel by viewModels()
    private var selectedApps = arrayListOf<String>()
    private var isUpdatingFromViewModel = false
    private var selectedColorIndex = 0
    private var colorChipViews = emptyList<View>()
    private var positionScrim: View? = null

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                selectedApps = apps
                updateAppsButtonText()
                viewModel.updateSelectedApps(apps.toList())
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMindfulMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_help) {
                val url = "https://fityah.app/docs/reducers/mindful-messages/"
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                true
            } else {
                false
            }
        }

        colorChipViews = OverlayDragHelper.buildColorChips(
            container = binding.colorChipsContainer,
            fragment = this,
            onColorSelected = { index ->
                if (!isUpdatingFromViewModel) {
                    selectedColorIndex = index
                    OverlayDragHelper.refreshChipSelection(colorChipViews, selectedColorIndex, resources.displayMetrics.density)
                    viewModel.updateBgColor(OverlayDragHelper.PRESET_COLORS[index])
                }
            }
        )
        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.switchIsActive.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingFromViewModel) return@setOnCheckedChangeListener
            viewModel.updateIsActive(isChecked)
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", selectedApps)
            selectAppsLauncher.launch(intent)
        }

        binding.etMessages.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFromViewModel) return
                viewModel.updateMessages(s?.toString() ?: "")
            }
        })

        binding.sliderTextSize.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.tvTextSizeLabel.text = getString(R.string.text_size_value, value.toInt())
            viewModel.updateTextSize(value)
        }

        binding.sliderTextOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.tvTextOpacityLabel.text = getString(R.string.text_opacity_value, value.toInt())
            viewModel.updateTextOpacity(value.toInt())
        }

        binding.sliderOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.tvOpacityLabel.text = getString(R.string.opacity_value, value.toInt())
            viewModel.updateBgOpacity(value.toInt())
        }

        binding.btnSetPosition.setOnClickListener { showPositionDragOverlay() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.configState.collect { config ->
                    isUpdatingFromViewModel = true

                    if (binding.switchIsActive.isChecked != config.isActive) {
                        binding.switchIsActive.isChecked = config.isActive
                    }

                    if (selectedApps.toList() != config.selectedApps) {
                        selectedApps = ArrayList(config.selectedApps)
                        updateAppsButtonText()
                    }

                    if (binding.etMessages.text.toString() != config.messages) {
                        val cursor = binding.etMessages.selectionStart
                        binding.etMessages.setText(config.messages)
                        if (cursor >= 0 && cursor <= (binding.etMessages.text?.length ?: 0)) {
                            binding.etMessages.setSelection(cursor)
                        }
                    }

                    if (binding.sliderTextSize.value != config.textSize) {
                        binding.sliderTextSize.value = config.textSize.coerceIn(8f, 28f)
                    }
                    binding.tvTextSizeLabel.text = getString(R.string.text_size_value, config.textSize.toInt())

                    if (binding.sliderTextOpacity.value != config.textOpacity.toFloat()) {
                        binding.sliderTextOpacity.value = config.textOpacity.toFloat().coerceIn(0f, 100f)
                    }
                    binding.tvTextOpacityLabel.text = getString(R.string.text_opacity_value, config.textOpacity)

                    if (binding.sliderOpacity.value != config.bgOpacity.toFloat()) {
                        binding.sliderOpacity.value = config.bgOpacity.toFloat().coerceIn(0f, 100f)
                    }
                    binding.tvOpacityLabel.text = getString(R.string.opacity_value, config.bgOpacity)

                    val colorIdx = OverlayDragHelper.PRESET_COLORS.indexOfFirst { it == config.bgColor }.takeIf { it >= 0 } ?: 0
                    if (selectedColorIndex != colorIdx) {
                        selectedColorIndex = colorIdx
                        OverlayDragHelper.refreshChipSelection(colorChipViews, selectedColorIndex, resources.displayMetrics.density)
                    }

                    isUpdatingFromViewModel = false
                }
            }
        }
    }

    private fun showPositionDragOverlay() {
        if (positionScrim != null) return
        val config = viewModel.configState.value
        positionScrim = OverlayDragHelper.showDragOverlay(
            fragment = this,
            layoutResId = R.layout.mindfulmsg_overlay,
            positionX = config.positionX,
            positionY = config.positionY,
            setupWidget = { widget ->
                val r = (config.bgColor shr 16) and 0xFF
                val g = (config.bgColor shr 8) and 0xFF
                val b = config.bgColor and 0xFF
                widget.findViewById<TextView>(R.id.mindful_txt).apply {
                    text = config.messages.lines().firstOrNull()?.ifBlank { "Mindful message" } ?: "Mindful message"
                    textSize = config.textSize
                    setTextColor(Color.argb(config.textOpacity * 255 / 100, 255, 255, 255))
                    setBackgroundColor(Color.argb(config.bgOpacity * 255 / 100, r, g, b))
                    setPadding(32, 32, 32, 32)
                }
            },
            onPositionSaved = { x, y -> viewModel.updatePosition(x, y) },
            onDismiss = { positionScrim = null }
        )
    }

    private fun updateAppsButtonText() {
        binding.btnSelectApps.text = getString(R.string.select_apps_count, selectedApps.size)
    }

    override fun onDestroyView() {
        positionScrim?.let {
            (activity?.window?.decorView as? FrameLayout)?.removeView(it)
            positionScrim = null
        }
        super.onDestroyView()
        _binding = null
    }
}
