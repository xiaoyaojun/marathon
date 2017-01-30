package mesosphere.marathon
package core.group

import javax.inject.Provider

import akka.event.EventStream
import akka.stream.Materializer
import com.codahale.metrics.Gauge
import mesosphere.marathon.core.group.impl.GroupManagerImpl
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.repository.GroupRepository

import scala.concurrent.{ Await, ExecutionContext }

/**
  * Provides a [[GroupManager]] implementation.
  */
class GroupManagerModule(
    config: GroupManagerConfig,
    leadershipModule: LeadershipModule,
    scheduler: Provider[DeploymentService],
    groupRepo: GroupRepository,
    storage: StorageProvider,
    metrics: Metrics)(implicit mat: Materializer, ctx: ExecutionContext, eventStream: EventStream) {

  val groupManager: GroupManager = {
    val groupManager = new GroupManagerImpl(config, Await.result(groupRepo.root(), config.zkTimeoutDuration), groupRepo, scheduler, storage)

    metrics.gauge("service.mesosphere.marathon.app.count", new Gauge[Int] {
      override def getValue: Int = {
        // Accessing rootGroup from the repository because getting it from groupManager will fail
        // on non-leader marathon instance.
        Await.result(groupRepo.root(), config.zkTimeoutDuration).transitiveApps.size
      }
    })

    metrics.gauge("service.mesosphere.marathon.group.count", new Gauge[Int] {
      override def getValue: Int = {
        // Accessing rootGroup from the repository because getting it from groupManager will fail
        // on non-leader marathon instance.
        Await.result(groupRepo.root(), config.zkTimeoutDuration).transitiveGroupsById.size
      }
    })

    metrics.gauge("service.mesosphere.marathon.uptime", new Gauge[Long] {
      val startedAt = System.currentTimeMillis()

      override def getValue: Long = {
        System.currentTimeMillis() - startedAt
      }
    })

    groupManager
  }
}
