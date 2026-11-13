package com.example.psbill

import org.json.JSONObject

/**
 * optString() returns the text "null" when the server sends a JSON null, which used to
 * show up on the TV as "null" game names and broken cover images. Use this instead for
 * anything that is displayed or loaded.
 */
fun JSONObject.cleanStr(key: String, fallback: String = ""): String {
    if (!has(key) || isNull(key)) return fallback
    val v = optString(key, fallback).trim()
    return if (v.isEmpty() || v.equals("null", ignoreCase = true)) fallback else v
}
