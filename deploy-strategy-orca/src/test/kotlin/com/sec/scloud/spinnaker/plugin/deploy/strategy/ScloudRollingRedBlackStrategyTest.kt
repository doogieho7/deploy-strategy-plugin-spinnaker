package com.sec.scloud.spinnaker.plugin.deploy.strategy

import com.netflix.spinnaker.orca.capabilities.CapabilitiesService
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import org.junit.jupiter.api.Assertions.*
import strikt.api.expectThat

class ScloudRollingRedBlackStrategyTest : JUnit5Minutests {

    fun tests() = rootContext {
        test("execute RollingRedBlackStrategy") {
            /* TODO
            var strategy = RollingRedBlackStrategy()
            expectThat(NewService(StubCapabilitiesService(), NewProperties()).test())
                    .isEqualTo(
                            listOf("new service", "works")
                    )
             */
        }
    }
}