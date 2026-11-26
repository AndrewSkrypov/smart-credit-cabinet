package ru.creditcabinet.servicing.grpc

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat

/** Typed protobuf descriptors are the wire schema. Exact decimal strings never become doubles. */
class ServicingWire(mapper: ObjectMapper) {
    private val json = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    fun <T : Message> encode(value: Any, builder: Message.Builder): T {
        JsonFormat.parser().merge(json.writeValueAsString(value),builder)
        @Suppress("UNCHECKED_CAST")
        return builder.build() as T
    }
    fun <T> decode(value: Message, type: Class<T>): T = json.readValue(JsonFormat.printer().alwaysPrintFieldsWithNoPresence().print(value),type)
}
