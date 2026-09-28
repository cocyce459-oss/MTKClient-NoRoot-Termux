package dev.cocyce.mtknative.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.cocyce.mtknative.databinding.FragmentConsoleBinding

/**
 * Terminal-parity console.
 *
 * Accepts the same command syntax as upstream `mtk.py` / `stage2.py`, so
 * existing documentation and muscle memory transfer directly. Scrollback is
 * preserved across tab switches because the fragment is hidden, not destroyed.
 */
class ConsoleFragment : Fragment() {

    private var _binding: FragmentConsoleBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MtkViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConsoleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.sendButton.setOnClickListener { submit() }
        binding.clearButton.setOnClickListener {
            viewModel.clearConsole()
            binding.outputText.text = ""
        }

        binding.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                submit()
                true
            } else {
                false
            }
        }

        // Command recall is awkward on a soft keyboard, so the common
        // operations are exposed as tappable chips instead.
        binding.chipPrintGpt.setOnClickListener { runCommand("printgpt") }
        binding.chipInfo.setOnClickListener { runCommand("info") }
        binding.chipHelp.setOnClickListener { runCommand("help") }
        binding.chipLs.setOnClickListener { runCommand("ls") }

        viewModel.console.observe(viewLifecycleOwner) { text ->
            binding.outputText.text = text
            val scroll = binding.outputScroll
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        viewModel.busy.observe(viewLifecycleOwner) { busy ->
            binding.sendButton.isEnabled = !busy
        }
    }

    private fun submit() {
        val command = binding.commandInput.text?.toString().orEmpty().trim()
        if (command.isEmpty()) return
        binding.commandInput.setText("")
        runCommand(command)
    }

    private fun runCommand(command: String) {
        viewModel.submitConsole(command)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
