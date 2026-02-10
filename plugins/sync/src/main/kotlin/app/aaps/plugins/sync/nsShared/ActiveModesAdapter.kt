package app.aaps.plugins.sync.nsShared

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.aaps.plugins.sync.R

/**
 * RecyclerView adapter for displaying active modes.
 * Shows mode name, icon, remaining time, and progress bar.
 */
class ActiveModesAdapter : RecyclerView.Adapter<ActiveModesAdapter.ActiveModeViewHolder>() {

    private var activeModes = listOf<ActiveModeItem>()

    fun updateModes(newModes: List<ActiveModeItem>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = activeModes.size
            override fun getNewListSize() = newModes.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                activeModes[oldPos].mode.id == newModes[newPos].mode.id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                activeModes[oldPos].remainingMs == newModes[newPos].remainingMs
        })
        
        activeModes = newModes
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActiveModeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_active_mode, parent, false)
        return ActiveModeViewHolder(view)
    }

    override fun onBindViewHolder(holder: ActiveModeViewHolder, position: Int) {
        holder.bind(activeModes[position])
    }

    override fun getItemCount() = activeModes.size

    class ActiveModeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.active_mode_icon)
        private val name: TextView = itemView.findViewById(R.id.active_mode_name)
        private val remaining: TextView = itemView.findViewById(R.id.active_mode_remaining)
        private val progress: ProgressBar = itemView.findViewById(R.id.active_mode_progress)

        fun bind(item: ActiveModeItem) {
            icon.setImageResource(item.mode.icon)
            name.text = item.mode.displayName
            
            // Format remaining time
            val minutes = item.remainingMinutes
            remaining.text = when {
                minutes >= 60 -> "${minutes / 60}h ${minutes % 60}min restantes"
                minutes > 0 -> "$minutes min restantes"
                else -> "Expiré"
            }
            
            // Update progress bar
            progress.max = 100
            progress.progress = item.progressPercent
        }
    }
}
