package com.roadwatch.trip

import com.roadwatch.engine.AlertEvent
import com.roadwatch.engine.Fix
import com.roadwatch.engine.TraceEvent

/**
 * Line-oriented JSON ("JSONL") trip log format, one flat object per line:
 *
 *   {"k":"meta", ...}                       app/device/config info, first line
 *   {"k":"fix","t":..,"lat":..,"lng":..,"spd":..,"brg":..,"acc":..}
 *   {"k":"alert","t":..,"id":..,"kind":..,"hazard":"<key>","type":..,"dist":..,"kph":..,"speech":".."}
 *   {"k":"trace","t":..,"ev":"SKIP_WRONG_DIRECTION","hz":"<key>","d":".."}
 *   {"k":"report","t":..,"type":..,"lat":..,"lng":..,"dir":..,"hdg":..,"res":".."}
 *   {"k":"feedback","t":..,"alert":<id>,"v":"WRONG|OK|MISSED|LATE|EARLY","lat":..,"lng":..}
 *
 * Pure Kotlin so the same file can be replayed through [com.roadwatch.engine.AlertEngine] on a laptop.
 */
object TripFormat {
    fun meta(fields: Map<String, Any?>): String = obj(mapOf("k" to "meta") + fields)

    fun fix(f: Fix): String = obj(
        linkedMapOf(
            "k" to "fix", "t" to f.timeMs, "lat" to f.lat, "lng" to f.lng,
            "spd" to f.speedMps, "brg" to f.bearingDeg, "acc" to f.accuracyM, "bacc" to f.bearingAccuracyDeg,
        ),
    )

    fun alert(a: AlertEvent): String = obj(
        linkedMapOf(
            "k" to "alert", "t" to a.timeMs, "id" to a.id, "kind" to a.kind.name,
            "hazard" to a.primary.key, "type" to a.primary.type.name, "n" to a.hazards.size,
            "dist" to a.distanceM, "kph" to a.speedKph, "lead" to a.leadM, "speech" to a.speech,
        ),
    )

    fun trace(e: TraceEvent): String = obj(
        linkedMapOf("k" to "trace", "t" to e.timeMs, "ev" to e.kind, "hz" to e.hazardKey, "d" to e.detail),
    )

    fun custom(kind: String, fields: Map<String, Any?>): String = obj(linkedMapOf<String, Any?>("k" to kind) + fields)

    private fun obj(m: Map<String, Any?>): String = m.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"$k\":" + when (v) {
            null -> "null"
            is Number, is Boolean -> v.toString()
            else -> "\"" + escape(v.toString()) + "\""
        }
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }

    /** Parses one flat JSON object line into a string map ("null" values become null). Malformed/truncated lines yield null. */
    fun parse(line: String): Map<String, String?>? = try {
        parseUnsafe(line)
    } catch (_: Exception) {
        null
    }

    private fun parseUnsafe(line: String): Map<String, String?>? {
        val s = line.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return null
        val out = LinkedHashMap<String, String?>()
        var i = 1
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun readString(): String? {
            if (s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) return null
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }
        while (i < s.length - 1) {
            skipWs()
            if (s[i] == ',') { i++; continue }
            val key = readString() ?: return null
            skipWs()
            if (s[i] != ':') return null
            i++
            skipWs()
            if (s[i] == '"') {
                out[key] = readString() ?: return null
            } else {
                val start = i
                while (i < s.length && s[i] != ',' && s[i] != '}') i++
                val raw = s.substring(start, i).trim()
                out[key] = if (raw == "null") null else raw
            }
        }
        return out
    }
}
