package com.filamentvision.backend.input.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpNetworkProfileValidatorTest {
    private val validator = UdpNetworkProfileValidator()

    @Test
    fun acceptsEveryBindDestinationAndSourceModeWithoutPerformingResolution() {
        val profiles = listOf(
            profile(bind = BindProfile.Auto),
            profile(bind = BindProfile.AllInterfaces),
            profile(bind = BindProfile.SpecificInterface("wifi-stable-id", "192.168.2.10")),
            profile(bind = BindProfile.ManualIp("10.20.30.40")),
            profile(destination = AdvertisedDestinationProfile.Manual("backend.local")),
            profile(destination = AdvertisedDestinationProfile.Manual("192.168.2.10")),
            profile(source = SourceFilterProfile.IpOrHost("filament-pi.local")),
            profile(source = SourceFilterProfile.IpOrHost("192.168.2.20")),
        )

        profiles.forEach(::assertValid)
    }

    @Test
    fun normalizesCanonicalIpv4AndAsciiHostnamesWithoutChangingSingleLabelsOrLocalNames() {
        assertEquals("192.168.20.3", normalizeHostOrIpv4("192.168.20.3"))
        assertEquals("camera", normalizeHostOrIpv4("CAMERA."))
        assertEquals("filament-pi.local", normalizeHostOrIpv4("Filament-Pi.LOCAL."))
        assertEquals("pi.example.test", normalizeHostOrIpv4("PI.Example.Test"))
    }

    @Test
    fun rejectsSchemesPortsPathsWhitespaceEmptyLabelsAndInvalidLabels() {
        val invalid = listOf(
            "http://pi.local", "pi.local:5101", "pi.local/path", "pi local", "pi..local",
            "-pi.local", "pi-.local", "pi_local", "", ".", "[192.168.1.2]",
            "192.168.1", "192.168.001.2", "192.168.1.256",
            "a".repeat(64) + ".local", "a".repeat(250) + ".com",
        )
        invalid.forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { normalizeHostOrIpv4(value) }
            assertPaths(profile(destination = AdvertisedDestinationProfile.Manual(value)), "destination.hostOrIpv4")
            assertPaths(profile(source = SourceFilterProfile.IpOrHost(value)), "sourceFilter.hostOrIpv4")
        }
    }

    @Test
    fun rejectsIpv6AndIpv4MappedIpv6WithAStableCode() {
        listOf("::1", "2001:db8::1", "::ffff:192.168.1.2").forEach { value ->
            val result = validator.validate(profile(destination = AdvertisedDestinationProfile.Manual(value)))
            assertFalse(result.isValid)
            assertEquals("IPV6_NOT_SUPPORTED", result.errors.single().code)
        }
    }

    @Test
    fun validatesModeSpecificFieldsAndPortBoundaries() {
        assertPaths(profile(bind = BindProfile.SpecificInterface("", "192.168.1.2")), "bind.interfaceId")
        assertPaths(profile(bind = BindProfile.SpecificInterface("wifi", "192.168.001.2")), "bind.ipv4")
        assertPaths(profile(bind = BindProfile.ManualIp("pi.local")), "bind.ipv4")
        listOf(0, 65_536).forEach { assertPaths(profile(port = it), "listenPort") }
        listOf(1, 65_535).forEach { assertValid(profile(port = it)) }
    }

    @Test
    fun raspberryPiDefaultsUseTheExistingPortAndIndependentNetworkIntent() {
        val profile = RaspberryPiUdpInputProfile.defaults()
        assertFalse(profile.enabled)
        assertEquals(BindProfile.Auto, profile.network.bind)
        assertEquals(AdvertisedDestinationProfile.Auto, profile.network.destination)
        assertEquals(SourceFilterProfile.Any, profile.network.sourceFilter)
        assertEquals(5_000, profile.network.listenPort)
    }

    private fun profile(
        bind: BindProfile = BindProfile.Auto,
        destination: AdvertisedDestinationProfile = AdvertisedDestinationProfile.Auto,
        source: SourceFilterProfile = SourceFilterProfile.Any,
        port: Int = 5_000,
    ) = UdpNetworkProfile(bind, destination, source, port)

    private fun assertValid(profile: UdpNetworkProfile) {
        val result = validator.validate(profile)
        assertTrue(result.errors.toString(), result.isValid)
    }

    private fun assertPaths(profile: UdpNetworkProfile, vararg paths: String) {
        val result = validator.validate(profile)
        assertFalse(result.isValid)
        assertEquals(paths.toSet(), result.errors.map { it.path }.toSet())
    }
}
