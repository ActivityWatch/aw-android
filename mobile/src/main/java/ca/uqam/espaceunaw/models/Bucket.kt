package ca.uqam.espaceunaw.models

import org.json.JSONObject

data class Bucket(val id: String, val type: String, val hostname: String, val client: String) {
    override fun toString(): String {
        val bucket = JSONObject()
        bucket.put("id", id)
        bucket.put("type", type)
        bucket.put("hostname", hostname)
        bucket.put("client", client)
        return bucket.toString()
    }
}