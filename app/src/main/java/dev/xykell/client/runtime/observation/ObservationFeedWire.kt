package dev.xykell.client.runtime.observation

import org.json.JSONObject

/**
 * App->game feed wire encoder (Phase F). Maps a translated observation to
 * (typeKey, jsonLine) for ObservationFeedServer; Unknown frames carry no
 * payload (null). Field vocabulary is the CONTRACT with the native feed
 * client (native/src/xykell_feed_client.cpp applyLine) — both sides are
 * unit-tested against the same names: t,id,at,x,y,z,yaw,m,method /
 * t,id,at,sender,msg / t,id,at[,health][,ticks]. Pure JVM.
 */
object ObservationFeedWire {

    fun encode(item: Translated): Pair<String, String>? {
        val o = JSONObject()
        return when (item) {
            is ChatMessage -> {
                o.put("t", "chat")
                o.put("id", item.eventId)
                o.put("at", item.observedAtMs)
                o.put("sender", item.sender)
                o.put("msg", item.message)
                "chat" to o.toString()
            }
            is Travelled -> {
                o.put("t", "travel")
                o.put("id", item.eventId)
                o.put("at", item.observedAtMs)
                o.put("x", item.x)
                o.put("y", item.y)
                o.put("z", item.z)
                o.put("yaw", item.yawDegrees)
                o.put("m", item.metersTravelled)
                o.put("method", item.travelMethod)
                "travel" to o.toString()
            }
            is Vitals -> {
                // Only the fields this packet actually carried are written, and
                // the native client treats an absent key as "not observed" —
                // the same rule the JNI -1 sentinel follows, expressed in the
                // feed's JSON vocabulary.
                o.put("t", "vitals")
                o.put("id", item.eventId)
                o.put("at", item.observedAtMs)
                item.health?.let { o.put("health", it) }
                item.timeTicks?.let { o.put("ticks", it) }
                "vitals" to o.toString()
            }
            is Population -> {
                o.put("t", "population")
                o.put("id", item.eventId)
                o.put("at", item.observedAtMs)
                o.put("entities", item.entityCount)
                o.put("players", item.playerCount)
                "population" to o.toString()
            }
            is UnknownFrame -> null
        }
    }
}
