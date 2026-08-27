package app.aaps.plugins.aps.openAPSAIMI.context.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.aaps.plugins.aps.databinding.ItemActiveIntentBinding
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent

/**
 * Adapter for active intents RecyclerView.
 * 
 * Displays each intent with:
 * - Icon + Type + Intensity
 * - Time remaining
 * - Remove and Extend buttons
 */
class ContextIntentAdapter(
    private val onRemove: (String) -> Unit,
    private val onExtend: (String) -> Unit,
    private val getTimeRemaining: (ContextIntent) -> String,
    private val getDisplayString: (ContextIntent) -> String
) : ListAdapter<Pair<String, ContextIntent>, ContextIntentAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemActiveIntentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ViewHolder(
        private val binding: ItemActiveIntentBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(item: Pair<String, ContextIntent>) {
            val (id, intent) = item
            
            // Display string (e.g., "🏃 Activity: CARDIO HIGH")
            binding.textIntentType.text = getDisplayString(intent)
            
            // Time remaining
            binding.textTimeRemaining.text = getTimeRemaining(intent)
            
            // Confidence (if available)
            val confidenceText = when {
                intent.confidence >= 0.90 -> "✓ Haute confiance"
                intent.confidence >= 0.70 -> "~ Moyenne confiance"
                intent.confidence >= 0.50 -> "? Faible confiance"
                else -> ""
            }
            binding.textConfidence.text = confidenceText
            
            // Remove button
            binding.btnRemove.setOnClickListener {
                onRemove(id)
            }
            
            // Extend button
            binding.btnExtend.setOnClickListener {
                onExtend(id)
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<Pair<String, ContextIntent>>() {
        override fun areItemsTheSame(
            oldItem: Pair<String, ContextIntent>,
            newItem: Pair<String, ContextIntent>
        ): Boolean {
            return oldItem.first == newItem.first
        }
        
        override fun areContentsTheSame(
            oldItem: Pair<String, ContextIntent>,
            newItem: Pair<String, ContextIntent>
        ): Boolean {
            return oldItem == newItem
        }
    }
}
