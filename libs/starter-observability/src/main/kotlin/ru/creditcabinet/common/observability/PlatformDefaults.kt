package ru.creditcabinet.common.observability

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Settings every service shares, added with the lowest precedence: a service's own
 * application.yml or an environment variable still wins.
 */
class PlatformDefaults : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        environment.propertySources.addLast(MapPropertySource("cabinetPlatformDefaults", DEFAULTS))
    }

    private companion object {
        val DEFAULTS: Map<String, Any> = mapOf(
            // Scraped by Prometheus inside the cluster; not published through the ingress.
            "management.endpoints.web.exposure.include" to "health,info,prometheus",
            "management.endpoint.health.probes.enabled" to true,
            "management.metrics.tags.application" to "\${spring.application.name}",
            // Histograms, so latency percentiles can be computed across replicas.
            "management.metrics.distribution.percentiles-histogram.http.server.requests" to true,
            // Every request gets a trace id (logs, X-Request-Id); nothing is exported yet.
            "management.tracing.sampling.probability" to 0.0,
        )
    }
}
