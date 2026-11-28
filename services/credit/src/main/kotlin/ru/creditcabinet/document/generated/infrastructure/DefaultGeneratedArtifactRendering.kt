package ru.creditcabinet.document.generated.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.document.generated.ArtifactRenderer
import ru.creditcabinet.document.generated.application.GeneratedArtifactRendering

@Component
class DefaultGeneratedArtifactRendering(private val renderer: ArtifactRenderer) : GeneratedArtifactRendering {
    override val templateVersion: Int get() = ArtifactRenderer.TEMPLATE_VERSION

    override fun title(kind: ArtifactKind, input: ArtifactInput): String = renderer.title(kind, input)

    override fun render(kind: ArtifactKind, input: ArtifactInput): ByteArray = renderer.render(kind, input)
}
