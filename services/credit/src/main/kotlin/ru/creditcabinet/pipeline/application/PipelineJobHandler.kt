package ru.creditcabinet.pipeline.application

import ru.creditcabinet.application.domain.PipelineJob

fun interface PipelineJobHandler {
    fun run(job: PipelineJob)
}
