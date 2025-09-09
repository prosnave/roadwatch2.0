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
        data class Row(
            val label: String,
            val isUser: Boolean,
            val userId: String?,
            val seedKey: String?,
            val voteKey: String,
            val active: Boolean,
            val votes: Int,
            val directionality: String,
            val userBearing: Float?,
        )

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
                rows += Row(
                    label = buildLabel(h.type.name, active, votes, createdAt, h.directionality, h.reportedHeadingDeg, h.userBearing),
                    isUser = false,
                    userId = null,
                    seedKey = key,
                    voteKey = key,
                    active = active,
                    votes = votes,
                    directionality = h.directionality,
                    userBearing = h.userBearing,
                )
            }
            userHazards.forEach { u ->
                val voteKey = SeedOverrides.keyOf(u.hazard)
                val votes = CommunityVotes.getVotes(requireContext(), voteKey)
                val createdAt = try { u.hazard.createdAt.toString() } catch (_: Exception) { "" }
                rows += Row(
                    label = buildLabel(u.hazard.type.name, u.hazard.active, votes, createdAt, u.hazard.directionality, u.hazard.reportedHeadingDeg, u.hazard.userBearing),
                    isUser = true,
                    userId = u.id,
                    seedKey = null,
                    voteKey = voteKey,
                    active = u.hazard.active,
                    votes = votes,
                    directionality = u.hazard.directionality,
                    userBearing = u.hazard.userBearing,
                )
            }
            // Summary and list rendering
            val total = rows.size
            if (total == 0) {
                summaryBody?.text = "No hazards"
                list.adapter = object : BaseAdapter() {
                    override fun getCount() = 1
                    override fun getItem(position: Int) = "No hazards"
                    override fun getItemId(position: Int) = 0L
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                        val tv = (convertView as? TextView) ?: TextView(requireContext())
                        tv.text = "No hazards"
                        tv.setPadding(24, 24, 24, 24)
                        return tv
                    }
                }
                list.setOnItemClickListener(null)
                return
            }

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
                        setPadding(20, 14, 20, 14)
                        val iconSize = (ctx.resources.displayMetrics.density * 20).toInt()
                        addView(ImageView(ctx).apply {
                            id = android.R.id.icon
                            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { setMargins(0, 6, 16, 0) }
                        })
                        val textWrap = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        textWrap.addView(TextView(ctx).apply { id = android.R.id.text1 })
                        textWrap.addView(TextView(ctx).apply { id = android.R.id.text2 })
                        addView(textWrap)
                    }
                    val icon = container.findViewById<ImageView>(android.R.id.icon)
                    icon.setImageResource(R.drawable.ic_hazard)
                    icon.setColorFilter(if (row.active) android.graphics.Color.parseColor("#1DB954") else android.graphics.Color.parseColor("#E63946"))

                    val parts = row.label.split('\n')
                    val title = parts.getOrNull(0) ?: row.label
                    val subtitle = parts.getOrNull(1) ?: ""
                    container.findViewById<TextView>(android.R.id.text1).apply {
                        text = title
                        textSize = 16f
                        setTextColor(android.graphics.Color.parseColor("#212121"))
                    }
                    container.findViewById<TextView>(android.R.id.text2).apply {
                        text = subtitle
                        textSize = 13f
                        setTextColor(android.graphics.Color.parseColor("#666666"))
                    }
                    return container
                }
            }
            list.setOnItemClickListener { _, _, pos, _ ->
                val row = rows[pos]
                val key = row.voteKey
                val voted = CommunityVotes.hasVoted(requireContext(), key)
                val voteAction = if (voted) "Remove Vote" else "Add Vote"
                val actions = when {
                    isAdmin && row.isUser -> arrayOf(voteAction, "Toggle Active", "Delete", "Edit")
                    isAdmin && !row.isUser -> arrayOf(voteAction, "Toggle Active", "Edit")
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
                            "Edit" -> {
                                val dialogView = layoutInflater.inflate(R.layout.dialog_edit_hazard, null)
                                val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radio_group_directionality)
                                val spinner = dialogView.findViewById<Spinner>(R.id.spinner_hazard_type)

                                val hazardTypes = HazardType.values().filter { it != HazardType.SPEED_LIMIT_ZONE }.map { it.name }
                                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, hazardTypes)
                                spinner.adapter = adapter

                                val currentHazard = if (row.isUser) {
                                    store.list().find { it.id == row.userId }?.hazard
                                } else {
                                    repo.loadSeeds().second.find { SeedOverrides.keyOf(it) == row.seedKey }
                                }

                                currentHazard?.let {
                                    spinner.setSelection(hazardTypes.indexOf(it.type.name))
                                    when (it.directionality) {
                                        "ONE_WAY" -> radioGroup.check(R.id.radio_one_way)
                                        "BIDIRECTIONAL" -> radioGroup.check(R.id.radio_two_way)
                                        "OPPOSITE" -> radioGroup.check(R.id.radio_opposite)
                                    }
                                }

                                android.app.AlertDialog.Builder(requireContext())
                                    .setView(dialogView)
                                    .setPositiveButton("Save") { _, _ ->
                                        val newDirectionality = when (radioGroup.checkedRadioButtonId) {
                                            R.id.radio_one_way -> "ONE_WAY"
                                            R.id.radio_two_way -> "BIDIRECTIONAL"
                                            R.id.radio_opposite -> "OPPOSITE"
                                            else -> row.directionality
                                        }
                                        val newHazardType = HazardType.valueOf(spinner.selectedItem as String)

                                        if (row.isUser) {
                                            val userHazard = store.list().find { it.id == row.userId }
                                            userHazard?.let {
                                                val updatedHazard = it.hazard.copy(
                                                    directionality = newDirectionality,
                                                    type = newHazardType
                                                )
                                                store.upsertByKey(SeedOverrides.keyOf(updatedHazard), updatedHazard)
                                            }
                                        } else {
                                            android.util.Log.d("AdminLocationsFragment", "Editing seed hazards is not supported yet.")
                                        }
                                        refresh()
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
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

    private fun buildLabel(type: String, active: Boolean, votes: Int, created: String, directionality: String, heading: Float?, userBearing: Float?): String {
        val instant = try { java.time.Instant.parse(created) } catch (_: Exception) { java.time.Instant.EPOCH }
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val formattedDate = formatter.format(instant)
        val niceType = type.lowercase().replace('_',' ').replaceFirstChar { it.uppercase() }
        val status = if (active) "Active" else "Inactive"
        val niceDir = when (directionality.uppercase()) {
            "ONE_WAY" -> "One-way"
            "BIDIRECTIONAL" -> "Two-way"
            "OPPOSITE" -> "Opposite"
            else -> "Unknown"
        }
        val hdg = heading?.let { "${it.toInt()}°" } ?: "—"
        val userBrg = userBearing?.let { "${it.toInt()}°" } ?: "—"
        // Show directionality prominently alongside votes and status
        val top = "$niceType • $status • votes: $votes • $niceDir"
        // Keep date and bearings in the subtitle for detail
        val bottom = "$formattedDate • User Bearing: $userBrg • Heading: $hdg"
        return "$top\n$bottom"
    }
}
