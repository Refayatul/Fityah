package com.refayatul.fityah.ui.fragments.main.reducers.blockertools.shared

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.refayatul.fityah.data.models.AppBlockerWarningScreenConfig
import com.refayatul.fityah.databinding.FragmentWarningConfigBinding
import com.refayatul.fityah.utils.DataStoreManager

class WarningConfigFragment : Fragment() {
    private var _binding: FragmentWarningConfigBinding? = null
    private val binding get() = _binding!!

    private var initialConfig: AppBlockerWarningScreenConfig? = null
    private var formController: WarningConfigFormController? = null
    private var qrController: WarningConfigQrController? = null
    private var nfcController: WarningConfigNfcController? = null

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        qrController?.onBarcodeResult(result.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialConfig = arguments?.getString(ARG_CONFIG)?.let { configJson ->
            Gson().fromJson(configJson, AppBlockerWarningScreenConfig::class.java)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWarningConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val formController = WarningConfigFormController(
            fragment = this,
            binding = binding,
            onEachOpenChanged = {
                qrController?.refreshKeyList()
                nfcController?.refreshKeyList()
            }
        )
        val timingDialog = WarningUnlockTimingDialog(
            fragment = this,
            isOnEachOpenEnabled = formController::isOnEachOpenEnabled
        )
        val keyListRenderer = WarningUnlockKeyListRenderer(
            fragment = this,
            isOnEachOpenEnabled = formController::isOnEachOpenEnabled
        )
        val qrController = WarningConfigQrController(
            fragment = this,
            binding = binding,
            barcodeLauncher = barcodeLauncher,
            timingDialog = timingDialog,
            keyListRenderer = keyListRenderer
        )
        val nfcController = WarningConfigNfcController(
            fragment = this,
            binding = binding,
            timingDialog = timingDialog,
            keyListRenderer = keyListRenderer
        )
        this.formController = formController
        this.qrController = qrController
        this.nfcController = nfcController

        val config = initialConfig ?: AppBlockerWarningScreenConfig()
        val isNew = arguments?.getBoolean(ARG_IS_NEW)
            ?: (arguments?.getString(ARG_CONFIG) == null)
        val supportsOnEachOpen =
            arguments?.getBoolean(ARG_SUPPORTS_ON_EACH_OPEN) == true

        formController.bind(config, isNew, supportsOnEachOpen)
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = DataStoreManager(requireContext()).settings.first().manualFocusGroups
            formController.bindFocusGroups(groups)
        }
        qrController.bind(config.qrKeys)
        nfcController.bind(config.nfcKeys)
        formController.setupListeners(::saveConfig)
        qrController.setupListeners()
        nfcController.setupListeners()
    }

    private fun saveConfig() {
        val formController = formController ?: return
        if (!formController.validate()) return
        val qrController = qrController ?: return
        val nfcController = nfcController ?: return
        val config = formController.createConfig(
            qrKeys = qrController.keys(),
            nfcKeys = nfcController.keys()
        )
        val requestKey = arguments?.getString(ARG_REQUEST_KEY) ?: RESULT_KEY
        parentFragmentManager.setFragmentResult(
            requestKey,
            Bundle().apply {
                putString(RESULT_CONFIG, Gson().toJson(config))
            }
        )
        parentFragmentManager.popBackStack()
    }

    override fun onPause() {
        super.onPause()
        nfcController?.stopTap()
    }

    override fun onDestroyView() {
        nfcController?.stopTap()
        formController = null
        qrController = null
        nfcController = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val FRAGMENT_ID = "warning_config_fragment"
        const val ARG_CONFIG = "arg_config"
        const val ARG_REQUEST_KEY = "arg_request_key"
        const val ARG_IS_NEW = "arg_is_new"
        const val ARG_SUPPORTS_ON_EACH_OPEN = "arg_supports_on_each_open"
        const val RESULT_KEY = "request_key_warning_config"
        const val RESULT_CONFIG = "result_config"

        fun newInstance(
            config: AppBlockerWarningScreenConfig,
            requestKey: String = RESULT_KEY,
            isNew: Boolean = false,
            supportsOnEachOpen: Boolean = false
        ): WarningConfigFragment {
            return WarningConfigFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONFIG, Gson().toJson(config))
                    putString(ARG_REQUEST_KEY, requestKey)
                    putBoolean(ARG_IS_NEW, isNew)
                    putBoolean(ARG_SUPPORTS_ON_EACH_OPEN, supportsOnEachOpen)
                }
            }
        }
    }
}
