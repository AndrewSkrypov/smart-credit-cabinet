package ru.creditcabinet.servicing.infrastructure

/** Translate deployment settings into a use-case permission without enabling demo accounts. */
class PaymentReserveSimulationPolicy(
    demoAccounts: Boolean = false,
    devSimulationEnabled: Boolean = false,
    deploymentProject: String = "",
) {
    init {
        require(!devSimulationEnabled || deploymentProject == "cabinet-dev") {
            "Payment reserve simulation requires the cabinet-dev deployment context"
        }
        require(!devSimulationEnabled || !demoAccounts) {
            "Protected Dev simulation requires demo accounts to remain disabled"
        }
        require(!deploymentProject.startsWith("cabinet-prod") || (!demoAccounts && !devSimulationEnabled)) {
            "Payment reserve simulation is disabled in production"
        }
    }

    val enabled: Boolean = demoAccounts || devSimulationEnabled
}
