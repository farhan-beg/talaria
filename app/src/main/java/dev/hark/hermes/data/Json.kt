package dev.hark.hermes.data

import kotlinx.serialization.json.*

val Jsonx = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject
fun JsonElement?.arrOrEmpty(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())

fun JsonObject?.s(key: String): String {
    val v = this?.get(key) ?: return ""
    return if (v is JsonPrimitive) { if (v is JsonNull) "" else v.content } else v.toString()
}
fun JsonObject?.sn(key: String): String? = s(key).ifBlank { null }
fun JsonObject?.d(key: String): Double = (this?.get(key) as? JsonPrimitive)?.doubleOrNull ?: 0.0
fun JsonObject?.l(key: String): Long = (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
fun JsonObject?.b(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull ?: false
fun JsonObject?.o(key: String): JsonObject? = this?.get(key) as? JsonObject
fun JsonObject?.a(key: String): JsonArray = (this?.get(key) as? JsonArray) ?: JsonArray(emptyList())
fun JsonArray.objs(): List<JsonObject> = mapNotNull { it as? JsonObject }
fun JsonArray.strs(): List<String> = mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) put(k, toJson(v))
}

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> buildJsonObject { v.forEach { (k, x) -> put(k.toString(), toJson(x)) } }
    is List<*> -> buildJsonArray { v.forEach { add(toJson(it)) } }
    else -> JsonPrimitive(v.toString())
}

fun humanTokens(n: Long): String = when {
    n >= 1_000_000_000 -> String.format("%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format("%.1fM", n / 1e6)
    n >= 1_000 -> String.format("%.1fK", n / 1e3)
    else -> n.toString()
}

fun humanBytes(n: Long): String = when {
    n >= 1L shl 40 -> String.format("%.1f TB", n / (1L shl 40).toDouble())
    n >= 1L shl 30 -> String.format("%.1f GB", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format("%.0f MB", n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> String.format("%.0f KB", n / (1L shl 10).toDouble())
    else -> "$n B"
}

fun relTime(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    val diff = System.currentTimeMillis() / 1000.0 - epochSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${(diff / 60).toInt()}m ago"
        diff < 86400 -> "${(diff / 3600).toInt()}h ago"
        diff < 86400 * 30 -> "${(diff / 86400).toInt()}d ago"
        else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date((epochSeconds * 1000).toLong()))
    }
}

fun isoRel(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try { relTime(java.time.OffsetDateTime.parse(iso).toEpochSecond().toDouble()) } catch (e: Exception) {
        try { relTime(java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble()) } catch (e2: Exception) { iso }
    }
}

fun isoFuture(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        val t = try { java.time.OffsetDateTime.parse(iso).toEpochSecond() } catch (e: Exception) {
            java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        }
        val diff = t - System.currentTimeMillis() / 1000
        when {
            diff < 0 -> isoRel(iso)
            diff < 3600 -> "in ${diff / 60}m"
            diff < 86400 -> "in ${diff / 3600}h"
            else -> "in ${diff / 86400}d"
        }
    } catch (e: Exception) { iso }
}
