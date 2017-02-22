package mesosphere.marathon
package storage.migration.legacy

import akka.Done
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.AppRepository

import scala.concurrent.{ ExecutionContext, Future }

@SuppressWarnings(Array("ClassNames"))
class MigrationTo_1_4_2(appRepository: AppRepository)(implicit
  ctx: ExecutionContext,
    mat: Materializer) extends StrictLogging {

  private def fixResidentApp(app: AppDefinition): AppDefinition = {
    app.copy(unreachableStrategy = UnreachableStrategy.default(true))
  }

  @SuppressWarnings(Array("all")) // async/await
  def migrate(): Future[Done] = {
    appRepository.all().collect {
      case app: AppDefinition if app.isResident => app
    }.mapAsync(Int.MaxValue) { app =>
      logger.info(s"Disable Unreachable strategy for resident app: ${app.id}")
      appRepository.store(fixResidentApp(app))
    }.runForeach(_ => Done)
  }
}
