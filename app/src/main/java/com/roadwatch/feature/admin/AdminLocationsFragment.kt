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
        // Legacy fields for local mode
        val isUser: Boolean = false,
        val userId: String? = null,
        val seedKey: String? = null,
        val voteKey: String = id,
        val userBearing: Float? = null,
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
        val repo = SeedRepository(requireContext())
        val store = HazardStore(requireContext())

        val isAdmin = com.roadwatch.app.BuildConfig.IS_ADMIN

        fun renderServer() {
            val rows = serverRows
            val total = rows.size
            val activeCount = rows.count { it.active }
            val byType = rows.map { extractType(it.label) }.groupingBy { it }.eachCount()
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
                try {
                    val email = com.roadwatch.prefs.AppPrefs.getAccountEmail(requireContext())
                    val password = com.roadwatch.prefs.AppPrefs.getAccountPassword(requireContext())
                    val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
                    if (!email.isNullOrEmpty() && !password.isNullOrEmpty() && base.isNotEmpty()) {
                        val vs = com.roadwatch.network.ApiClient.voteStatusWithBasic(base, email, password, row.id)
                        if (vs.isSuccess) {
                            val hasVoted = vs.getOrNull()!!.has_voted == true
                            val idxAdd = actions.indexOf("Add Vote")
                            val idxRem = actions.indexOf("Remove Vote")
                            if (hasVoted && idxAdd >= 0) actions[idxAdd] = "Remove Vote"
                            if (!hasVoted && idxRem >= 0) actions[idxRem] = "Add Vote"
                        }
                    }
                } catch (_: Exception) {}
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Manage Hazard")
                    .setItems(actions.toTypedArray()) { _, which ->
                        val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
                        when (actions[which]) {
                            "Add Vote" -> {
                                val email = com.roadwatch.prefs.AppPrefs.getAccountEmail(requireContext())
                                val password = com.roadwatch.prefs.AppPrefs.getAccountPassword(requireContext())
                                if (!email.isNullOrEmpty() && !password.isNullOrEmpty() && base.isNotEmpty()) {
                                    val r = ApiClient.upvoteWithBasic(base, email, password, row.id)
                                    if (r.isSuccess) { serverRows.clear(); serverCursor = null; fetchServerPage(); renderServer() } else toast("Vote failed")
                                } else toast("Save account first")
                            }
                            "Remove Vote" -> {
                                val email = com.roadwatch.prefs.AppPrefs.getAccountEmail(requireContext())
                                val password = com.roadwatch.prefs.AppPrefs.getAccountPassword(requireContext())
                                if (!email.isNullOrEmpty() && !password.isNullOrEmpty() && base.isNotEmpty()) {
                                    val r = ApiClient.unvoteWithBasic(base, email, password, row.id)
                                    if (r.isSuccess) { serverRows.clear(); serverCursor = null; fetchServerPage(); renderServer() } else toast("Unvote failed")
                                } else toast("Save account first")
                            }
                            "Toggle Active" -> {
                                val patch = org.json.JSONObject().put("active", !row.active)
                                val r = com.roadwatch.network.AdminClient.patchHazardWithRefresh(requireContext(), base, row.id, patch)
                                if (r.isSuccess) { serverRows.clear(); serverCursor = null; fetchServerPage(); renderServer() } else toast("Update failed")
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
                                        if (r.isSuccess) { serverRows.clear(); serverCursor = null; fetchServerPage(); renderServer() } else toast("Update failed")
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            "Delete" -> {
                                val r = com.roadwatch.network.AdminClient.deleteHazardWithRefresh(requireContext(), base, row.id)
                                if (r.isSuccess) { serverRows.clear(); serverCursor = null; fetchServerPage(); renderServer() } else toast("Delete failed")
                            }
                        }
                    }
                    .show()
            }
        }

        fun fetchServerPage() {
            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val res = com.roadwatch.network.AdminClient.listHazardsWithRefresh(
                requireContext(),
                baseUrl,
                100,
                serverCursor,
                type = spnType.selectedItem.toString().takeIf { it != "All" },
                source = spnSource.selectedItem.toString().takeIf { it != "All" },
                active = if (chkActiveOnly.isChecked) true else null,
                search = edtSearch.text.toString().takeIf { it.isNotBlank() },
                minLat = view.findViewById<EditText>(R.id.edt_min_lat)?.text?.toString()?.toDoubleOrNull(),
                minLng = view.findViewById<EditText>(R.id.edt_min_lng)?.text?.toString()?.toDoubleOrNull(),
                maxLat = view.findViewById<EditText>(R.id.edt_max_lat)?.text?.toString()?.toDoubleOrNull(),
                maxLng = view.findViewById<EditText>(R.id.edt_max_lng)?.text?.toString()?.toDoubleOrNull(),
                createdAfter = view.findViewById<EditText>(R.id.edt_created_after)?.text?.toString()?.takeIf { it.isNotBlank() },
                createdBefore = view.findViewById<EditText>(R.id.edt_created_before)?.text?.toString()?.takeIf { it.isNotBlank() }
            )
            if (res.isSuccess) {
                val payload = res.getOrNull()!!
                payload.hazards.forEach { h ->
                    serverRows += Row(
                        id = h.id,
                        label = buildLabel(h.type.name, h.active, h.votes_count, h.created_at, h.directionality),
                        active = h.active,
                        votes = h.votes_count,
                        directionality = h.directionality,
                    )
                }
                serverCursor = payload.cursor
            } else {
                toast(res.exceptionOrNull()?.message ?: "Load failed")
            }
        }

        fun refresh() {
            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val adminAccess = com.roadwatch.prefs.AppPrefs.getAdminAccess(requireContext())
            val isAdmin = com.roadwatch.app.BuildConfig.IS_ADMIN
            if (isAdmin && baseUrl.isNotEmpty() && !adminAccess.isNullOrEmpty()) {
                serverRows.clear(); serverCursor = null
                fetchServerPage()
                renderServer()
                btnApply?.setOnClickListener { serverRows.clear(); serverCursor = null; fetchServerPage(); renderServer() }
                btnClear?.setOnClickListener {
                    try { spnType.setSelection(0); spnSource.setSelection(0) } catch (_: Exception) {}
                    chkActiveOnly.isChecked = false
                    edtSearch.setText("")
                    serverRows.clear(); serverCursor = null; fetchServerPage(); renderServer()
                }
                btnLoadMore?.setOnClickListener {
                    if (serverCursor != null) { fetchServerPage(); renderServer() }
                }
                return
            }
            val seedHazards = repo.loadSeeds().second
            val userHazards = store.list()
            val rows = mutableListOf<Row>()

            val baseUrl = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val adminAccess = com.roadwatch.prefs.AppPrefs.getAdminAccess(requireContext())
            if (isAdmin && baseUrl.isNotEmpty() && !adminAccess.isNullOrEmpty()) {
                val res = com.roadwatch.network.ApiClient.adminListHazards(baseUrl, adminAccess, 200, null)
                if (res.isSuccess) {
                    val items = res.getOrNull()!!.hazards
                    items.forEach { h ->
                        rows += Row(
                            id = h.id,
                            label = buildLabel(h.type.name, h.active, h.votes_count, h.created_at, h.directionality, h.reported_heading_deg, null),
                            active = h.active,
                            votes = h.votes_count,
                            directionality = h.directionality,
                        )
                    }
                }
            }
            if (rows.isEmpty()) {
            seedHazards.forEach { h ->
                val key = SeedOverrides.keyOf(h)
                // Hide disabled seeds entirely so the list reflects what is actually active/visible
                if (SeedOverrides.isDisabled(requireContext(), key)) return@forEach
                val active = h.active
                val votes = CommunityVotes.getVotes(requireContext(), key)
                val createdAt = try { h.createdAt.toString() } catch (_: Exception) { "" }
                rows += Row(
                    id = key,
                    label = buildLabel(h.type.name, active, votes, createdAt, h.directionality, h.reportedHeadingDeg, h.userBearing),
                    active = active,
                    votes = votes,
                    directionality = h.directionality,
                    isUser = false,
                    userId = null,
                    seedKey = key,
                    voteKey = key,
                    userBearing = h.userBearing,
                )
            }
            userHazards.forEach { u ->
                val voteKey = SeedOverrides.keyOf(u.hazard)
                val votes = CommunityVotes.getVotes(requireContext(), voteKey)
                val createdAt = try { u.hazard.createdAt.toString() } catch (_: Exception) { "" }
                rows += Row(
                    id = voteKey,
                    label = buildLabel(u.hazard.type.name, u.hazard.active, votes, createdAt, u.hazard.directionality, u.hazard.reportedHeadingDeg, u.hazard.userBearing),
                    active = u.hazard.active,
                    votes = votes,
                    directionality = u.hazard.directionality,
                    isUser = true,
                    userId = u.id,
                    seedKey = null,
                    voteKey = voteKey,
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
                            "Add Vote" -> {
                                // Try server vote if configured
                                if (!tryServerVote(seedHazards, userHazards, row.isUser, row.userId, row.seedKey, add = true)) {
                                    CommunityVotes.upvote(requireContext(), key)
                                }
                                refresh()
                            }
                            "Remove Vote" -> {
                                if (!tryServerVote(seedHazards, userHazards, row.isUser, row.userId, row.seedKey, add = false)) {
                                    CommunityVotes.downvote(requireContext(), key)
                                }
                                refresh()
                            }
                            "Toggle Active" -> {
                                val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
                                val access = com.roadwatch.prefs.AppPrefs.getAdminAccess(requireContext())
                                if (!isAdmin || row.voteKey.isBlank() || base.isEmpty() || access.isNullOrEmpty()) { toast("Admin login required"); return@setItems }
                                val patch = org.json.JSONObject().put("active", !row.active)
                                val r = com.roadwatch.network.AdminClient.patchHazardWithRefresh(requireContext(), base, row.voteKey, patch)
                                if (r.isSuccess) refresh() else toast("Update failed")
                            }
                            "Delete" -> {
                                val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
                                val access = com.roadwatch.prefs.AppPrefs.getAdminAccess(requireContext())
                                if (!isAdmin || row.voteKey.isBlank() || base.isEmpty() || access.isNullOrEmpty()) { toast("Admin login required"); return@setItems }
                                val r = com.roadwatch.network.AdminClient.deleteHazardWithRefresh(requireContext(), base, row.voteKey)
                                if (r.isSuccess) refresh() else toast("Delete failed")
                            }
                            "Edit" -> {
                                val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
                                val access = com.roadwatch.prefs.AppPrefs.getAdminAccess(requireContext())
                                if (row.isUser || row.voteKey.isBlank() || base.isEmpty() || access.isNullOrEmpty()) {
                                    toast("Admin login required for editing")
                                    return@setItems
                                }
                                val dialogView = layoutInflater.inflate(R.layout.dialog_edit_hazard, null)
                                val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radio_group_directionality)
                                val spinner = dialogView.findViewById<Spinner>(R.id.spinner_hazard_type)

                                val hazardTypes = HazardType.values().map { it.name }
                                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, hazardTypes)
                                spinner.adapter = adapter
                                // Preselect unknown defaults
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
                                        val newHazardType = try { HazardType.valueOf(spinner.selectedItem as String).name } catch (_: Exception) { null }
                                        val patch = org.json.JSONObject()
                                        patch.put("directionality", newDirectionality)
                                        if (newHazardType != null) patch.put("type", newHazardType)
                                        val r = com.roadwatch.network.AdminClient.patchHazardWithRefresh(requireContext(), base, row.voteKey, patch)
                                        if (r.isSuccess) refresh() else toast("Update failed")
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

    private fun tryServerVote(
        seedHazards: List<com.roadwatch.data.Hazard>,
        userHazards: List<com.roadwatch.data.UserHazard>,
        isUser: Boolean,
        userId: String?,
        seedKey: String?,
        add: Boolean
    ): Boolean {
        return try {
            val latLngType = if (isUser) {
                val u = userHazards.find { it.id == userId } ?: return false
                Triple(u.hazard.lat, u.hazard.lng, u.hazard.type.name)
            } else {
                val h = seedHazards.find { com.roadwatch.data.SeedOverrides.keyOf(it) == seedKey } ?: return false
                Triple(h.lat, h.lng, h.type.name)
            }
            val base = com.roadwatch.prefs.AppPrefs.getBaseUrl(requireContext()).trim()
            val token = com.roadwatch.prefs.AppPrefs.getDeviceToken(requireContext())
            if (base.isEmpty() || token.isNullOrEmpty()) return false
            val res = com.roadwatch.network.ApiClient.listHazards(base, latLngType.first, latLngType.second, 50, 50, null, token)
            if (!res.isSuccess) return false
            val candidates = res.getOrNull()!!.hazards
            val match = candidates.firstOrNull { it.type.name == latLngType.third && distanceMeters(it.lat, it.lng, latLngType.first, latLngType.second) <= 30.0 }
            if (match == null) return false
            val voteRes = if (add) com.roadwatch.network.ApiClient.upvote(base, token, match.id) else com.roadwatch.network.ApiClient.unvote(base, token, match.id)
            voteRes.isSuccess
        } catch (_: Throwable) { false }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
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
