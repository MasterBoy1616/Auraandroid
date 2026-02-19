package com.aura.link

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class CrystalPacksAdapter(
    private val onPackClick: (CrystalStoreActivity.CrystalPack) -> Unit
) : ListAdapter<CrystalStoreActivity.CrystalPack, CrystalPacksAdapter.PackViewHolder>(PackDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crystal_pack, parent, false)
        return PackViewHolder(view)
    }

    override fun onBindViewHolder(holder: PackViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvPackTitle)
        private val tvCrystals: TextView = itemView.findViewById(R.id.tvPackCrystals)
        private val tvBonus: TextView = itemView.findViewById(R.id.tvPackBonus)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvPackPrice)
        private val btnPurchase: Button = itemView.findViewById(R.id.btnPurchasePack)
        private val tvPopular: TextView = itemView.findViewById(R.id.tvPackPopularBadge)

        fun bind(pack: CrystalStoreActivity.CrystalPack) {
            tvTitle.text = pack.title
            tvCrystals.text = "${pack.crystals} Aura"
            tvPrice.text = pack.price
            
            // Bonus gösterimi
            if (pack.bonus > 0) {
                tvBonus.text = "+${pack.bonus} Bonus"
                tvBonus.visibility = View.VISIBLE
            } else {
                tvBonus.visibility = View.GONE
            }
            
            // Popular badge
            tvPopular.visibility = if (pack.isPopular) View.VISIBLE else View.GONE
            
            btnPurchase.setOnClickListener {
                onPackClick(pack)
            }
            
            // Popular paketler için özel stil
            if (pack.isPopular) {
                itemView.setBackgroundResource(R.drawable.primary_button_background)
                // Popular paketlerde text renklerini koyu yap (okunabilir olsun)
                tvTitle.setTextColor(0xFF000000.toInt()) // Siyah
                tvCrystals.setTextColor(0xFF333333.toInt()) // Koyu gri
                tvBonus.setTextColor(0xFF006600.toInt()) // Koyu yeşil
                tvPrice.setTextColor(0xFF000000.toInt()) // Siyah
            } else {
                itemView.setBackgroundResource(R.drawable.chip_background)
                // Normal paketlerde beyaz text
                tvTitle.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                tvCrystals.setTextColor(0xFF00E5FF.toInt()) // Neon mavi
                tvBonus.setTextColor(0xFF00FF00.toInt()) // Yeşil
                tvPrice.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
            }
        }
    }

    class PackDiffCallback : DiffUtil.ItemCallback<CrystalStoreActivity.CrystalPack>() {
        override fun areItemsTheSame(
            oldItem: CrystalStoreActivity.CrystalPack,
            newItem: CrystalStoreActivity.CrystalPack
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: CrystalStoreActivity.CrystalPack,
            newItem: CrystalStoreActivity.CrystalPack
        ): Boolean = oldItem == newItem
    }
}