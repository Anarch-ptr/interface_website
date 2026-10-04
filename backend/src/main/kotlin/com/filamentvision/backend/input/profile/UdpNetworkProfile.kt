package com.filamentvision.backend.input.profile

data class UdpNetworkProfile(
    val bind: BindProfile,
    val destination: AdvertisedDestinationProfile,
    val sourceFilter: SourceFilterProfile,
    val listenPort: Int,
)

sealed interface BindProfile {
    data object Auto : BindProfile
    data object AllInterfaces : BindProfile
    data class SpecificInterface(val interfaceId: String, val ipv4: String) : BindProfile
    data class ManualIp(val ipv4: String) : BindProfile
}

sealed interface AdvertisedDestinationProfile {
    data object Auto : AdvertisedDestinationProfile
    data class Manual(val hostOrIpv4: String) : AdvertisedDestinationProfile
}

sealed interface SourceFilterProfile {
    data object Any : SourceFilterProfile
    data class IpOrHost(val hostOrIpv4: String) : SourceFilterProfile
}

/** Pure syntax validation. DNS and interface ownership are Apply-time concerns. */
class UdpNetworkProfileValidator {
    fun validate(profile: UdpNetworkProfile): UdpInputValidationResult {
        val errors = mutableListOf<UdpInputValidationError>()
        if (profile.listenPort !in 1..65_535) {
            errors += error("listenPort", "OUT_OF_RANGE", "Listen port must be between 1 and 65535")
        }
        when (val bind = profile.bind) {
            BindProfile.Auto, BindProfile.AllInterfaces -> Unit
            is BindProfile.SpecificInterface -> {
                if (bind.interfaceId.isBlank()) {
                    errors += error("bind.interfaceId", "INVALID_VALUE", "Select a network interface")
                }
                validateIpv4("bind.ipv4", bind.ipv4, errors)
            }
            is BindProfile.ManualIp -> validateIpv4("bind.ipv4", bind.ipv4, errors)
        }
        when (val destination = profile.destination) {
            AdvertisedDestinationProfile.Auto -> Unit
            is AdvertisedDestinationProfile.Manual ->
                validateHostOrIpv4("destination.hostOrIpv4", destination.hostOrIpv4, errors)
        }
        when (val source = profile.sourceFilter) {
            SourceFilterProfile.Any -> Unit
            is SourceFilterProfile.IpOrHost ->
                validateHostOrIpv4("sourceFilter.hostOrIpv4", source.hostOrIpv4, errors)
        }
        return UdpInputValidationResult(errors)
    }

    private fun validateIpv4(
        path: String,
        value: String,
        errors: MutableList<UdpInputValidationError>,
    ) {
        if (!isCanonicalIpv4Literal(value)) {
            errors += error(path, if (':' in value) "IPV6_NOT_SUPPORTED" else "INVALID_VALUE", "Accepts only canonical IPv4 literals")
        }
    }

    private fun validateHostOrIpv4(
        path: String,
        value: String,
        errors: MutableList<UdpInputValidationError>,
    ) {
        try {
            normalizeHostOrIpv4(value)
        } catch (_: IllegalArgumentException) {
            errors += error(path, if (':' in value) "IPV6_NOT_SUPPORTED" else "INVALID_HOST_OR_IPV4", "Enter a canonical IPv4 literal or ASCII hostname")
        }
    }

    private fun error(path: String, code: String, message: String) = UdpInputValidationError(path, code, message)
}

fun normalizeHostOrIpv4(value: String): String {
    require(value.isNotEmpty() && value == value.trim()) { "Host must not be empty or contain surrounding whitespace" }
    if (isCanonicalIpv4Literal(value)) return value
    require(!value.all { it in '0'..'9' || it == '.' }) { "Malformed IPv4 literals are not hostnames" }
    require(':' !in value) { "IPv6 is not supported" }
    require(value.none(Char::isWhitespace) && value.none { it == '/' || it == '\\' || it == '[' || it == ']' }) {
        "Host must not contain a scheme, path, brackets or whitespace"
    }
    val normalized = value.removeSuffix(".").lowercase()
    require(normalized.length in 1..253 && normalized.all { it.code in 0..127 }) { "Hostname length or character is invalid" }
    val labels = normalized.split('.')
    require(labels.all { label ->
        label.length in 1..63 && label.first().isAsciiAlphaNumeric() && label.last().isAsciiAlphaNumeric() &&
            label.all { it.isAsciiAlphaNumeric() || it == '-' }
    }) { "Hostname labels must contain only ASCII letters, digits and internal hyphens" }
    return normalized
}

internal fun isCanonicalIpv4Literal(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.all { it in '0'..'9' } &&
            (part.length == 1 || part.first() != '0') && part.toIntOrNull() in 0..255
    }
}

private fun Char.isAsciiAlphaNumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
