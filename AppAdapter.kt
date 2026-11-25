package com.tvos.launcher

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var apps = listOf<App>()
    private var longListener: ((App) -> Unit)? = null
    fun setOnItemLongClickListener(listener: (App) -> Unit) { longListener = listener }
    private var listener: ((App) -> Unit)? = null

    fun update(newApps: List<App>) {
        apps = newApps
        notifyDataSetChanged()
    }

    fun setOnItemClickListener(listener: (App) -> Unit) {
        this.listener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.bind(app, listener)
        holder.itemView.isFocusable = true
        holder.itemView.setOnLongClickListener { longListener?.invoke(app); true }
    }

    override fun getItemCount() = apps.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val label: TextView = itemView.findViewById(R.id.label)

        fun bind(app: App, listener: ((App) -> Unit)?) {
            icon.setImageDrawable(app.icon)
            label.text = app.label
            itemView.setOnClickListener { listener?.invoke(app) }
            itemView.setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f).setDuration(120).start()
            }
        }
    }
}
