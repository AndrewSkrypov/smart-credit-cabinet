package ru.creditcabinet.document.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import ru.creditcabinet.document.application.ArtifactInputs
import ru.creditcabinet.document.application.FrozenArtifactInput
import ru.creditcabinet.document.domain.ArtifactInput
import ru.creditcabinet.document.domain.artifactChecksum

@Component
class JacksonArtifactInputs(private val mapper: ObjectMapper) : ArtifactInputs {
    override fun freeze(input: ArtifactInput): FrozenArtifactInput {
        val json = mapper.writeValueAsString(input)
        return FrozenArtifactInput(json, artifactChecksum(json.toByteArray(Charsets.UTF_8)))
    }

    override fun restore(frozen: FrozenArtifactInput): ArtifactInput {
        val input = mapper.readValue<ArtifactInput>(frozen.json)
        require(artifactChecksum(mapper.writeValueAsBytes(input)) == frozen.hash) { "Generated input checksum mismatch" }
        return input
    }
}
