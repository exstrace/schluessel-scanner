package de.ric.schluessel

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class KeyAdapter(private val onClick: (KeyEntry) -> Unit) : RecyclerView.Adapter<KeyAdapter.VH>() {

    private var items: List<KeyEntry> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<KeyEntry>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.ivThumb)
        val name: TextView = v.findViewById(R.id.tvName)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val meta: TextView = v.findViewById(R.id.tvMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_key, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val k = items[position]
        h.name.text = k.name
        h.sub.text = k.subtitle().ifBlank { "–" }
        h.meta.text = "Fotos: ${k.count(SIDE_A)}× Seite A · ${k.count(SIDE_B)}× Seite B"
        h.thumb.setImageBitmap(k.firstImage()?.let { ImageUtil.thumb(it.file) })
        h.itemView.setOnClickListener { onClick(k) }
    }
}

class ResultAdapter(
    private val onOpen: (KeyEntry) -> Unit,
    private val onConfirm: (KeyMatcher.MatchResult) -> Unit
) : RecyclerView.Adapter<ResultAdapter.VH>() {

    private var items: List<KeyMatcher.MatchResult> = emptyList()
    private var maxScore = 1

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<KeyMatcher.MatchResult>) {
        items = list
        maxScore = (list.maxOfOrNull { it.score } ?: 1).coerceAtLeast(1)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.tvRank)
        val thumb: ImageView = v.findViewById(R.id.ivThumb)
        val name: TextView = v.findViewById(R.id.tvName)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val score: TextView = v.findViewById(R.id.tvScore)
        val bar: ProgressBar = v.findViewById(R.id.pbScore)
        val confirm: MaterialButton = v.findViewById(R.id.btnConfirm)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        h.rank.text = "${position + 1}."
        h.name.text = r.key.name
        h.sub.text = r.key.subtitle().ifBlank { "–" }
        h.score.text = "${r.score} Übereinstimmungen"
        h.bar.max = maxScore
        h.bar.progress = r.score
        h.thumb.setImageBitmap(r.key.firstImage()?.let { ImageUtil.thumb(it.file) })
        h.itemView.setOnClickListener { onOpen(r.key) }
        h.confirm.setOnClickListener { onConfirm(r) }
    }
}
