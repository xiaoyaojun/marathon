package mesosphere.marathon
package storage.migration

import akka.stream.scaladsl.{ Sink, Source }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.api.v2.{ AppNormalization, AppsResource }
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork }
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.VersionInfo.{ FullVersionInfo, OnlyVersion }
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.{ Range, RangesResource, ScalarResource }
import org.apache.mesos.{ Protos => Mesos }
import org.scalatest.RecoverMethods

import scala.concurrent.Future

class MigrationTo1_5Test extends AkkaUnitTest with RecoverMethods {

  import MigrationTo1_5._

  private def migrateSingleApp(sd: Protos.ServiceDefinition)(implicit n: Normalization[raml.App]): AppDefinition =
    migrateSingleAppF(sd).futureValue

  private def migrateSingleAppF(sd: Protos.ServiceDefinition)(implicit n: Normalization[raml.App]): Future[AppDefinition] =
    Source.single(sd).via(migrateServiceFlow).runWith(Sink.head)

  "MigrationTo1_5" when {
    "migrating app without --default-network-name set" should {
      "basic command" in new Fixture {
        migrateSingleApp(basicCommandService) should be(basicCommandApp)
      }

      "scale+config versions" in new Fixture {
        val sd = basicCommandService.toBuilder
          .setLastScalingAt(2000L)
          .setLastConfigChangeAt(3000L)
          .build
        val expected = basicCommandApp.copy(
          versionInfo = FullVersionInfo(
            version = basicCommandApp.version,
            lastScalingAt = Timestamp(2000L),
            lastConfigChangeAt = Timestamp(3000L)
          )
        )
        migrateSingleApp(sd) should be(expected)
      }

      "mesos container, host networking" in new Fixture {
        val sd = withContainer { containerInfo =>
          containerInfo.setType(Mesos.ContainerInfo.Type.MESOS)
          containerInfo.addVolumes(Protos.Volume.newBuilder()
            .setHostPath("/host")
            .setContainerPath("containerPath")
            .setMode(Mesos.Volume.Mode.RW)
            .build
          )
        }
        val expected = basicCommandApp.copy(
          container = Some(Container.Mesos(Seq(DockerVolume("containerPath", "/host", Mesos.Volume.Mode.RW))))
        )
        migrateSingleApp(sd) should be(expected)
      }

      "mesos container, host networking, w/o ports" in new Fixture {
        val sd = withContainer(_.setType(Mesos.ContainerInfo.Type.MESOS)).toBuilder.clearPortDefinitions().build
        val expected = basicCommandApp.copy(container = Some(Container.Mesos()), portDefinitions = Nil)
        migrateSingleApp(sd) should be(expected)
      }

      def failsWithNetworkNameRequired(title: String)(f: Fixture => Protos.ServiceDefinition) = {
        title in new Fixture {
          val sd: Protos.ServiceDefinition = f(this)

          recoverToExceptionIf[SerializationFailedException] {
            migrateSingleAppF(sd)
          }.map { ex =>
            ex.getMessage should be(raml.NetworkConversionMessages.ContainerNetworkRequiresName)
          }.futureValue
        }
      }

      behave like failsWithNetworkNameRequired("mesos container, IP/CT networking (unnamed), w/o ports"){ fixture =>
        fixture.withDeprecatedIpAddress(fixture.withContainer(_.setType(Mesos.ContainerInfo.Type.MESOS)), ports = Nil)
      }

      behave like failsWithNetworkNameRequired("mesos container, IP/CT networking (unnamed)"){ fixture =>
        fixture.withDeprecatedIpAddress(fixture.withContainer(_.setType(Mesos.ContainerInfo.Type.MESOS)))
      }

      "mesos container, IP/CT networking (named), w/o ports" in new Fixture {
        val sd = withDeprecatedIpAddress(
          withContainer(_.setType(Mesos.ContainerInfo.Type.MESOS)),
          Option("someNetworkName"), ports = Nil)
        val expected = basicCommandApp.copy(
          container = Some(Container.Mesos()),
          networks = Seq(ContainerNetwork(name = "someNetworkName")),
          portDefinitions = Nil
        )
        migrateSingleApp(sd) should be(expected)
      }

      "mesos container, IP/CT networking (named)" in new Fixture {
        val sd = withDeprecatedIpAddress(
          withContainer(_.setType(Mesos.ContainerInfo.Type.MESOS)),
          Option("someNetworkName"))
        val expected = basicCommandApp.copy(
          container = Some(Container.Mesos(
            portMappings = Seq(Container.PortMapping(containerPort = 80, name = Some("http")))
          )),
          networks = Seq(ContainerNetwork(name = "someNetworkName")),
          portDefinitions = Nil
        )
        migrateSingleApp(sd) should be(expected)
      }

      "docker container, host networking" in new Fixture {
        import Protos.ExtendedContainerInfo.DockerInfo
        val sd = withContainer(
          _.setType(Mesos.ContainerInfo.Type.DOCKER).setDocker(DockerInfo.newBuilder()
            .setImage("image0").setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.HOST)))
        val expected = basicCommandApp.copy(container = Some(Container.Docker(image = "image0")))
        migrateSingleApp(sd) should be(expected)
      }

      "docker container, host networking, w/o ports" in new Fixture {
        import Protos.ExtendedContainerInfo.DockerInfo
        val sd = withContainer(
          _.setType(Mesos.ContainerInfo.Type.DOCKER).setDocker(DockerInfo.newBuilder()
            .setImage("image0").setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.HOST))
        ).toBuilder.clearPortDefinitions().build

        val expected = basicCommandApp.copy(container = Some(Container.Docker(image = "image0")), portDefinitions = Nil)
        migrateSingleApp(sd) should be(expected)
      }

      "docker container, bridge networking" in new Fixture {
        import Protos.ExtendedContainerInfo.DockerInfo
        val sd = withContainer(
          _.setType(Mesos.ContainerInfo.Type.DOCKER).setDocker(DockerInfo.newBuilder()
            .setImage("image0")
            .setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.BRIDGE)
            .addOBSOLETEPortMappings(DockerInfo.ObsoleteDockerPortMapping.newBuilder()
              .setContainerPort(8080)
              .setHostPort(123)
              .setServicePort(456)
              .setName("rtp")
              .setProtocol("udp")
            )
          )).toBuilder.clearPortDefinitions().build
        val expected = basicCommandApp.copy(
          container = Some(Container.Docker(image = "image0", portMappings = Seq(
            Container.PortMapping(
              containerPort = 8080,
              hostPort = Option(123),
              servicePort = 456,
              name = Option("rtp"),
              protocol = "udp"
            )
          ))),
          networks = Seq(BridgeNetwork()),
          portDefinitions = Nil
        )
        migrateSingleApp(sd) should be(expected)
      }

      "docker container, bridge networking, w/o ports" in new Fixture {
        import Protos.ExtendedContainerInfo.DockerInfo
        val sd = withContainer(
          _.setType(Mesos.ContainerInfo.Type.DOCKER).setDocker(DockerInfo.newBuilder()
            .setImage("image0")
            .setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.BRIDGE)
          )).toBuilder.clearPortDefinitions().build

        val expected = basicCommandApp.copy(
          container = Some(Container.Docker(image = "image0")),
          networks = Seq(BridgeNetwork()),
          portDefinitions = Nil
        )
        migrateSingleApp(sd) should be(expected)
      }

      "docker container, user networking (named)" in new Fixture {
        import Protos.ExtendedContainerInfo.DockerInfo
        val sd = withDeprecatedIpAddress(withContainer(
          _.setType(Mesos.ContainerInfo.Type.DOCKER).setDocker(DockerInfo.newBuilder()
            .setImage("image0")
            .setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.USER)
            .addOBSOLETEPortMappings(DockerInfo.ObsoleteDockerPortMapping.newBuilder()
              .setContainerPort(8080)
              .setHostPort(123)
              .setServicePort(456)
              .setName("rtp")
              .setProtocol("udp")
            )
          )), networkName = Some("someNetworkName"), ports = Nil)

        val expected = basicCommandApp.copy(
          container = Some(Container.Docker(image = "image0", portMappings = Seq(
            Container.PortMapping(
              containerPort = 8080,
              hostPort = Option(123),
              servicePort = 456,
              name = Option("rtp"),
              protocol = "udp"
            )
          ))),
          networks = Seq(ContainerNetwork("someNetworkName")),
          portDefinitions = Nil
        )
        migrateSingleApp(sd) should be(expected)
      }

      "docker container, user networking (named), w/o ports" in new Fixture {
        import Protos.ExtendedContainerInfo.DockerInfo
        val sd = withDeprecatedIpAddress(withContainer(
          _.setType(Mesos.ContainerInfo.Type.DOCKER).setDocker(DockerInfo.newBuilder()
            .setImage("image0")
            .setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.USER)
          )), networkName = Some("someNetworkName"), ports = Nil)

        val expected = basicCommandApp.copy(
          container = Some(Container.Docker(image = "image0")),
          networks = Seq(ContainerNetwork("someNetworkName")),
          portDefinitions = Nil
        )
        migrateSingleApp(sd) should be(expected)
      }

      behave like failsWithNetworkNameRequired("docker container, user networking (unnamed)"){ fixture =>
        import Protos.ExtendedContainerInfo.DockerInfo
        fixture.withDeprecatedIpAddress(fixture.withContainer(
          _.setType(Mesos.ContainerInfo.Type.DOCKER).setDocker(DockerInfo.newBuilder()
            .setImage("image0")
            .setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.USER)
          )), ports = Nil)
      }

      "docker container, user networking (unnamed), w/o ports, w/ default network" in new Fixture(Some("defaultNetwork")) {
        import Protos.ExtendedContainerInfo.DockerInfo
        val sd = withDeprecatedIpAddress(withContainer(
          _.setType(Mesos.ContainerInfo.Type.DOCKER).setDocker(DockerInfo.newBuilder()
            .setImage("image0")
            .setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.USER)
          )), ports = Nil)

        val expected = basicCommandApp.copy(
          container = Some(Container.Docker(image = "image0")),
          networks = Seq(ContainerNetwork("defaultNetwork")),
          portDefinitions = Nil
        )
        migrateSingleApp(sd) should be(expected)
      }

      "mesos container, IP/CT networking (unnamed), w/o ports, w/ default network" in new Fixture(Some("defaultNetwork")) {
        val sd = withDeprecatedIpAddress(withContainer(_.setType(Mesos.ContainerInfo.Type.MESOS)), ports = Nil)
        val expected = basicCommandApp.copy(
          container = Some(Container.Mesos()),
          networks = Seq(ContainerNetwork(name = "defaultNetwork")),
          portDefinitions = Nil
        )
        migrateSingleApp(sd) should be(expected)
      }
    }

    // TODO: test w/ special migration envvar for network name
    // TODO: e2e migration test for a root w/ apps
  }

  private class Fixture(val defaultNetworkName: Option[String] = None, val enabledFeatures: Set[String] = Set.empty) {
    val basicCommandService: Protos.ServiceDefinition = Protos.ServiceDefinition.newBuilder()
      .setId("/foo")
      .setCmd(Mesos.CommandInfo.newBuilder().setValue("sleep 60"))
      .setInstances(1)
      .setExecutor("//cmd")
      .addAllResources(Seq(
        ScalarResource.cpus(0.1),
        ScalarResource.memory(64),
        ScalarResource.disk(10),
        ScalarResource.gpus(0),
        RangesResource.ports(Seq(Range(80)))
      ).map(resourceToProto))
      .addPortDefinitions(Mesos.Port.newBuilder().setNumber(80))
      .setVersion(Timestamp(1000L).toString)
      .build

    val basicCommandApp: AppDefinition = AppDefinition(
      id = PathId("/foo"),
      cmd = Option("sleep 60"),
      resources = Resources(cpus = 0.1, mem = 64, disk = 10),
      executor = "//cmd",
      portDefinitions = PortDefinitions(80),
      versionInfo = OnlyVersion(Timestamp(1000L))
    )

    def withContainer(f: Protos.ExtendedContainerInfo.Builder => Protos.ExtendedContainerInfo.Builder): Protos.ServiceDefinition = {
      basicCommandService.toBuilder
        .setContainer(f(Protos.ExtendedContainerInfo.newBuilder()).build)
        .build
    }

    def withDeprecatedIpAddress(sd: Protos.ServiceDefinition, networkName: Option[String] = None, ports: Seq[(Int, String)] = Seq(80 -> "http")) = {
      val ipAddress = Protos.ObsoleteIpAddress.newBuilder()
        .setDiscoveryInfo(Protos.ObsoleteDiscoveryInfo.newBuilder()
          .addAllPorts(ports.map { case (port, name) => Mesos.Port.newBuilder().setNumber(port).setName(name).build })
        )
      networkName.map(ipAddress.setNetworkName)
      sd.toBuilder.setOBSOLETEIpAddress(ipAddress).clearPortDefinitions().build
    }

    lazy implicit val appNormalization: Normalization[raml.App] = AppsResource.appNormalization(
      AppsResource.NormalizationConfig(enabledFeatures, AppNormalization.Configure(defaultNetworkName)))
  }
}
