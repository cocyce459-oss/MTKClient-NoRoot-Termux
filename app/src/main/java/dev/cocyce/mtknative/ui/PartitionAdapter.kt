package dev.cocyce.mtknative.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.cocyce.mtknative.databinding.ItemPartitionBinding
import dev.cocyce.mtknative.gpt.GptPartition
import dev.cocyce.mtknative.gpt.GptTable

/** Row renderer for the partition browser. */
class PartitionAdapter(
    private var sectorSize: Int = 0x200,
    private val onDump: (GptPartition) -> Unit
) : ListAdapter<GptPartition, PartitionAdapter.PartitionViewHolder>(DIFF) {

    fun setSectorSize(size: Int) {
        if (sectorSize != size) {
            sectorSize = size
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PartitionViewHolder {
        val binding = ItemPartitionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PartitionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PartitionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PartitionViewHolder(
        private val binding: ItemPartitionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(partition: GptPartition) {
            binding.nameText.text = partition.name.ifBlank { "(unnamed)" }
            binding.typeText.text = partition.typeName
            binding.sizeText.text = GptTable.formatSize(partition.byteLength(sectorSize))
            binding.rangeText.text = "LBA ${partition.firstLba} – ${partition.lastLba}"

            val badges = StringBuilder()
            if (partition.isActiveSlot) badges.append("active ")
            if (partition.isBootSuccessful) badges.append("boot-ok ")
            if (partition.isUnbootable) badges.append("unbootable")
            binding.flagText.text = badges.toString().trim()
            binding.flagText.visibility =
                if (badges.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

            binding.dumpButton.setOnClickListener { onDump(partition) }
            binding.root.setOnClickListener { onDump(partition) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<GptPartition>() {
            override fun areItemsTheSame(old: GptPartition, new: GptPartition): Boolean =
                old.uniqueGuid == new.uniqueGuid && old.firstLba == new.firstLba

            override fun areContentsTheSame(old: GptPartition, new: GptPartition): Boolean =
                old == new
        }
    }
}
