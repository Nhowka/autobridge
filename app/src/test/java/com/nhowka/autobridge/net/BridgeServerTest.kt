package com.nhowka.autobridge.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class BridgeServerTest {
    @Test fun routesAndRemoteAddress() {
        val info = Route { r -> if (r.path == "/api/info") Response(200, JSONObject().put("from", r.remote)) else null }
        BridgeServer(listOf(info), port = 0).use { s ->
            val c = URL("http://127.0.0.1:${s.port}/api/info").openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertEquals("127.0.0.1", JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8)).getString("from"))
            assertEquals(404, (URL("http://127.0.0.1:${s.port}/nope").openConnection() as HttpURLConnection).responseCode)
        }
    }
}
