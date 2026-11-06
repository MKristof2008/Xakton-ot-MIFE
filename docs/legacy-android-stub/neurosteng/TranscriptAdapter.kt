package ru.mifi.neurosteng

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.mifi.neurosteng.databinding.ItemSegmentBinding

/** Список реплик: спикер, время, текст. */
class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.Holder>() {

    private val items = mutableListOf<HexJson.Segment>()

    fun submit(segments: List<HexJson.Segment>) {
        items.clear()
        items.addAll(segments)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSegmentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class Holder(private val binding: ItemSegmentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(segment: HexJson.Segment) {
            binding.speaker.text = segment.speaker_id
            binding.time.text = "${format(segment.start_ms)} – ${format(segment.end_ms)}"
            binding.text.text = segment.text
        }

        private fun format(ms: Int): String {
            val total = ms / 1000
            return "%02d:%02d".format(total / 60, total % 60)
        }
    }
}