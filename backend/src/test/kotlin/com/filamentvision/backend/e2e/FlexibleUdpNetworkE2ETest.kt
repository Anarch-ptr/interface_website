package com.filamentvision.backend.e2e

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.input.network.*
import com.filamentvision.backend.input.inspector.RawParseStatus
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.DatagramSocket
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FlexibleUdpNetworkE2ETest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun changedHostnameResolutionReappliesSameRevisionAndReplacesLiveSourceSet(): Unit = runBlocking {
        val c = open("hostname-reapply")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val resolver = MutableResolver(mutableMapOf(
            "backend.local" to linkedSetOf("127.0.0.1"),
            "filament-pi.local" to linkedSetOf("127.0.0.1", "127.0.0.2"),
        ))
        val service = service(c, resolver)
        val port = freePort()
        val profile = grayProfile(port, SourceFilterProfile.IpOrHost("filament-pi.local"))
        try {
            val saved = service.save(c.udpProfileRepository.state().saved.revision, profile)
            val first = service.apply(saved.revision, forceReapply = true)
            assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, first.outcome)
            c.application.start(c.deviceId, "start", null, null)
            val lease = source.packetInspector.createLease(128)
            UdpTestSender(sourceIpv4 = "127.0.0.1", destinationIpv4 = "127.0.0.1").use { sender ->
                sender.sendStructured(port, 1u, grayPayload(), 97, listOf(3, 2, 1, 0))
            }
            await { c.runtime.state.value.statistics.sampleCount == 1L }
            val before = c.udpProfileRepository.state()
            assertEquals(linkedSetOf("127.0.0.1", "127.0.0.2"), before.appliedPlan!!.resolvedSourceIpv4s)

            resolver.answers["filament-pi.local"] = linkedSetOf("127.0.0.2")
            val preview = service.preview(c.deviceId, profile, before)
            assertTrue(preview.reapplyRequired)
            assertEquals(NetworkConsistencyStatus.REAPPLY_REQUIRED, preview.consistencyStatus)
            val reapplied = service.apply(saved.revision, forceReapply = true,
                expectedAppliedRevision = before.applied.revision,
                expectedResolutionGeneration = before.resolutionGeneration)
            assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, reapplied.outcome)
            val after = c.udpProfileRepository.state()
            assertEquals(before.saved.revision, after.saved.revision)
            assertEquals(before.applied.revision, after.applied.revision)
            assertEquals(before.resolutionGeneration + 1, after.resolutionGeneration)
            assertEquals(setOf("127.0.0.2"), after.appliedPlan!!.resolvedSourceIpv4s)
            assertFalse(after.appliedPlan!!.resolvedSourceIpv4s.contains("127.0.0.1"))

            UdpTestSender(sourceIpv4 = "127.0.0.1", destinationIpv4 = "127.0.0.1").use { sender ->
                sender.send(port, byteArrayOf(1))
            }
            await { source.udpDiagnostics().sourceFilteredDatagrams == 1L }
            assertEquals(1L, c.runtime.state.value.statistics.sampleCount)
            assertEquals(RawParseStatus.SOURCE_IP_MISMATCH, source.packetInspector.latest(lease.id)!!.raw.status)
            assertEquals(setOf("127.0.0.2"), source.packetInspector.latest(lease.id)!!.raw.appliedNetwork!!.resolvedSourceIpv4s)

            UdpTestSender(sourceIpv4 = "127.0.0.2", destinationIpv4 = "127.0.0.1").use { sender ->
                sender.sendStructured(port, 2u, grayPayload(), 97, listOf(3, 2, 1, 0))
            }
            await { c.runtime.state.value.statistics.sampleCount == 2L }
            assertEquals(1, source.activeProducerCount)
            assertEquals(1, source.activeReceiverCount)
            source.packetInspector.releaseLease(lease.id)
        } finally { c.close() }
    }

    @Test fun wildcardToSpecificBindRestartAndFailedCandidatePreserveTheCommittedReceiver(): Unit = runBlocking {
        val c = open("bind-restart")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val resolver = MutableResolver(mutableMapOf("backend.local" to linkedSetOf("127.0.0.1")))
        val service = service(c, resolver)
        val firstPort = freePort()
        val first = grayProfile(firstPort, SourceFilterProfile.Any)
        try {
            apply(service, c, first)
            c.application.start(c.deviceId, "start", null, null)
            UdpTestSender().use { it.sendStructured(firstPort, 10u, grayPayload(), 97) }
            await { c.runtime.state.value.statistics.sampleCount == 1L }

            val secondPort = freePort()
            val specific = first.copy(network = first.network.copy(
                bind = BindProfile.SpecificInterface("loopback", "127.0.0.1"), listenPort = secondPort))
            apply(service, c, specific)
            UdpTestSender().use { it.sendStructured(secondPort, 11u, grayPayload(), 97) }
            await { c.runtime.state.value.statistics.sampleCount == 2L }

            val local = checkNotNull(JavaIpv4NetworkInterfaceProvider().listEligible().firstOrNull()) {
                "Task 19 requires one real non-loopback IPv4 interface for MANUAL_IP socket verification"
            }
            val manualPort = freePort()
            val manual = specific.copy(network = specific.network.copy(
                bind = BindProfile.ManualIp(local.ipv4), listenPort = manualPort))
            apply(service, c, manual)
            UdpTestSender(sourceIpv4 = local.ipv4, destinationIpv4 = local.ipv4).use {
                it.sendStructured(manualPort, 12u, grayPayload(), 97)
            }
            await { c.runtime.state.value.statistics.sampleCount == 3L }
            val committed = c.udpProfileRepository.state()

            DatagramSocket(0).use { occupied ->
                val failed = manual.copy(network = manual.network.copy(listenPort = occupied.localPort))
                val saved = service.save(committed.saved.revision, failed)
                assertTrue(runCatching { service.apply(saved.revision) }.isFailure)
            }
            assertEquals(manualPort, source.effectiveProfile.network.listenPort)
            assertEquals(committed.applied.revision, c.udpProfileRepository.state().applied.revision)
            assertEquals(committed.appliedPlan, c.udpProfileRepository.state().appliedPlan)
            UdpTestSender(sourceIpv4 = local.ipv4, destinationIpv4 = local.ipv4).use {
                it.sendStructured(manualPort, 13u, grayPayload(), 97)
            }
            await { c.runtime.state.value.statistics.sampleCount == 4L }
            assertEquals(1, source.activeReceiverCount)
            assertEquals(1, source.activeProducerCount)
        } finally { c.close() }
    }

    @Test fun differentSubnetSourceAndDestinationRemainValidConfigurationIntent(): Unit = runBlocking {
        val c = open("routed-subnets")
        val resolver = MutableResolver(mutableMapOf(
            "backend.remote" to linkedSetOf("192.168.50.10"),
            "filament-pi.remote" to linkedSetOf("10.0.0.2"),
        ))
        val service = service(c, resolver)
        val base = grayProfile(freePort(), SourceFilterProfile.IpOrHost("filament-pi.remote"))
        val profile = base.copy(
            network = base.network.copy(
                destination = AdvertisedDestinationProfile.Manual("backend.remote"),
                sourceFilter = SourceFilterProfile.IpOrHost("filament-pi.remote"),
            ),
        )
        try {
            val saved = service.save(c.udpProfileRepository.state().saved.revision, profile)
            val result = service.apply(saved.revision, forceReapply = true)
            assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, result.outcome)
            val plan = c.udpProfileRepository.state().appliedPlan!!
            assertEquals("192.168.50.10", plan.resolvedDestinationIpv4)
            assertEquals(setOf("10.0.0.2"), plan.resolvedSourceIpv4s)
        } finally { c.close() }
    }

    @Test fun structuredJpegCrossesRealSocketIntoVisionAndBoundedInspector(): Unit = runBlocking {
        val c = open("jpeg")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val resolver = MutableResolver(mutableMapOf("backend.local" to linkedSetOf("127.0.0.1")))
        val service = service(c, resolver)
        val port = freePort()
        val profile = grayProfile(port, SourceFilterProfile.Any).copy(
            payload = ImagePayloadProfile.Jpeg(4096, 48, 8),
            transportLimits = TransportLimits(2, 4096, 1000, 128, 128),
        )
        try {
            apply(service, c, profile)
            c.application.start(c.deviceId, "start", null, null)
            val lease = source.packetInspector.createLease(256)
            val jpeg = jpegPayload()
            val chunks = (jpeg.size + 63) / 64
            UdpTestSender().use { sender ->
                sender.sendStructured(port, 20u, jpeg, 64, (0 until chunks).reversed().toList())
            }
            await { c.runtime.state.value.statistics.sampleCount == 1L }
            assertEquals(1.75, c.application.latest(c.deviceId)!!.measurement.diameterMm, 1e-9)
            assertEquals("VALID", source.packetInspector.latest(lease.id)!!.parsedFrame!!.decodeResult)
            assertTrue(source.packetInspector.retainedPreviewBytes <= 512)
            assertEquals("127.0.0.1", source.packetInspector.latest(lease.id)!!.raw.appliedNetwork!!.resolvedDestinationIpv4)
            assertEquals(1, source.activeProducerCount)
            assertEquals(1, source.activeReceiverCount)
            source.packetInspector.releaseLease(lease.id)
            assertEquals(0, source.packetInspector.retainedPreviewBytes)
        } finally { c.close() }
    }

    private suspend fun open(name: String) = BackendComposition.open(
        temporary.root.toPath().resolve("$name.db"), temporary.root.toPath().resolve("$name-objects"))

    private fun service(c: BackendComposition, resolver: Ipv4HostResolver): RaspberryPiUdpInputConfigurationService {
        val inventory = Ipv4NetworkInterfaceProvider { listOf(
            Ipv4NetworkInterfaceEntry("loopback", "Loopback", "127.0.0.1", true, true, false, false, 1),
        ) + JavaIpv4NetworkInterfaceProvider().listEligible() }
        return RaspberryPiUdpInputConfigurationService(c.udpProfileRepository, checkNotNull(c.udpInputRouter),
            resolver, EffectiveUdpNetworkPlanner(inventory, Ipv4RouteProbe { "127.0.0.1" }))
    }

    private suspend fun apply(service: RaspberryPiUdpInputConfigurationService, c: BackendComposition,
        profile: RaspberryPiUdpInputProfile) {
        val saved = service.save(c.udpProfileRepository.state().saved.revision, profile)
        assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, service.apply(saved.revision).outcome)
    }

    private fun grayProfile(port: Int, source: SourceFilterProfile) = RaspberryPiUdpInputProfile.defaults().copy(
        enabled = true,
        network = UdpNetworkProfile(BindProfile.Auto,
            AdvertisedDestinationProfile.Manual("backend.local"), source, port),
        payload = ImagePayloadProfile.Gray8(48, 8),
        transportLimits = TransportLimits(2, 4096, 1000, 128, 128),
    )

    private fun grayPayload() = ByteArray(48 * 8) { if (it % 48 in 6 until 41) 20 else 200.toByte() }

    private fun jpegPayload(): ByteArray = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(48, 8, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 8) for (x in 0 until 48) image.setRGB(x, y,
            if (x in 6 until 41) 0xff141414.toInt() else 0xffc8c8c8.toInt())
        check(ImageIO.write(image, "jpeg", output))
        output.toByteArray()
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }

    private fun freePort() = DatagramSocket(0).use { it.localPort }

    private class MutableResolver(val answers: MutableMap<String, LinkedHashSet<String>>) : Ipv4HostResolver {
        private val clock = AtomicLong(100)
        override suspend fun resolveIpv4(hostOrIpv4: String): HostResolution {
            val values = answers[hostOrIpv4] ?: linkedSetOf(hostOrIpv4)
            return HostResolution(hostOrIpv4, LinkedHashSet(values), HostResolutionStatus.RESOLVED,
                clock.incrementAndGet())
        }
    }
}
