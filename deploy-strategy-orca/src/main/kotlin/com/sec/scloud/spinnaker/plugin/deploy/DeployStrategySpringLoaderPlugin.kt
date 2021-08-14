package com.sec.scloud.spinnaker.plugin.deploy

import com.netflix.spinnaker.kork.plugins.api.spring.PrivilegedSpringPlugin
import com.sec.scloud.spinnaker.plugin.deploy.strategy.ScloudRollingRedBlackStrategy
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry

class DeployStrategySpringLoaderPlugin(wrapper: PluginWrapper) : PrivilegedSpringPlugin(wrapper) {

    /* for SpringLoaderPlugin
    override fun getPackagesToScan(): List<String> {
        return listOf(
            "com.sec.scloud.spinnaker.plugin.deploy"
        )
    }
    */

    override fun registerBeanDefinitions(registry: BeanDefinitionRegistry?) {
        // replace existing Bean 'RollingRedBlackStrategy' with 'ScloudRollingRedBlackStrategy'
        listOf(
            Pair("rollingRedBlackStrategy", ScloudRollingRedBlackStrategy::class.java)
        ).forEach {
            val beanDefinition = primaryBeanDefinitionFor(it.second)

            registry?.registerBeanDefinition(it.first, beanDefinition)
        }

        logger.info("DeployStrategySpringLoaderPlugin.registerBeanDefinitions() - end")
    }

    private val logger = LoggerFactory.getLogger(DeployStrategySpringLoaderPlugin::class.java)

    override fun start() {
        logger.info("DeployStrategySpringLoaderPlugin.start()")
    }

    override fun stop() {
        logger.info("DeployStrategySpringLoaderPlugin.stop()")
    }
}
