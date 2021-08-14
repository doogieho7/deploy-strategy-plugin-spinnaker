package com.sec.scloud.spinnaker.plugin.deploy.strategy

import com.netflix.spinnaker.orca.api.pipeline.SyntheticStageOwner
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.clouddriver.pipeline.cluster.DisableClusterStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.cluster.ScaleDownClusterStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.CloneServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.CreateServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.ResizeServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.strategies.AbstractDeployStrategyStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.strategies.RollingRedBlackStageData
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.strategies.Strategy
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.DetermineTargetServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.TargetServerGroup
import com.netflix.spinnaker.orca.front50.pipeline.PipelineStage
import com.netflix.spinnaker.orca.kato.pipeline.support.ResizeStrategy
import com.netflix.spinnaker.orca.kato.pipeline.support.ResizeStrategySupport
import com.netflix.spinnaker.orca.kato.pipeline.support.StageData
import com.netflix.spinnaker.orca.pipeline.WaitStage
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware


class ScloudRollingRedBlackStrategy : Strategy, ApplicationContextAware {
    private val logger = LoggerFactory.getLogger(ScloudRollingRedBlackStrategy::class.java)

    @Autowired
    lateinit var resizeStrategySupport: ResizeStrategySupport

    @Autowired(required = false)
    var pipelineStage: PipelineStage? = null

    //val String name = "rollingredblack"         //ROLLING_RED_BLACK.key
    override fun getName(): String = "rollingredblack"

    override fun composeBeforeStages(stage: StageExecution): MutableList<StageExecution> {
        logger.info("ScloudRollingRedBlackStrategy.composeBeforeStages")

        if (pipelineStage == null) {
            throw IllegalStateException("Rolling red/black cannot be run without front50 enabled. Please set 'front50.enabled: true' in your orca config.")
        }

        var stageData = stage.mapTo(RollingRedBlackStageData::class.java)

        stage.context.replace("useSourceCapacity", false)
        stage.context.replace("targetSize", 0)

        // we expect a capacity object if a fixed capacity has been requested or as a fallback value when we are copying
        // the capacity from the current server group
        var savedCapacity = if (stage.context.containsKey("savedCapacity")) {
            stage.context["savedCapacity"]
        } else {
            var savedCapacity = ResizeStrategy.Capacity();
            savedCapacity.min = 0
            savedCapacity.max = 0
            savedCapacity.desired = 0

            if (stage.context.containsKey("capacity")) {
                var capacity = stage.context["capacity"] as MutableMap<String, Int>
                savedCapacity.min = capacity["min"]
                savedCapacity.max = capacity["max"]
                savedCapacity.desired = capacity["desired"]
            }

            savedCapacity
        }
        stage.context["savedCapacity"] = savedCapacity

        // FIXME: this clobbers the input capacity value (if any). Should find a better way to request a new asg of size 0
        stage.context["capacity"] = mutableMapOf("min" to 0, "max" to 0, "desired" to 0)

        return mutableListOf<StageExecution>()
    }

    override fun composeAfterStages(stage: StageExecution): MutableList<StageExecution> {
        logger.info("ScloudRollingRedBlackStrategy.composeAfterStages")

        var stages = mutableListOf<StageExecution>()
        var stageData = stage.mapTo(RollingRedBlackStageData::class.java)
        var cleanupConfig = AbstractDeployStrategyStage.CleanupConfig.fromStage(stage)

        var baseContext = mutableMapOf<String, Any?>()
        baseContext[cleanupConfig.location.singularType()] = cleanupConfig.location.value
        baseContext["cluster"] = cleanupConfig.cluster
        baseContext["moniker"] = cleanupConfig.moniker
        baseContext["credentials"] = cleanupConfig.account
        baseContext["cloudProvider"] = cleanupConfig.cloudProvider

        // we expect a capacity object if a fixed capacity has been requested or as a fallback value when we are copying
        // the capacity from the current server group
        var savedCapacity = stage.context["savedCapacity"]

        var targetPercentages = stageData.targetPercentages
        if (targetPercentages.isEmpty() || targetPercentages.last() != 100) {
            targetPercentages.add(100)
        }

        var findContext = baseContext.toMutableMap()
        findContext["target"] = TargetServerGroup.Params.Target.current_asg_dynamic
        findContext["targetLocation"] = cleanupConfig.location

        // Get source ASG from prior determineSourceServerGroupTask
        var source: ResizeStrategy.Source?

        try {
            source = lookupSourceServerGroup(stage)
        } catch (e: Exception) {
            // This probably means there was no parent CreateServerGroup stage - which should never happen
            throw IllegalStateException("Failed to determine source server group from parent stage while planning RRB flow", e)
        }

        var determineTargetServerGroupStage = StageExecutionImpl(
                stage.execution,
                DetermineTargetServerGroupStage.PIPELINE_CONFIG_TYPE,
                "Determine Deployed Server Group",
                findContext)
        determineTargetServerGroupStage.parentStageId = stage.id
        determineTargetServerGroupStage.syntheticStageOwner = SyntheticStageOwner.STAGE_AFTER
        stages.add(determineTargetServerGroupStage)

        if (source == null) {
            logger.warn("no source server group -- will perform RRB to exact fallback capacity $savedCapacity with no disableCluster or scaleDownCluster stages")
        }

        // get source capacity - if source server group don't exit, we will use fallback capacity
        val sourceCapacity = if (source == null) {
            savedCapacity
        }
        else {
            resizeStrategySupport.getCapacity(
                    source.credentials,
                    source.serverGroupName,
                    source.cloudProvider,
                    source.location)
        }

        targetPercentages.forEach { p ->
            var resizeContext = baseContext.toMutableMap()
            resizeContext["target"] = TargetServerGroup.Params.Target.current_asg_dynamic
            resizeContext["targetLocation"] = cleanupConfig.location
            resizeContext["scalePct"] = p
            resizeContext["pinCapacity"] = (p < 100)
            resizeContext["unpinMinimumCapacity"] = (p == 100)
            resizeContext["useNameAsLabel"] = true           // hint to deck that it should _not_ override the name
            resizeContext["targetHealthyDeployPercentage"] = stage.context["targetHealthyDeployPercentage"]
            resizeContext["action"] = ResizeStrategy.ResizeAction.scale_exact
            resizeContext["capacity"] = sourceCapacity

            var resizeStage = StageExecutionImpl(
                    stage.execution,
                    ResizeServerGroupStage.TYPE,
                    "Grow target to $p% of Desired Size",
                    resizeContext
            )
            resizeStage.parentStageId = stage.id
            resizeStage.syntheticStageOwner = SyntheticStageOwner.STAGE_AFTER
            stages.add(resizeStage)

            // only generate the "Shrink source to (100-p)% of initial size" stages if we have something to shrink
            if (source != null) {
                stages.addAll(getBeforeCleanupStages(stage, stageData))

                var shrinkContext = baseContext.toMutableMap()
                shrinkContext["serverGroupName"] = source.serverGroupName
                shrinkContext["targetLocation"] = cleanupConfig.location
                shrinkContext["scalePct"] = 100-p
                shrinkContext["pinCapacity"] = true
                shrinkContext["useNameAsLabel"] = true     // hint to deck that it should _not_ override the name
                shrinkContext["targetHealthyDeployPercentage"] = stage.context["targetHealthyDeployPercentage"]
                shrinkContext["action"] = ResizeStrategy.ResizeAction.scale_exact
                shrinkContext["capacity"] = sourceCapacity

                logger.info("Adding `Shrink source to $p% of initial size` stage with context $shrinkContext [executionId=${stage.execution.id}]")

                var shrinkStage = StageExecutionImpl(
                        stage.execution,
                        ResizeServerGroupStage.TYPE,
                        "Shrink source to $p% of initial size",
                        shrinkContext
                )
                shrinkStage.parentStageId = stage.id
                shrinkStage.syntheticStageOwner = SyntheticStageOwner.STAGE_AFTER
                stages.add(shrinkStage)
            }
        }

        if (source != null) {
            // disable old
            var disableContext = baseContext.toMutableMap()
            disableContext["remainingEnabledServerGroups"] = 1
            disableContext["preferLargerOverNewer"] = false

            logger.info("Adding `Disable cluster` stage with context $disableContext [executionId=${stage.execution.id}]")

            var disableStage = StageExecutionImpl(
                    stage.execution,
                    DisableClusterStage.STAGE_TYPE,
                    "disableCluster",
                    disableContext
            )
            disableStage.parentStageId = stage.id
            disableStage.syntheticStageOwner = SyntheticStageOwner.STAGE_AFTER
            stages.add(disableStage)
        }

        // only scale down if we have a source server group to scale down
        if (source != null && stageData.scaleDown) {
            if(stageData.delayBeforeScaleDown > 0L) {
                var waitContext = mapOf("waitTime" to stageData.delayBeforeScaleDown)
                var waitStage = StageExecutionImpl(
                        stage.execution,
                        WaitStage.STAGE_TYPE,
                        "Wait Before Scale Down",
                        waitContext
                )
                waitStage.parentStageId = stage.id
                waitStage.syntheticStageOwner = SyntheticStageOwner.STAGE_AFTER
                stages.add(waitStage)
            }

            var scaleDownContext = baseContext.toMutableMap()
            scaleDownContext["allowScaleDownActive"] = false
            scaleDownContext["remainingFullSizeServerGroups"] = 1
            scaleDownContext["preferLargerOverNewer"] = false

            var scaleDownStage = StageExecutionImpl(
                    stage.execution,
                    ScaleDownClusterStage.PIPELINE_CONFIG_TYPE,
                    "scaleDown",
                    scaleDownContext
            )
            scaleDownStage.parentStageId = stage.id
            scaleDownStage.syntheticStageOwner = SyntheticStageOwner.STAGE_AFTER
            stages.add(scaleDownStage)
        }

        return stages
    }

    override fun setApplicationContext(applicationContext: ApplicationContext) {}

    fun lookupSourceServerGroup(stage: StageExecution): ResizeStrategy.Source? {

        var parentCreateServerGroupStage = stage.directAncestors().find {
            it.type == CreateServerGroupStage.PIPELINE_CONFIG_TYPE || it.type == CloneServerGroupStage.PIPELINE_CONFIG_TYPE
        }

        if (parentCreateServerGroupStage == null)
            throw IllegalStateException("Failed to find parent CreateServerGroupStage or CloneServerGroupStage")

        var parentStageData = parentCreateServerGroupStage.mapTo(StageData::class.java)
        var sourceServerGroup = parentStageData.source
        var stageData = stage.mapTo(RollingRedBlackStageData::class.java)

        var source: ResizeStrategy.Source? = null

        if (sourceServerGroup?.serverGroupName != null || sourceServerGroup?.asgName != null) {
            source = ResizeStrategy.Source()
            source.region = sourceServerGroup.region
            source.serverGroupName = sourceServerGroup.serverGroupName ?: sourceServerGroup.asgName
            source.credentials = stageData.credentials ?: stageData.account
            source.cloudProvider = stageData.cloudProvider
        }

        return source
    }

    fun getBeforeCleanupStages(
            parentStage: StageExecution,
            stageData: RollingRedBlackStageData): List<StageExecution> {
        val stages = mutableListOf<StageExecution>()

        if (stageData.delayBeforeCleanup > 0L) {
            val waitContext = mapOf(
                    "waitTime" to stageData.delayBeforeCleanup
            )

            val stage = StageExecutionImpl(parentStage.execution, WaitStage.STAGE_TYPE, "wait", waitContext)
            stage.parentStageId = parentStage.id
            stage.syntheticStageOwner = SyntheticStageOwner.STAGE_AFTER
            stages.add(stage)
        }

        return stages
    }

}