package com.example.accesnav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.accesnav.databinding.ActivityHistoryBinding
import com.example.accesnav.databinding.ItemHistoryBinding
import com.example.accesnav.db.AppDatabase
import com.example.accesnav.db.HistoryItem
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = HistoryAdapter()
        binding.historyRecyclerView.adapter = adapter

        lifecycleScope.launch {
            db.historyDao().getAll().collect { items ->
                adapter.submitList(items)
            }
        }

        binding.clearHistoryButton.setOnClickListener {
            lifecycleScope.launch {
                db.historyDao().clearAll()
            }
        }
    }
}

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
    private var items = listOf<HistoryItem>()

    fun submitList(newItems: List<HistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.itemContent.text = item.content
        holder.binding.itemType.text = item.type
        
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        holder.binding.itemTimestamp.text = sdf.format(Date(item.timestamp))
    }

    override fun getItemCount() = items.size
}
