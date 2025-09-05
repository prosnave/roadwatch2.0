package com.roadwatch.feature.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.roadwatch.app.R
import com.roadwatch.data.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AdminLocationsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_admin_locations, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<ListView>(R.id.list)
        val summaryBody = view.findViewById<TextView>(R.id.txt_summary_body)
        val toggle = view.findViewById<Button>(R.id.btn_toggle_summary)
        val repo = SeedRepository(requireContext())
        val store = HazardStore(requireContext())

        val isAdmin = com.roadwatch.app.BuildConfig.IS_ADMIN
        data class Row(val label: String, val isUser: Boolean, val userId: String?, val seedKey: String?, val active: Boolean, val votes: Int, val bearingSide: String, val directionality: String)

        fun refresh() {
            val seedHazards = repo.loadSeeds().second
            val userHazards = store.list()
            val rows = mutableListOf<Row>()
            seedHazards.forEach { h ->
                val key = SeedOverrides.keyOf(h)
                // Hide disabled seeds entirely so the list reflects what is actually active/visible
                if (SeedOverrides.isDisabled(requireContext(), key)) return@forEach
                val active = h.active
                val votes = CommunityVotes.getVotes(requireContext(), key)
                val createdAt = try { h.createdAt.toString() } catch (_: Exception) { "" }
                rows += Row(label = buildLabel(h.type.name, active, votes, createdAt, h.bearingSide, h.directionality), isUser = false, userId = null, seedKey = key, active = active, votes = votes, bearingSide = h.bearingSide, directionality = h.directionality)
            }
            userHazards.forEach { u ->
                val key = SeedOverrides.keyOf(u.hazard)
                val votes = CommunityVotes.getVotes(requireContext(), key)
                val createdAt = try { u.hazard.createdAt.toString() } catch (_: Exception) { "" }
                rows += Row(label = buildLabel(u.hazard.type.name, u.hazard.active, votes, createdAt, u.hazard.bearingSide, u.hazard.directionality), isUser = true, userId = u.id, seedKey = null, active = u.hazard.active, votes = votes, bearingSide = u.hazard.bearingSide, directionality = u.hazard.directionality)
            }
            // Summary
            val total = rows.size
            val activeCount = rows.count { it.active }
            val byType = (seedHazards.map { it.type.name } + userHazards.map { it.hazard.type.name })
                .groupingBy { it }
                .eachCount()
            val typeLines = byType.entries.sortedBy { it.key }.joinToString("\n") { "- ${it.key}: ${it.value}" }
            summaryBody?.text = "Total: ${total} (Active: ${activeCount})\n${typeLines}"
            list.adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(position: Int) = rows[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val ctx = requireContext()
                    val row = rows[position]
                    val container = convertView as? LinearLayout ?: LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(16, 12, 16, 12)
                        addView(ImageView(ctx).apply { id = android.R.id.icon; layoutParams = LinearLayout.LayoutParams(32,32) })
                        addView(TextView(ctx).apply { id = android.R.id.text1; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                    }
                    val icon = container.findViewById<ImageView>(android.R.id.icon)
                    icon.setImageResource(R.drawable.ic_hazard)
                    icon.setColorFilter(if (row.active) android.graphics.Color.parseColor("#1DB954") else android.graphics.Color.parseColor("#E63946"))
                    container.findViewById<TextView>(android.R.id.text1).text = row.label
                    return container
                }
            }
            list.setOnItemClickListener { _, _, pos, _ ->
                val row = rows[pos]
                val key = row.seedKey ?: row.userId ?: ""
                val voted = CommunityVotes.hasVoted(requireContext(), key)
                val voteAction = if (voted) "Remove Vote" else "Add Vote"
                val actions = when {
                    isAdmin && row.isUser -> arrayOf(voteAction, "Toggle Active", "Delete")
                    isAdmin && !row.isUser -> arrayOf(voteAction, "Toggle Active")
                    else -> arrayOf(voteAction)
                }
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Manage Hazard")
                    .setItems(actions) { _, which ->
                        when (actions[which]) {
                            "Add Vote" -> { CommunityVotes.upvote(requireContext(), key); refresh() }
                            "Remove Vote" -> { CommunityVotes.downvote(requireContext(), key); refresh() }
                            "Toggle Active" -> {
                                if (!isAdmin) return@setItems
                                if (row.isUser) { HazardStore(requireContext()).toggleActive(row.userId!!) }
                                else { row.seedKey?.let { SeedOverrides.toggle(requireContext(), it) } }
                                refresh()
                            }
                            "Delete" -> { if (isAdmin && row.isUser) { HazardStore(requireContext()).delete(row.userId!!); refresh() } }
                        }
                    }
                    .show()
            }
        }
        toggle?.setOnClickListener {
            if (summaryBody?.visibility == View.VISIBLE) {
                summaryBody.visibility = View.GONE
                (it as Button).text = "Show"
            } else {
                summaryBody?.visibility = View.VISIBLE
                (it as Button).text = "Hide"
            }
        }

        refresh()
    }

    private fun buildLabel(type: String, active: Boolean, votes: Int, created: String, bearingSide: String, directionality: String): String {
        val instant = try { java.time.Instant.parse(created) } catch (_: Exception) { java.time.Instant.EPOCH }
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Africa/Nairobi"))
        val formattedDate = formatter.format(instant)
        return "$type • ${if (active) "Active" else "Inactive"} • votes: $votes • $formattedDate\nBearing: $bearingSide, Direction: $directionality"
    }
}
