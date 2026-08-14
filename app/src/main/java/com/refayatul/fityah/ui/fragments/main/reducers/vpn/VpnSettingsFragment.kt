package com.refayatul.fityah.ui.fragments.main.reducers.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.refayatul.fityah.R
import com.refayatul.fityah.databinding.FragmentVpnSettingsBinding
import com.refayatul.fityah.services.vpn.DnsVpnService
import com.refayatul.fityah.ui.activity.FragmentActivity
import com.refayatul.fityah.utils.BlocklistManager
import com.refayatul.fityah.utils.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VpnSettingsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "vpn_settings"
    }

    private var _binding: FragmentVpnSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataStoreManager: DataStoreManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(requireContext(), "VPN permission denied", Toast.LENGTH_SHORT).show()
            binding.switchVpnEnable.isChecked = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVpnSettingsBinding.inflate(inflater, container, false)
        dataStoreManager = DataStoreManager(requireContext())
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = dataStoreManager.settings.first()
            val config = settings.vpnConfig
            
            binding.switchVpnEnable.isChecked = config.isEnabled
            binding.switchLocalBlocklist.isChecked = config.useLocalBlocklist
            binding.switchSafesearch.isChecked = config.forcedSafeSearch
            binding.switchLockPrivateDns.isChecked = config.lockPrivateDns
            
            val serverCount = config.dnsServers.size
            binding.textActiveDns.text = if (serverCount == 1) {
                config.dnsServers[0].address
            } else {
                "$serverCount Servers"
            }
            
            setupListeners()
        }
    }

    private fun setupListeners() {
        // Clear listeners first to avoid loop during refresh
        binding.switchVpnEnable.setOnCheckedChangeListener(null)
        binding.switchLocalBlocklist.setOnCheckedChangeListener(null)
        binding.switchSafesearch.setOnCheckedChangeListener(null)
        binding.switchLockPrivateDns.setOnCheckedChangeListener(null)

        binding.switchVpnEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                prepareVpn()
            } else {
                stopVpnService()
            }
            updateConfig()
        }

        binding.switchLocalBlocklist.setOnCheckedChangeListener { _, _ -> updateConfig() }
        binding.switchSafesearch.setOnCheckedChangeListener { _, _ -> updateConfig() }
        
        binding.switchLockPrivateDns.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !com.refayatul.fityah.utils.PermissionUtils.hasShizukuPermission()) {
                if (com.refayatul.fityah.utils.PermissionUtils.isShizukuAvailable()) {
                    rikka.shizuku.Shizuku.requestPermission(1002)
                } else {
                    Toast.makeText(requireContext(), "Shizuku not available", Toast.LENGTH_SHORT).show()
                    binding.switchLockPrivateDns.isChecked = false
                }
            }
            updateConfig()
        }
        
        binding.btnRefreshBlocklists.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val manager = BlocklistManager(requireContext())
                manager.refreshAll()
                Toast.makeText(requireContext(), R.string.done, Toast.LENGTH_SHORT).show()
            }
        }

        binding.cardDnsServers.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", DnsServersFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.cardBlocklistSources.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", BlocklistSourcesFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.cardAllowlist.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AllowlistFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.cardExemptApps.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", ExemptAppsFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.btnTestRust.setOnClickListener {
            try {
                System.loadLibrary("fityah_rust")
                val msg = uniffi.fityah_rust.pingRust()
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Rust call failed: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Error) {
                Toast.makeText(requireContext(), "Rust load failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun updateConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = dataStoreManager.settings.first().vpnConfig
            val newConfig = current.copy(
                isEnabled = binding.switchVpnEnable.isChecked,
                useLocalBlocklist = binding.switchLocalBlocklist.isChecked,
                forcedSafeSearch = binding.switchSafesearch.isChecked,
                lockPrivateDns = binding.switchLockPrivateDns.isChecked
            )
            dataStoreManager.updateVpnConfig(newConfig)
            
            if (newConfig.lockPrivateDns) {
                com.refayatul.fityah.utils.DnsLockManager(requireContext()).applyLock(newConfig)
            }
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(requireContext(), DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
        }
        requireContext().startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(requireContext(), DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_STOP
        }
        requireContext().startService(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
