package com.pintoinsta.app.ui.pins

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

class DirectViewsPagerAdapter(
    private val pages: List<View>
) : RecyclerView.Adapter<DirectViewsPagerAdapter.PageViewHolder>() {

    class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    override fun getItemCount(): Int = pages.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return PageViewHolder(container)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val pageView = pages[position]
        (pageView.parent as? ViewGroup)?.removeView(pageView)
        holder.container.removeAllViews()
        holder.container.addView(pageView)
    }
}
