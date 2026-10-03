package com.filamentvision.backend.api

import com.filamentvision.backend.application.*
import com.filamentvision.backend.input.diagnostics.UdpDiagnosticsSnapshot
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.*
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.*
import org.junit.Test

class UdpDiagnosticsApiTest {
    @Test fun everyOperationalStatusAndEveryCounterHasOneStableNeutralField() = testApplication {
        var status = UdpOperationalStatus.DISABLED
        val backend = object : BackendApplication by FakeBackend() {
            override suspend fun udpDiagnostics(deviceId: String) = UdpDiagnosticsView(
                UdpOperationalStatusSnapshot(status, if (status == UdpOperationalStatus.BIND_FAILED) "UDP_BIND_FAILED" else null),
                UdpDiagnosticsSnapshot(TransportMode.STRUCTURED_CHUNKED, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                    13, 14, 15, 16, 17, 18, 19, 20, "192.168.1.7", 5050, 21, 22, 23))
        }
        application { installRestApi(backend) }
        for (value in UdpOperationalStatus.entries) {
            status = value
            val response = client.get("/api/v1/devices/device1/udp-diagnostics")
            assertEquals(HttpStatusCode.OK, response.status)
            val data = parse(response.bodyAsText())["data"]
            assertEquals(value.name, data["status"].asText())
            if (value == UdpOperationalStatus.BIND_FAILED) assertEquals("UDP_BIND_FAILED", data["statusDetail"].asText())
            val fields = listOf("datagramsReceived", "bytesReceived", "framesStarted", "framesCompleted", "framesDropped",
                "duplicateChunks", "outOfOrderChunks", "missingChunks", "lateChunks", "reassemblyTimeouts", "invalidTransportHeaders",
                "oversizedFrames", "invalidHeaderCount", "invalidFooterCount", "invalidPayloadSizeCount", "decodeFailures",
                "sourceFilteredDatagrams", "oversizedOrTruncatedDatagrams", "lastPacketMonotonicNanos", "lastValidFrameMonotonicNanos",
                "lastPacketEpochMillis", "preAdmissionDroppedInputs", "processingBackpressureDrops")
            fields.forEachIndexed { index, field -> assertEquals(field, index + 1L, data[field].asLong()) }
            assertEquals("192.168.1.7", data["lastPacketSourceIpv4"].asText())
            assertEquals(5050, data["lastPacketSourcePort"].asInt())
            assertEquals((fields + listOf("status", "statusDetail", "lastPacketSourceIpv4", "lastPacketSourcePort")).toSet(), data.fieldNames().asSequence().toSet())
        }
    }

    @Test fun realSourceActivityDrivesStatusAndPacketMetadata() = testApplication {
        val clock = AtomicLong(0)
        val c = openApiComposition { clock.get() }; application { installRestApi(c.application) }
        val port = DatagramSocket(0).use { it.localPort }
        val source = c.inputSource as RaspberryPiUdpInputSource
        suspend fun data() = parse(client.get("/api/v1/devices/${c.deviceId}/udp-diagnostics").bodyAsText())["data"]
        try {
            val enabled = RaspberryPiUdpInputProfile.defaults().copy(enabled = true, listenPort = port)
            c.application.saveUdpInput(c.deviceId, "enable", 1, enabled)
            c.application.applyUdpInput(c.deviceId, "apply", 2, 2)
            c.application.start(c.deviceId, "start", null, null)
            assertEquals("UDP_READY", data()["status"].asText())
            clock.set(2000)
            assertEquals("WAITING_FOR_RASPBERRY_PI", data()["status"].asText())
            DatagramSocket().use { sender ->
                val bytes = ByteArray(40)
                sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port))
                withTimeout(3000) { while (source.udpDiagnostics().invalidHeaderCount == 0L) delay(5) }
                val receiving = data()
                assertEquals("RECEIVING", receiving["status"].asText())
                assertEquals(1, receiving["datagramsReceived"].asLong()); assertEquals(40, receiving["bytesReceived"].asLong())
                assertEquals("127.0.0.1", receiving["lastPacketSourceIpv4"].asText())
                assertEquals(sender.localPort, receiving["lastPacketSourcePort"].asInt())
                assertTrue(receiving["lastPacketEpochMillis"].asLong() > 0)
                clock.set(5000)
                assertEquals("NO_PACKETS_RECENTLY", data()["status"].asText())
                c.application.saveUdpInput(c.deviceId, "filter", 2, enabled.copy(raspberryPiIpv4 = "127.0.0.2"))
                c.application.applyUdpInput(c.deviceId, "filter-apply", 3, 3)
                sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port))
                withTimeout(3000) { while (source.udpDiagnostics().sourceFilteredDatagrams == 0L) delay(5) }
                assertEquals("SOURCE_IP_MISMATCH", data()["status"].asText())
            }
            c.application.stop(c.deviceId, "stop")
            assertEquals("DISABLED", data()["status"].asText())
        } finally { c.close() }
    }
    @Test fun oneSourceDiagnosticsExposeStatusAndNoCameraSelector() = testApplication {
        val c = openApiComposition(); application { installRestApi(c.application) }
        try {
            val response = client.get("/api/v1/devices/filamentvision-1/udp-diagnostics")
            assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
            val data = parse(response.bodyAsText())["data"]
            assertEquals("DISABLED", data["status"].asText())
            assertEquals(0L, data["datagramsReceived"].asLong())
            assertTrue(data["lastPacketSourceIpv4"].isNull)
            assertFalse(data.has("cameraId")); assertFalse(data.has("cameraA")); assertFalse(data.has("cameraB"))
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/devices/missing/udp-diagnostics").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/devices/filamentvision-1/cameras/A/udp-diagnostics").status)
        } finally { c.close() }
    }
}
