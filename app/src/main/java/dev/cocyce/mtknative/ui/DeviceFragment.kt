package dev.cocyce.mtknative.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.cocyce.mtknative.R
import dev.cocyce.mtknative.databinding.FragmentDeviceBinding
import dev.cocyce.mtknative.engine.LogLevel

/**
 * Connection control and the live engine log.
 *
 * Everything on this screen is safe to press: connecting, identifying and
 * reading memory do not modify the target. The one destructive action,
 * [R.id.rebootButton], is gated behind a confirmation dialog.
 */
class DeviceFragment : Fragment() {

    private var _binding: FragmentDeviceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MtkViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.connectButton.setOnClickListener { viewModel.connect() }
        binding.disconnectButton.setOnClickListener { viewModel.disconnect() }
        binding.clearLogButton.setOnClickListener {
            viewModel.clearLogs()
            binding.logText.text = ""
        }
        binding.rebootButton.setOnClickListener { confirmReboot() }

        binding.memReadButton.setOnClickListener {
            val address = binding.memAddressInput.text?.toString().orEmpty()
            val length = binding.memLengthInput.text?.toString().orEmpty()
            if (address.isBlank() || length.isBlank()) {
                binding.memReadLayout.error = getString(R.string.error_fields_required)
                return@setOnClickListener
            }
            binding.memReadLayout.error = null
            viewModel.memRead(address, length)
        }

        viewModel.state.observe(viewLifecycleOwner) { render(it) }
        viewModel.busy.observe(viewLifecycleOwner) { busy ->
            binding.connectButton.isEnabled = !busy
            binding.rebootButton.isEnabled = !busy
            binding.memReadButton.isEnabled = !busy
        }
        viewModel.logs.observe(viewLifecycleOwner) { lines ->
            binding.logText.text = lines.joinToString("\n") {
                "${it.timestamp}  ${it.level.shortLabel().padEnd(5)}  ${it.message}"
            }
            // Capture the view: a posted runnable can fire after onDestroyView
            // has nulled the binding.
            val scroll = binding.logScroll
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun render(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                binding.deviceCard.visibility = View.VISIBLE
                binding.chipValue.text = state.info.chip.displayName
                binding.hwcodeValue.text =
                    "%08X".format(state.info.hwcode)
                binding.usbIdValue.text = state.info.usbId
                binding.modeValue.text =
                    if (state.info.isBromMode) getString(R.string.mode_brom)
                    else getString(R.string.mode_preloader)
                binding.disconnectButton.isEnabled = true
                binding.rebootButton.isEnabled = true
            }
            else -> {
                binding.deviceCard.visibility = View.GONE
                binding.disconnectButton.isEnabled = false
                binding.rebootButton.isEnabled = false
            }
        }
    }

    private fun confirmReboot() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reboot_title)
            .setMessage(R.string.reboot_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reboot_confirm) { _, _ -> viewModel.reboot() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun LogLevel.shortLabel(): String = when (this) {
        LogLevel.DEBUG -> "DEBUG"
        LogLevel.INFO -> "INFO"
        LogLevel.WARN -> "WARN"
        LogLevel.ERROR -> "ERROR"
    }
}
