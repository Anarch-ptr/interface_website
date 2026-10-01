package com.filamentvision.backend.api.dto

import com.filamentvision.backend.application.ApplicationFailure
import com.filamentvision.backend.application.ApplicationFailureCode
import com.filamentvision.core.repository.HistoryCursor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Versioned, opaque, scope-bound keyset. Not an authorization token. */
fun HistoryCursor.encodeCursor(scope: String): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { it.writeByte(1); it.writeUTF(scope); it.writeLong(timestamp); it.writeUTF(id) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
}
fun decodeCursor(value: String, scope: String): HistoryCursor = try {
    require(value.length in 1..2048 && value.matches(Regex("[A-Za-z0-9_-]+")))
    val bytes = Base64.getUrlDecoder().decode(value)
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
    DataInputStream(ByteArrayInputStream(bytes)).use {
        require(it.readUnsignedByte() == 1 && it.readUTF() == scope)
        HistoryCursor(it.readLong(), it.readUTF()).also { cursor ->
            require(cursor.timestamp >= 0 && cursor.id.isNotBlank() && it.available() == 0)
        }
    }
} catch (_: Exception) { throw ApplicationFailure(ApplicationFailureCode.INVALID) }
