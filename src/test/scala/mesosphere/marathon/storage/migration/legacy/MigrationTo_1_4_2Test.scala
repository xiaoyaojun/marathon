package mesosphere.marathon
package storage.migration.legacy

import akka.Done
import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.Protos.ResidencyDefinition.TaskLostBehavior
import mesosphere.marathon.state.{ AppDefinition, PathId, Residency, UnreachableStrategy }
import mesosphere.marathon.storage.repository.AppRepository
import mesosphere.marathon.test.GroupCreation

import scala.concurrent.Future

class MigrationTo_1_4_2Test extends AkkaUnitTest with GroupCreation {

  "Migration to 1.4.2" should {
    "do nothing if there are no resident apps" in {
      val appRepo = mock[AppRepository]
      appRepo.all() returns Source.single(AppDefinition(id = PathId("abc")))
      new MigrationTo_1_4_2(appRepo).migrate().futureValue
      verify(appRepo).all()
      noMoreInteractions(appRepo)
    }

    "fix wrong UnreachableStrategy for resident apps" in {
      val repo = mock[AppRepository]
      val badApp = AppDefinition(id = PathId("/badApp"), residency = Some(Residency(23L, TaskLostBehavior.WAIT_FOREVER)), unreachableStrategy = UnreachableStrategy.default(false))
      val goodApp = AppDefinition(id = PathId("/goodApp"))
      repo.all() returns Source(Seq(badApp, goodApp))
      repo.store(any) returns Future.successful(Done)
      new MigrationTo_1_4_2(repo).migrate().futureValue
      val fixedApp = badApp.copy(unreachableStrategy = UnreachableStrategy.default(true))
      verify(repo).all()
      verify(repo).store(fixedApp)
      noMoreInteractions(repo)
    }
  }
}
