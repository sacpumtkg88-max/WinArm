package com.example.winarm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

data class Vm(
    val id: String, var name: String, var cpu: Int = 4, var ramMb: Int = 6144, var diskGb: Int = 128,
    var iso: String? = null, var disk: String? = null, var net: Boolean = false
)

object Store {
    private fun p(c: Context) = c.getSharedPreferences("winarm", Context.MODE_PRIVATE)
    fun load(c: Context): MutableList<Vm> {
        val a = JSONArray(p(c).getString("vms", "[]"))
        return MutableList(a.length()) {
            val o = a.getJSONObject(it)
            Vm(o.getString("id"), o.getString("name"), o.getInt("cpu"), o.getInt("ram"), o.getInt("disk"),
                o.optString("iso").ifEmpty { null }, o.optString("uri").ifEmpty { null }, o.optBoolean("net"))
        }
    }
    fun save(c: Context, l: List<Vm>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("cpu", it.cpu).put("ram", it.ramMb)
                .put("disk", it.diskGb).put("iso", it.iso ?: "").put("uri", it.disk ?: "").put("net", it.net))
        }
        p(c).edit().putString("vms", a.toString()).apply()
    }
}

fun nameOf(c: Context, u: Uri): String = try {
    c.contentResolver.query(u, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    } ?: u.lastPathSegment ?: "?"
} catch (e: Exception) { "?" }
