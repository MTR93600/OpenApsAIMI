package app.aaps.plugins.sync.nsShared

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.aaps.plugins.sync.R

/**
 * RecyclerView adapter for mode selection.
 * Shows mode name, icon, editable duration, and SEND button.
 */
class ModesAdapter(
    private val onSendMode: (ModePreset, Int) -> Unit
) : RecyclerView.Adapter<ModesAdapter.ModeViewHolder>() {

    private var allModes = ModePresets.ALL_MODES
    private var displayedModes = allModes
    // Track custom durations per mode ID
    private val customDurations = mutableMapOf<String, Int>()

    /**
     * Filter modes by category.
     */
    fun filterByCategories(categories: Set<ModeCategory>) {
        displayedModes = if (categories.isEmpty()) {
            allModes
        } else {
            allModes.filter { it.category in categories }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_remote_mode, parent, false)
        return ModeViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModeViewHolder, position: Int) {
        val mode = displayedModes[position]
        holder.bind(mode)
    }

    override fun getItemCount() = displayedModes.size

    inner class ModeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.mode_icon)
        private val name: TextView = itemView.findViewById(R.id.mode_name)
        private val description: TextView = itemView.findViewById(R.id.mode_description)
        private val durationInput: EditText = itemView.findViewById(R.id.duration_input)
        private val durationLabel: TextView = itemView.findViewById(R.id.duration_label)
        private val sendButton: Button = itemView.findViewById(R.id.btn_send_mode)
        
        // Track text watcher to prevent memory leaks on view recycling
        private var textWatcher: TextWatcher? = null

        fun bind(mode: ModePreset) {
            icon.setImageResource(mode.icon)
            name.text = mode.displayName
            description.text = mode.description

            // Special handling for "Stop" mode (no duration)
            if (mode.id == "stop") {
                durationInput.visibility = View.GONE
                durationLabel.visibility = View.GONE
                sendButton.setOnClickListener {
                    onSendMode(mode, 0)
                }
            } else {
                durationInput.visibility = View.VISIBLE
                durationLabel.visibility = View.VISIBLE
                
                // Set duration (custom or default)
                val duration = customDurations[mode.id] ?: mode.defaultDurationMin
                
                // Remove old text watcher to prevent memory leak
                textWatcher?.let { durationInput.removeTextChangedListener(it) }
                
                // Set text without triggering listener
                durationInput.setText(duration.toString())
                
                // Create and add new text watcher
                textWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val newDuration = s?.toString()?.toIntOrNull()
                        if (newDuration != null && newDuration > 0) {
                            customDurations[mode.id] = newDuration
                        }
                    }
                }
                durationInput.addTextChangedListener(textWatcher)
            }

            // Send button click
            sendButton.setOnClickListener {
                val duration = if (mode.id == "stop") {
                    0
                } else {
                    durationInput.text.toString().toIntOrNull() ?: mode.defaultDurationMin
                }
                onSendMode(mode, duration)
            }
        }
    }
}
