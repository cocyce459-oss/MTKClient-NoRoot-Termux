package dev.cocyce.mtknative.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import dev.cocyce.mtknative.R
import dev.cocyce.mtknative.databinding.FragmentPartitionsBinding

/**
 * Partition browser: the native equivalent of `printgpt`, with tap-to-dump.
 */
class PartitionsFragment : Fragment() {

    private var _binding: FragmentPartitionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MtkViewModel by activityViewModels()

    private lateinit var adapter: PartitionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPartitionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PartitionAdapter { partition -> confirmDump(partition.name) { viewModel.dumpPartition(partition) } }
        binding.partitionList.layoutManager = LinearLayoutManager(requireContext())
        binding.partitionList.adapter = adapter
        binding.partitionList.setHasFixedSize(true)

        binding.refreshButton.setOnClickListener { viewModel.refreshPartitions() }

        viewModel.sectorSize.observe(viewLifecycleOwner) { size -> adapter.setSectorSize(size) }

        viewModel.partitions.observe(viewLifecycleOwner) { partitions ->
            adapter.submitList(partitions)
            val hasData = partitions.isNotEmpty()
            binding.partitionList.visibility = if (hasData) View.VISIBLE else View.GONE
            binding.emptyText.visibility = if (hasData) View.GONE else View.VISIBLE
            binding.summaryText.text = if (hasData) {
                resources.getQuantityString(
                    R.plurals.partition_count, partitions.size, partitions.size
                )
            } else {
                ""
            }
        }

        viewModel.busy.observe(viewLifecycleOwner) { busy ->
            binding.refreshButton.isEnabled = !busy
            binding.refreshProgress.visibility = if (busy) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDump(name: String, action: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dump_title, name))
            .setMessage(R.string.dump_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dump_confirm) { _, _ -> action() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.partitionList.adapter = null
        _binding = null
    }
}
