package ru.creditcabinet.servicing.grpc

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat

/** Typed descriptors define the wire schema; exact decimal values are encoded as strings. */
class ServicingWire(mapper: ObjectMapper) {
    private val json = mapper.copy()
        .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun <T : Message> encode(value: Any, builder: Message.Builder): T {
        val tree = json.valueToTree<ObjectNode>(value)
        decimalStrings(tree, builder.descriptorForType)
        JsonFormat.parser().merge(json.writeValueAsString(tree), builder)
        @Suppress("UNCHECKED_CAST")
        return builder.build() as T
    }

    fun <T> decode(value: Message, type: Class<T>): T = json.readValue(JsonFormat.printer().alwaysPrintFieldsWithNoPresence().print(value), type)

    private fun decimalStrings(node: ObjectNode, descriptor: Descriptors.Descriptor) {
        for (field in descriptor.fields) {
            val child = node.get(field.jsonName) ?: continue
            if (child.isNull) continue
            if (field.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                if (field.isRepeated) {
                    child.forEach { decimalStrings(it as ObjectNode, field.messageType) }
                } else {
                    decimalStrings(child as ObjectNode, field.messageType)
                }
            } else if (field.javaType == Descriptors.FieldDescriptor.JavaType.STRING && child.isNumber) {
                // valueToTree preserves BigDecimal; no conversion through double occurs.
                node.put(field.jsonName, child.asText())
            }
        }
    }
}
