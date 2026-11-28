package ru.creditcabinet.servicing.infrastructure

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentReserveSimulationPolicyTest {
    @Test
    fun `simulation is disabled by default including an ordinary production deployment`() {
        assertFalse(PaymentReserveSimulationPolicy().enabled)
        assertFalse(PaymentReserveSimulationPolicy(deploymentProject = "cabinet-dev").enabled)
        assertFalse(PaymentReserveSimulationPolicy(deploymentProject = "cabinet-prod").enabled)
    }

    @Test
    fun `legacy isolated demo remains enabled without the Dev simulation flag`() {
        assertTrue(PaymentReserveSimulationPolicy(demoAccounts = true).enabled)
        assertTrue(PaymentReserveSimulationPolicy(demoAccounts = true, deploymentProject = "cabinet-dev-demo").enabled)
    }

    @Test
    fun `the exact Dev project can enable simulation while demo accounts remain disabled`() {
        assertTrue(
            PaymentReserveSimulationPolicy(
                demoAccounts = false,
                devSimulationEnabled = true,
                deploymentProject = "cabinet-dev",
            ).enabled,
        )
    }

    @Test
    fun `protected Dev simulation refuses an enabled demo account mode`() {
        assertFailsWith<IllegalArgumentException> {
            PaymentReserveSimulationPolicy(demoAccounts = true, devSimulationEnabled = true, deploymentProject = "cabinet-dev")
        }
    }

    @Test
    fun `missing foreign restore and lookalike project contexts cannot enable Dev simulation`() {
        val projects = listOf(
            "",
            "cabinet-prod",
            "cabinet-prod-test",
            "cabinet-dev-demo",
            "cabinet-dev-restore-test",
            "cabinet-dev-other",
            "CABINET-DEV",
            " cabinet-dev",
        )
        for (project in projects) {
            assertFailsWith<IllegalArgumentException>(project) {
                PaymentReserveSimulationPolicy(devSimulationEnabled = true, deploymentProject = project)
            }
        }
    }

    @Test
    fun `production rejects both legacy demo and Dev simulation enablement`() {
        for (project in listOf("cabinet-prod", "cabinet-prod-restore-test")) {
            for ((demo, dev) in listOf(true to false, false to true, true to true)) {
                assertFailsWith<IllegalArgumentException>("$project demo=$demo dev=$dev") {
                    PaymentReserveSimulationPolicy(
                        demoAccounts = demo,
                        devSimulationEnabled = dev,
                        deploymentProject = project,
                    )
                }
            }
        }
    }
}
