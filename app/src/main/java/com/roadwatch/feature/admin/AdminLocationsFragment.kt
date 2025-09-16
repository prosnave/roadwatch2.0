package com.roadwatch.feature.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.roadwatch.app.R
import com.roadwatch.data.*
import com.roadwatch.network.ApiClient
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AdminLocationsFragment : Fragment() {
    data class Row(
        val id: String,
        val label: String,
        val active: Boolean,
        val votes: Int,
        val directionality: String,
    )
    private var serverRows: MutableList<Row> = mutableListOf()
    private var serverCursor: String? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_admin_locations, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<ListView>(R.id.list)
        val summaryBody = view.findViewById<TextView>(R.id.txt_summary_body)
        val toggle = view.findViewById<Button>(R.id.btn_toggle_summary)
        val spnType = view.findViewById<Spinner>(R.id.spn_type)
        val spnSource = view.findViewById<Spinner>(R.id.spn_source)
        val chkActiveOnly = view.findViewById<CheckBox>(R.id.chk_active_only)
        val edtSearch = view.findViewById<EditText>(R.id.edt_search)
        val btnApply = view.findViewById<Button>(R.id.btn_apply_filters)
        val btnClear = view.findViewById<Button>(R.id.btn_clear_filters)
        val btnLoadMore = view.findViewById<Button>(R.id.btn_load_more)

        // Setup filter spinners
        try {
            val hazardTypes = arrayOf("All", "SPEED_BUMP", "POTHOLE", "RUMBLE_STRIP", "SPEED_LIMIT_ZONE")
            val sources = arrayOf("All", "SEED", "USER", "ADMIN")
            spnType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, hazardTypes)
            spnSource.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, sources)
        } catch (_: Exception) {}

        fun renderServer() {
            val rows = serverRows
            val total = rows.size
            val activeCount = rows.count { it.active }
            val byType = rows.map { it.label.split("•")[0].trim() }.groupingBy { it }.eachCount()
            val typeLines = byType.entries.sortedBy { it.key }.joinToString("\n") { "- ${it.key}: ${it.value}" }
            summaryBody?.text = "Total: ${total} (Active: ${activeCount})\n${typeLines}"
            list.adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(position: Int) = rows[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val row = rows[position]
                    val container = convertView as? LinearLayout ?: LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val icon = ImageView(context)
                        icon.id = android.R.id.icon
                        val titles = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(TextView(context).apply { id = android.R.id.text1 })
                            addView(TextView(context).apply { id = android.R.id.text2 })
                        }
                        addView(icon, LinearLayout.LayoutParams(72, 72))
                        addView(titles, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        setPadding(16, 16, 16, 16)
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
            btnLoadMore?.isEnabled = serverCursor != null
            list.setOnItemClickListener { _, _, pos, _ ->
                val row = rows[pos]
                val actions = mutableListOf("Add Vote", "Remove Vote", "Toggle Active", "Edit", "Delete")
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Manage Hazard")
                    .setItems(actions.toTypedArray()) { _, which ->
                        val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
                        val adminAccess = com.roadwatch.prefs.AppPrefs.getAdminAccess(requireContext())
                        if (base.isEmpty() || adminAccess.isNullOrEmpty()) {
                            Toast.makeText(requireContext(), "Admin login required", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        when (actions[which]) {
                            "Add Vote", "Remove Vote" -> {
                                Toast.makeText(requireContext(), "Voting not implemented in admin", Toast.LENGTH_SHORT).show()
                            }
                            "Toggle Active" -> {
                                val patch = org.json.JSONObject().put("active", !row.active)
                                val r = com.roadwatch.network.AdminClient.patchHazardWithRefresh(requireContext(), base, row.id, patch)
                                if (r.isSuccess) { serverRows.clear(); serverCursor = null; fetchServerPage(view, list, summaryBody, btnLoadMore) } else Toast.makeText(requireContext(), "Update failed", Toast.LENGTH_SHORT).show()
                            }
                            "Edit" -> {
                                val dialogView = layoutInflater.inflate(R.layout.dialog_edit_hazard, null)
                                val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radio_group_directionality)
                                val spinner = dialogView.findViewById<Spinner>(R.id.spinner_hazard_type)
                                val hazardTypes = com.roadwatch.data.HazardType.values().map { it.name }
                                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, hazardTypes)
                                spinner.adapter = adapter
                                when (row.directionality.uppercase()) {
                                    "ONE_WAY" -> radioGroup.check(R.id.radio_one_way)
                                    "BIDIRECTIONAL" -> radioGroup.check(R.id.radio_two_way)
                                    "OPPOSITE" -> radioGroup.check(R.id.radio_opposite)
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
                                        val newHazardType = try { com.roadwatch.data.HazardType.valueOf(spinner.selectedItem as String).name } catch (_: Exception) { null }
                                        val patch = org.json.JSONObject().put("directionality", newDirectionality)
                                        if (newHazardType != null) patch.put("type", newHazardType)
                                        val r = com.roadwatch.network.AdminClient.patchHazardWithRefresh(requireContext(), base, row.id, patch)
                                        if (r.isSuccess) { serverRows.clear(); serverCursor = null; fetchServerPage(view, list, summaryBody, btnLoadMore) } else Toast.makeText(requireContext(), "Update failed", Toast.LENGTH_SHORT).show()
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            "Delete" -> {
                                val r = com.roadwatch.network.AdminClient.deleteHazardWithRefresh(requireContext(), base, row.id)
                                if (r.isSuccess) { serverRows.clear(); serverCursor = null; fetchServerPage(view, list, summaryBody, btnLoadMore) } else Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
        }

        fun refresh() {
            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val adminAccess = com.roadwatch.prefs.AppPrefs.getAdminAccess(requireContext())
            if (baseUrl.isNotEmpty() && !adminAccess.isNullOrEmpty()) {
                serverRows.clear(); serverCursor = null
                fetchServerPage(view, list, summaryBody, btnLoadMore)
                btnApply?.setOnClickListener { serverRows.clear(); serverCursor = null; fetchServerPage(view, list, summaryBody, btnLoadMore) }
                btnClear?.setOnClickListener {
                    try { spnType.setSelection(0); spnSource.setSelection(0) } catch (_: Exception) {}
                    chkActiveOnly.isChecked = false
                    edtSearch.setText("")
                    serverRows.clear(); serverCursor = null; fetchServerPage(view, list, summaryBody, btnLoadMore)
                }
                btnLoadMore?.setOnClickListener {
                    if (serverCursor != null) { fetchServerPage(view, list, summaryBody, btnLoadMore) }
                }
            } else {
                summaryBody.text = "Admin login required"
                list.adapter = null
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

    private fun fetchServerPage(view: View, list: ListView, summaryBody: TextView, btnLoadMore: Button) {
        val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
        val adminAccess = com.roadwatch.prefs.AppPrefs.getAdminAccess(requireContext())
        if (baseUrl.isEmpty() || adminAccess.isNullOrEmpty()) return

        val spnType = view.findViewById<Spinner>(R.id.spn_type)
        val spnSource = view.findViewById<Spinner>(R.id.spn_source)
        val chkActiveOnly = view.findViewById<CheckBox>(R.id.chk_active_only)
        val edtSearch = view.findViewById<EditText>(R.id.edt_search)

        val res = com.roadwatch.network.AdminClient.listHazardsWithRefresh(
            requireContext(),
            baseUrl,
            100,
            serverCursor,
            type = spnType.selectedItem.toString().takeIf { it != "All" },
            source = spnSource.selectedItem.toString().takeIf { it != "All" },
            active = if (chkActiveOnly.isChecked) true else null,
            search = edtSearch.text.toString().takeIf { it.isNotBlank() }
        )
        if (res.isSuccess) {
            val payload = res.getOrNull()!!
            payload.hazards.forEach { h ->
                serverRows += Row(
                    id = h.id,
                    label = buildLabel(h.type.name, h.active, h.votes_count, h.created_at, h.directionality, h.reported_heading_deg, null),
                    active = h.active,
                    votes = h.votes_count,
                    directionality = h.directionality,
                )
            }
            serverCursor = payload.cursor
            renderServer(list, summaryBody, btnLoadMore)
        } else {
            Toast.makeText(requireContext(), res.exceptionOrNull()?.message ?: "Load failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderServer(list: ListView, summaryBody: TextView, btnLoadMore: Button) {
        val rows = serverRows
        val total = rows.size
        val activeCount = rows.count { it.active }
        val byType = rows.map { it.label.split("•")[0].trim() }.groupingBy { it }.eachCount()
        val typeLines = byType.entries.sortedBy { it.key }.joinToString("\n") { "- ${it.key}: ${it.value}" }
        summaryBody?.text = "Total: ${total} (Active: ${activeCount})\n${typeLines}"
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = rows[position]
                val container = convertView as? LinearLayout ?: LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val icon = ImageView(context)
                    icon.id = android.R.id.icon
                    val titles = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(context).apply { id = android.R.id.text1 })
                        addView(TextView(context).apply { id = android.R.id.text2 })
                    }
                    addView(icon, LinearLayout.LayoutParams(72, 72))
                    addView(titles, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    setPadding(16, 16, 16, 16)
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
        btnLoadMore?.isEnabled = serverCursor != null
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
