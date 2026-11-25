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
    }

    override fun getItemCount() = apps.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon)
        private val label: TextView = itemView.findViewById(R.id.label)

        fun bind(app: App, listener: ((App) -> Unit)?) {
            icon.setImageDrawable(app.icon)
            label.text = app.label
            itemView.setOnClickListener { listener?.invoke(app) }
        }
    }
}
