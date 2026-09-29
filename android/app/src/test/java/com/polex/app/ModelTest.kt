package com.polex.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the server status/config model (Model.kt), covering
 * the SSH power-off fields (sshUser/sshKeyPath/sshPassword), the
 * sshConfigured predicate, and lastShutdownAt. No device/emulator needed.
 */
class ModelTest {

    private fun statusJson(
        sshUser: String = "",
        sshKeyPath: String = "",
        sshPassword: String = "",
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
            "sshPassword": "$sshPassword",
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
            statusJson(
                sshUser = "root",
                sshKeyPath = "/app/keys/polex",
                sshPassword = "s3cret",
                lastShutdownAt = "2026-09-09T02:00:00Z",
            ),
        )
        assertEquals(1, items.size)
        val s = items[0]
        assertEquals("root", s.sshUser)
        assertEquals("/app/keys/polex", s.sshKeyPath)
        assertEquals("s3cret", s.sshPassword)
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
        assertEquals("", items[0].sshPassword)
        assertEquals("", items[0].lastShutdownAt)
        assertFalse(items[0].sshConfigured())
    }

    @Test
    fun parseServerArrayReadsSshFields() {
        val body = """
            [
              {
                "id": 2, "name": "WS", "ip": "192.168.1.11", "mac": "",
                "port": 22, "check": "tcp", "sshUser": "admin", "sshKeyPath": "~/keys/polex", "sshPassword": "pw"
              }
            ]
        """.trimIndent()
        val items = parseServerArray(body)
        assertEquals(1, items.size)
        assertEquals("admin", items[0].sshUser)
        assertEquals("~/keys/polex", items[0].sshKeyPath)
        assertEquals("pw", items[0].sshPassword)

        // Missing fields (legacy backend) → empty, not a crash.
        val legacy = parseServerArray("""[{"id":3,"name":"x","ip":"1.2.3.4","mac":"","port":22,"check":"tcp"}]""")
        assertEquals("", legacy[0].sshUser)
        assertEquals("", legacy[0].sshKeyPath)
        assertEquals("", legacy[0].sshPassword)
    }

    @Test
    fun serverToJsonCarriesSshFields() {
        val json = serverToJson("NAS", "192.168.1.10", "AA:BB:CC:DD:EE:01", 22, "tcp", "root", "/keys/polex", "s3cret")
        val o = JSONObject(json)
        assertEquals("root", o.getString("sshUser"))
        assertEquals("/keys/polex", o.getString("sshKeyPath"))
        assertEquals("s3cret", o.getString("sshPassword"))
        assertEquals("NAS", o.getString("name"))
    }

    @Test
    fun serverToJsonDefaultsSshFieldsToEmpty() {
        // A server without SSH power-off must serialize empty (not absent,
        // not null) so the backend validation sees all credentials empty.
        val json = serverToJson("NAS", "192.168.1.10", "", 22, "ping")
        val o = JSONObject(json)
        assertEquals("", o.getString("sshUser"))
        assertEquals("", o.getString("sshKeyPath"))
        assertEquals("", o.getString("sshPassword"))
    }

    @Test
    fun sshConfiguredGatesOnUserPlusAnyCredential() {
        // Mirrors the backend predicate: the user plus at least one of
        // key path / password must be set.
        val base = SrvStatus(
            id = 1, name = "NAS", ip = "192.168.1.10", mac = "", port = 22,
            check = "tcp", sshUser = "", sshKeyPath = "", sshPassword = "",
            online = true, latencyMs = 0, lastChecked = "",
            lastWolAt = "", lastShutdownAt = "",
        )
        assertFalse(base.sshConfigured())
        assertFalse(base.copy(sshUser = "root").sshConfigured())
        assertFalse(base.copy(sshKeyPath = "/keys/polex").sshConfigured())
        assertFalse(base.copy(sshPassword = "secret").sshConfigured())
        assertFalse(base.copy(sshKeyPath = "/keys/polex", sshPassword = "secret").sshConfigured())
        assertTrue(base.copy(sshUser = "root", sshKeyPath = "/keys/polex").sshConfigured())
        assertTrue(base.copy(sshUser = "root", sshPassword = "secret").sshConfigured())
        assertTrue(base.copy(sshUser = "root", sshKeyPath = "/keys/polex", sshPassword = "secret").sshConfigured())
    }
}
