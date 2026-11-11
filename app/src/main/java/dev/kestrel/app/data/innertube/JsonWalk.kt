package dev.kestrel.app.data.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Helpers to dig through InnerTube's deeply nested JSON without fixed paths. */

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
fun JsonElement?.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()

/** Follows a dotted path. Numeric parts index arrays. */
fun JsonElement?.at(path: String): JsonElement? {
    var cur: JsonElement? = this
    for (part in path.split('.')) {
        cur = when (cur) {
            is JsonObject -> cur[part]
            is JsonArray -> part.toIntOrNull()?.let { cur.getOrNull(it) }
            else -> null
        }
        if (cur == null) return null
    }
    return cur
}

fun JsonElement?.atStr(path: String): String? = (at(path) as? JsonPrimitive)?.contentOrNull

/** All objects found under [key] anywhere in the tree (depth first, document order). */
fun JsonElement?.findAll(key: String, out: MutableList<JsonObject> = mutableListOf()): List<JsonObject> {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key && v is JsonObject) out.add(v)
            v.findAll(key, out)
        }
        is JsonArray -> forEach { it.findAll(key, out) }
        else -> {}
    }
    return out
}

fun JsonElement?.findFirst(key: String): JsonElement? {
    when (this) {
        is JsonObject -> {
            this[key]?.let { return it }
            for (v in values) v.findFirst(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirst(key)?.let { return it }
        else -> {}
    }
    return null
}

fun JsonElement?.findFirstStr(key: String): String? = (findFirst(key) as? JsonPrimitive)?.contentOrNull

/** Objects anywhere in the tree that satisfy [pred]. */
fun JsonElement?.findWhere(pred: (JsonObject) -> Boolean): List<JsonObject> {
    val out = mutableListOf<JsonObject>()
    collectWhere(this, pred, out)
    return out
}

private fun collectWhere(e: JsonElement?, pred: (JsonObject) -> Boolean, out: MutableList<JsonObject>) {
    when (e) {
        is JsonObject -> {
            if (pred(e)) out.add(e)
            e.values.forEach { collectWhere(it, pred, out) }
        }
        is JsonArray -> e.forEach { collectWhere(it, pred, out) }
        else -> {}
    }
}

/** Reads InnerTube text in any of its shapes: simpleText, runs, content. */
fun JsonElement?.text(): String? {
    if (this == null) return null
    if (this is JsonPrimitive) return contentOrNull
    str("simpleText")?.let { return it }
    str("content")?.let { return it }
    arr("runs")?.let { runs -> return runs.joinToString("") { it.str("text").orEmpty() } }
    return null
}

/** Picks the largest thumbnail URL from a thumbnails / sources array holder. */
fun JsonElement?.thumb(): String? {
    val list = arr("thumbnails") ?: arr("sources") ?: (this as? JsonArray)
    val url = list?.mapNotNull { it as? JsonObject }?.maxByOrNull {
        (it.str("width")?.toIntOrNull() ?: 0)
    }?.str("url")
    return url?.let { if (it.startsWith("//")) "https:$it" else it }
}
