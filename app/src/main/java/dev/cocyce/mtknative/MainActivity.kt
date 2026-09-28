package dev.cocyce.mtknative

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.tabs.TabLayout
import dev.cocyce.mtknative.databinding.ActivityMainBinding
import dev.cocyce.mtknative.engine.LogLevel
import dev.cocyce.mtknative.ui.ConnectionState
import dev.cocyce.mtknative.ui.ConsoleFragment
import dev.cocyce.mtknative.ui.DeviceFragment
import dev.cocyce.mtknative.ui.MtkViewModel
import dev.cocyce.mtknative.ui.PartitionsFragment

/**
 * Single-activity host for the three tool surfaces: device control, the
 * partition browser, and the `mtk.py`-style console.
 *
 * Declaring `USB_DEVICE_ATTACHED` in the manifest means Android can launch this
 * activity the moment an MTK device is plugged in — the decisive UX win over the
 * Termux bridge, where the user had to accept a popup within about three seconds
 * or lose BROM to the watchdog.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MtkViewModel by viewModels()

    private val fragments: List<Fragment> by lazy {
        listOf(DeviceFragment(), PartitionsFragment(), ConsoleFragment())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupTabs()
        setupStatusStrip()
        observeViewModel()
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private fun setupTabs() {
        listOf(TAB_DEVICE, TAB_PARTITIONS, TAB_CONSOLE).forEach { title ->
            binding.tabs.addTab(binding.tabs.newTab().setText(title))
        }
        showTab(0)
        binding.tabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            }
        )
    }

    private fun showTab(index: Int) {
        val tag = "tab_$index"
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            fragments.forEachIndexed { position, fragment ->
                if (position == index) {
                    if (!fragment.isAdded) add(R.id.fragmentHost, fragment, tag) else show(fragment)
                } else if (fragment.isAdded) {
                    hide(fragment)
                }
            }
        }
    }

    private fun setupStatusStrip() {
        binding.progressIndicator.visibility = View.GONE
        binding.progressText.visibility = View.GONE
    }

    private fun observeViewModel() {
        viewModel.state.observe(this) { state -> renderState(state) }

        viewModel.progress.observe(this) { progress ->
            if (progress == null) {
                binding.progressIndicator.visibility = View.GONE
                binding.progressText.visibility = View.GONE
            } else {
                binding.progressIndicator.visibility = View.VISIBLE
                binding.progressText.visibility = View.VISIBLE
                binding.progressText.text = progress.text
                binding.progressIndicator.isIndeterminate = progress.total <= 0
                if (progress.total > 0) {
                    binding.progressIndicator.setProgressCompat(
                        (progress.fraction * 100).toInt(), true
                    )
                }
            }
        }

        viewModel.busy.observe(this) { busy ->
            binding.busyIndicator.visibility = if (busy) View.VISIBLE else View.GONE
        }
    }

    private fun renderState(state: ConnectionState) {
        binding.statusText.text = when (state) {
            is ConnectionState.Idle -> getString(R.string.status_idle)
            is ConnectionState.Scanning -> getString(R.string.status_scanning)
            is ConnectionState.AwaitingPermission ->
                getString(R.string.status_permission, state.deviceId)
            is ConnectionState.Connected -> state.info.summary
            is ConnectionState.Failed -> getString(R.string.status_failed, state.reason)
        }
        binding.statusDot.setImageResource(
            when (state) {
                is ConnectionState.Connected -> R.drawable.ic_status_connected
                is ConnectionState.Failed -> R.drawable.ic_status_error
                is ConnectionState.Scanning,
                is ConnectionState.AwaitingPermission -> R.drawable.ic_status_busy
                is ConnectionState.Idle -> R.drawable.ic_status_idle
            }
        )
    }

    /**
     * Reacts to a plug-in event delivered by the system.
     *
     * Permission is granted implicitly when the user launches the app from the
     * USB chooser, so we can go straight to opening the transport.
     */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = extractDevice(intent) ?: return
        viewModel.onLog(LogLevel.INFO, "Device attached: ${describe(device)} — connecting…")
        // connectToDevice() is not a suspend function: it launches its own
        // viewModelScope work and reports through the listener.
        viewModel.connectToDevice(device)
    }

    @Suppress("DEPRECATION")
    private fun extractDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun describe(device: UsbDevice): String =
        "%04X:%04X".format(device.vendorId, device.productId)

    private companion object {
        const val TAB_DEVICE = "Device"
        const val TAB_PARTITIONS = "Partitions"
        const val TAB_CONSOLE = "Console"
    }
}
