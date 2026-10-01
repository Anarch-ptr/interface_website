package com.filamentvision.backend.api

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.filamentvision.backend.application.ApplicationFailure
import com.filamentvision.backend.application.ApplicationFailureCode

/** Strict scalar types and a final output guard: Jackson must never quote NaN/Infinity. */
internal val apiJson: ObjectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .build().registerModule(SimpleModule().apply {
        val finiteDouble = object : JsonSerializer<Double>() {
            override fun serialize(value: Double, generator: JsonGenerator, provider: SerializerProvider) {
                check(value.isFinite()) { "Non-finite API value" }
                generator.writeNumber(value)
            }
        }
        addSerializer(Double::class.javaObjectType, finiteDouble)
        addSerializer(Double::class.javaPrimitiveType!!, finiteDouble)
    }).apply {
        coercionConfigFor(LogicalType.Textual).apply {
            setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
        }
    }

internal inline fun <reified T> decodeRequest(text: String): T = try {
    apiJson.readValue(text, T::class.java) ?: throw ApplicationFailure(ApplicationFailureCode.INVALID)
} catch (_: JsonProcessingException) { throw ApplicationFailure(ApplicationFailureCode.INVALID) }
