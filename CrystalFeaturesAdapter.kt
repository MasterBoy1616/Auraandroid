package com.aura.link

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class CrystalFeaturesAdapter(
    private val onFeatureClick: (CrystalStoreActivity.CrystalFeature) -> Unit
) : ListAdapter<CrystalStoreActivity.CrystalFeature, CrystalFeaturesAdapter.FeatureViewHolder>(FeatureDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeatureViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crystal_feature, parent, false)
        return FeatureViewHolder(view)
    }

    override fun onBindViewHolder(holder: FeatureViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FeatureViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvFeatureTitle)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvFeatureDescription)
        private val tvCost: TextView = itemView.findViewById(R.id.tvFeatureCost)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvFeatureDuration)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvFeatureCategory)
        private val btnPurchase: Button = itemView.findViewById(R.id.btnPurchaseFeature)
        private val tvPopular: TextView = itemView.findViewById(R.id.tvPopularBadge)

        fun bind(feature: CrystalStoreActivity.CrystalFeature) {
            tvTitle.text = feature.title
            tvDescription.text = feature.description
            tvCost.text = "${feature.cost} Aura"
            tvDuration.text = feature.duration
            tvCategory.text = feature.category
            
            // Popular badge
            tvPopular.visibility = if (feature.isPopular) View.VISIBLE else View.GONE
            
            btnPurchase.setOnClickListener {
                onFeatureClick(feature)
            }
            
            // Kategori rengine göre stil
            val categoryColor = when (feature.category) {
                itemView.context.getString(R.string.category_instant_boost) -> android.graphics.Color.parseColor("#FF4500")
                itemView.context.getString(R.string.category_spontaneous_expression) -> android.graphics.Color.parseColor("#9932CC")
                itemView.context.getString(R.string.category_time_based) -> android.graphics.Color.parseColor("#FFD700")
                itemView.context.getString(R.string.category_spontaneous_event) -> android.graphics.Color.parseColor("#FF1493")
                itemView.context.getString(R.string.category_smart_matching) -> android.graphics.Color.parseColor("#00CED1")
                itemView.context.getString(R.string.category_personal_expression) -> android.graphics.Color.parseColor("#32CD32")
                itemView.context.getString(R.string.category_visual_customization) -> android.graphics.Color.parseColor("#FF6347")
                else -> android.graphics.Color.parseColor("#00E5FF")
            }
            tvCategory.setTextColor(categoryColor)
        }
    }

    class FeatureDiffCallback : DiffUtil.ItemCallback<CrystalStoreActivity.CrystalFeature>() {
        override fun areItemsTheSame(
            oldItem: CrystalStoreActivity.CrystalFeature,
            newItem: CrystalStoreActivity.CrystalFeature
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: CrystalStoreActivity.CrystalFeature,
            newItem: CrystalStoreActivity.CrystalFeature
        ): Boolean = oldItem == newItem
    }
}