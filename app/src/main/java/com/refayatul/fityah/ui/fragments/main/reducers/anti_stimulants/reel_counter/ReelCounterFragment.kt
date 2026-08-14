package com.refayatul.fityah.ui.fragments.main.reducers.anti_stimulants.reel_counter

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.refayatul.fityah.utils.ViewUtils
import com.refayatul.fityah.R
import com.refayatul.fityah.databinding.FragmentReelCounterBinding
import com.refayatul.fityah.ui.overlay.OverlayDragHelper

class ReelCounterFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "reel_counter"
    }

    private var _binding: FragmentReelCounterBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ReelCounterViewModel
    private var isUpdatingUi = false
    private var selectedColorIndex = 0
    private var colorChipViews = emptyList<View>()
    private var positionScrim: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReelCounterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[ReelCounterViewModel::class.java]
        colorChipViews = OverlayDragHelper.buildColorChips(
            container = binding.reelColorChipsContainer,
            fragment = this,
            onColorSelected = { index ->
                if (!isUpdatingUi) {
                    selectedColorIndex = index
                    OverlayDragHelper.refreshChipSelection(colorChipViews, selectedColorIndex, resources.displayMetrics.density)
                    viewModel.updateOverlayConfig(viewModel.overlayConfig.value.copy(bgColor = OverlayDragHelper.PRESET_COLORS[index]))
                }
            }
        )
        setupListeners()
        observeViewModel()
        viewModel.initialize()
    }

    private fun setupListeners() {
        binding.switchEnableCounter.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setIsActive(isChecked)
        }

        binding.btnHelp.setOnClickListener {
            ViewUtils.showHelpPopup(it, "Track how many short videos you watch to build better digital habits.", "https://fityah.app/docs/reducers/video-counter/")
        }

        binding.btnPrevWeek.setOnClickListener { viewModel.goToPreviousWeek() }
        binding.btnNextWeek.setOnClickListener { viewModel.goToNextWeek() }

        binding.weeklyBarGraph.setOnDaySelectedListener { dayData ->
            val index = viewModel.weeklyData.value?.indexOf(dayData) ?: return@setOnDaySelectedListener
            if (index != -1) viewModel.selectDay(index)
        }

        binding.sliderReelTextSize.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.tvReelTextSizeLabel.text = getString(R.string.text_size_value, value.toInt())
            viewModel.updateOverlayConfig(viewModel.overlayConfig.value.copy(textSize = value))
        }

        binding.sliderReelTextOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.tvReelTextOpacityLabel.text = getString(R.string.text_opacity_value, value.toInt())
            viewModel.updateOverlayConfig(viewModel.overlayConfig.value.copy(textOpacity = value.toInt()))
        }

        binding.sliderReelOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            binding.tvReelOpacityLabel.text = getString(R.string.opacity_value, value.toInt())
            viewModel.updateOverlayConfig(viewModel.overlayConfig.value.copy(bgOpacity = value.toInt()))
        }

        binding.btnSetPosition.setOnClickListener { showPositionDragOverlay() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settings.collectLatest { settings ->
                isUpdatingUi = true
                if (binding.switchEnableCounter.isChecked != settings.isReelCounterOn) {
                    binding.switchEnableCounter.isChecked = settings.isReelCounterOn
                }
                isUpdatingUi = false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.overlayConfig.collectLatest { config ->
                isUpdatingUi = true

                if (binding.sliderReelTextSize.value != config.textSize) {
                    binding.sliderReelTextSize.value = config.textSize.coerceIn(24f, 120f)
                }
                binding.tvReelTextSizeLabel.text = getString(R.string.text_size_value, config.textSize.toInt())

                if (binding.sliderReelTextOpacity.value != config.textOpacity.toFloat()) {
                    binding.sliderReelTextOpacity.value = config.textOpacity.toFloat().coerceIn(0f, 100f)
                }
                binding.tvReelTextOpacityLabel.text = getString(R.string.text_opacity_value, config.textOpacity)

                if (binding.sliderReelOpacity.value != config.bgOpacity.toFloat()) {
                    binding.sliderReelOpacity.value = config.bgOpacity.toFloat().coerceIn(0f, 100f)
                }
                binding.tvReelOpacityLabel.text = getString(R.string.opacity_value, config.bgOpacity)

                val colorIdx = OverlayDragHelper.PRESET_COLORS.indexOfFirst { it == config.bgColor }.takeIf { it >= 0 } ?: 0
                if (selectedColorIndex != colorIdx) {
                    selectedColorIndex = colorIdx
                    OverlayDragHelper.refreshChipSelection(colorChipViews, selectedColorIndex, resources.displayMetrics.density)
                }

                isUpdatingUi = false
            }
        }

        viewModel.weeklyData.observe(viewLifecycleOwner) { data ->
            binding.weeklyBarGraph.setData(data, viewModel.selectedDayIndex.value ?: 6)
        }

        viewModel.selectedDayIndex.observe(viewLifecycleOwner) { index ->
            binding.weeklyBarGraph.setSelectedIndex(index)
        }

        viewModel.selectedDayTotal.observe(viewLifecycleOwner) { count ->
            binding.totalReelsCount.text = count.toString()
        }

        viewModel.dateSublabel.observe(viewLifecycleOwner) { label ->
            binding.dateSublabel.text = label
        }

        viewModel.weekRangeLabel.observe(viewLifecycleOwner) { label ->
            binding.tvWeekRange.text = label
        }

        viewModel.canGoNext.observe(viewLifecycleOwner) { canGo ->
            binding.btnNextWeek.alpha = if (canGo) 1f else 0.3f
            binding.btnNextWeek.isEnabled = canGo
        }
    }

    private fun showPositionDragOverlay() {
        if (positionScrim != null) return
        val config = viewModel.overlayConfig.value
        positionScrim = OverlayDragHelper.showDragOverlay(
            fragment = this,
            layoutResId = R.layout.overlay_usage_stat,
            positionX = config.positionX,
            positionY = config.positionY,
            setupWidget = { widget ->
                val r = (config.bgColor shr 16) and 0xFF
                val g = (config.bgColor shr 8) and 0xFF
                val b = config.bgColor and 0xFF
                widget.setBackgroundColor(Color.argb(config.bgOpacity * 255 / 100, r, g, b))
                widget.findViewById<TextView>(R.id.reel_counter).apply {
                    visibility = View.VISIBLE
                    text = "42"
                    textSize = config.textSize
                    alpha = config.textOpacity / 100f
                }
                widget.findViewById<TextView>(R.id.time_elapsed_txt).apply {
                    visibility = View.GONE
                }
            },
            onPositionSaved = { x, y ->
                viewModel.updateOverlayConfig(config.copy(positionX = x, positionY = y))
            },
            onDismiss = { positionScrim = null }
        )
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
