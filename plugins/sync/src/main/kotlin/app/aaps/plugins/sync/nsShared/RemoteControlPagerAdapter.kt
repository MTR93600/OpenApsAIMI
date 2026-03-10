package app.aaps.plugins.sync.nsShared

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.aaps.plugins.sync.R

/**
 * ViewPager2 adapter for Remote Control tabs.
 * Manages 3 tabs: MODES, CONTEXTS, ACTIVE.
 */
class RemoteControlPagerAdapter(
    private val modesAdapter: ModesAdapter,
    private val contextsAdapter: ModesAdapter,
    private val activeAdapter: ActiveModesAdapter,
    private val onRefreshActive: () -> Unit,
    private val onSendLLM: (String) -> Unit  // LLM callback
) : RecyclerView.Adapter<RemoteControlPagerAdapter.TabViewHolder>() {

    companion object {
        const val TAB_MODES = 0
        const val TAB_CONTEXTS = 1
        const val TAB_ACTIVE = 2
        const val TAB_LLM = 3
        const val TAB_COUNT = 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val layoutRes = when (viewType) {
            TAB_MODES -> R.layout.tab_modes
            TAB_CONTEXTS -> R.layout.tab_contexts
            TAB_ACTIVE -> R.layout.tab_active
            TAB_LLM -> R.layout.tab_llm
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
        
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        when (position) {
            TAB_MODES -> holder.bindModesTab(modesAdapter)
            TAB_CONTEXTS -> holder.bindContextsTab(contextsAdapter)
            TAB_ACTIVE -> holder.bindActiveTab(activeAdapter, onRefreshActive)
            TAB_LLM -> holder.bindLLMTab(onSendLLM)
        }
    }

    override fun getItemCount() = TAB_COUNT

    override fun getItemViewType(position: Int) = position

    class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        
        fun bindModesTab(adapter: ModesAdapter) {
            val recyclerView = itemView.findViewById<RecyclerView>(R.id.modes_recycler)
            recyclerView.layoutManager = LinearLayoutManager(itemView.context)
            recyclerView.adapter = adapter
            
            // Filter to show MEAL + ACTIVITY + CONTROL modes (includes Sport)
            adapter.filterByCategories(setOf(ModeCategory.MEAL, ModeCategory.ACTIVITY, ModeCategory.CONTROL))
        }
        
        fun bindContextsTab(adapter: ModesAdapter) {
            val recyclerView = itemView.findViewById<RecyclerView>(R.id.contexts_recycler)
            recyclerView.layoutManager = LinearLayoutManager(itemView.context)
            recyclerView.adapter = adapter
            
            // Filter to show CONTEXT_ONLY (Cardio, Musculation, Yoga, Marche) + PHYSIO contexts
            adapter.filterByCategories(setOf(ModeCategory.CONTEXT_ONLY, ModeCategory.PHYSIO))
        }
        
        fun bindActiveTab(adapter: ActiveModesAdapter, onRefresh: () -> Unit) {
            val recyclerView = itemView.findViewById<RecyclerView>(R.id.active_recycler)
            recyclerView.layoutManager = LinearLayoutManager(itemView.context)
            recyclerView.adapter = adapter
            
            // Trigger initial refresh
            onRefresh()
        }
        
        fun bindLLMTab(onSendLLM: (String) -> Unit) {
            val textInput = itemView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.llm_text_input)
            val sendButton = itemView.findViewById<android.widget.Button>(R.id.btn_send_llm)
            val statusText = itemView.findViewById<android.widget.TextView>(R.id.llm_status)
            
            sendButton.setOnClickListener {
                val text = textInput.text?.toString() ?: ""
                if (text.isNotBlank()) {
                    statusText.visibility = android.view.View.VISIBLE
                    onSendLLM(text)
                    
                    // Clear input after sending
                    textInput.text?.clear()
                }
            }
        }
    }
}
