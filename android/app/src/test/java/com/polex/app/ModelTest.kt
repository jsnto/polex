package com.polex.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the server status/config model (Model.kt), covering
 * the SSH shutdown fields (sshUser/sshKeyPath) and lastShutdownAt.
 * No device/emulator needed.
 */
class ModelTest {

    private fun statusJson(
        sshUser: String = "",
        sshKeyPath: String = "",
        lastShutdownAt: String = "",
    ): String = """
        [
          {
            "id": 1,
            "name": "NAS",
            "ip": "192.168.1.10",
            "mac": "AA:BB:CC:DD:EE:01",
            "port": 22,
            "check": "tcp",
            "sshUser": "$sshUser",
            "sshKeyPath": "$sshKeyPath",
            "online": true,
            "latencyMs": 3,
            "lastChecked": "2026-09-09T01:27:07Z",
            "lastWolAt": "",
            "lastShutdownAt": "$lastShutdownAt"
          }
        ]
    """.trimIndent()

    @Test
    fun parseStatusArrayReadsSshAndShutdownFields() {
        val items = parseStatusArray(
            statusJson(sshUser = "root", sshKeyPath = "/app/keys/polex", lastShutdownAt = "2026-09-09T02:00:00Z"),
        )
        assertEquals(1, items.size)
        val s = items[0]
        assertEquals("root", s.sshUser)
        assertEquals("/app/keys/polex", s.sshKeyPath)
        assertEquals("2026-09-09T02:00:00Z", s.lastShutdownAt)
        assertTrue(s.sshConfigured())
    }

    @Test
    fun parseStatusArrayToleratesMissingNewFields() {
        // A response from a backend that predates the SSH shutdown fields:
        // the new fields must parse as empty strings, not crash.
        val oldBackendJson = """
            [
              {
                "id": 1, "name": "NAS", "ip": "192.168.1.10", "mac": "",
                "port": 22, "check": "tcp", "online": false, "latencyMs": 0,
                "lastChecked": "never", "lastWolAt": ""
              }
            ]
        """.trimIndent()
        val items = parseStatusArray(oldBackendJson)
        assertEquals(1, items.size)
        assertEquals("", items[0].sshUser)
        assertEquals("", items[0].sshKeyPath)
        assertEquals("", items[0].lastShutdownAt)
        assertFalse(items[0].sshConfigured())
    }

    @Test
    fun parseServerArrayReadsSshFields() {
        val body = """
            [
              {
                "id": 2, "name": "WS", "ip": "192.168.1.11", "mac": "",
                "port": 22, "check": "tcp", "sshUser": "admin", "sshKeyPath": "~/keys/polex"
              }
            ]
        """.trimIndent()
        val items = parseServerArray(body)
        assertEquals(1, items.size)
        assertEquals("admin", items[0].sshUser)
        assertEquals("~/keys/polex", items[0].sshKeyPath)

        // Missing fields (legacy backend) → empty, not a crash.
        val legacy = parseServerArray("""[{"id":3,"name":"x","ip":"1.2.3.4","mac":"","port":22,"check":"tcp"}]""")
        assertEquals("", legacy[0].sshUser)
        assertEquals("", legacy[0].sshKeyPath)
    }

    @Test
    fun serverToJsonCarriesSshFields() {
        val json = serverToJson("NAS", "192.168.1.10", "AA:BB:CC:DD:EE:01", 22, "tcp", "root", "/keys/polex")
        val o = JSONObject(json)
        assertEquals("root", o.getString("sshUser"))
        assertEquals("/keys/polex", o.getString("sshKeyPath"))
        assertEquals("NAS", o.getString("name"))
    }

    @Test
    fun serverToJsonDefaultsSshFieldsToEmpty() {
        // A server without SSH shutdown must serialize empty (not absent,
        // not null) so the backend's pair validation sees both-empty.
        val json = serverToJson("NAS", "192.168.1.10", "", 22, "ping")
        val o = JSONObject(json)
        assertEquals("", o.getString("sshUser"))
        assertEquals("", o.getString("sshKeyPath"))
    }

    @Test
    fun sshConfiguredGatesOnBothFields() {
        // Mirrors the backend predicate: both fields must be non-empty.
        val base = SrvStatus(
            id = 1, name = "NAS", ip = "192.168.1.10", mac = "", port = 22,
            check = "tcp", sshUser = "", sshKeyPath = "", online = true,
            latencyMs = 0, lastChecked = "", lastWolAt = "", lastShutdownAt = "",
        )
        assertFalse(base.sshConfigured())
        assertFalse(base.copy(sshUser = "root").sshConfigured())
        assertFalse(base.copy(sshKeyPath = "/keys/polex").sshConfigured())
        assertTrue(base.copy(sshUser = "root", sshKeyPath = "/keys/polex").sshConfigured())
    }
}
