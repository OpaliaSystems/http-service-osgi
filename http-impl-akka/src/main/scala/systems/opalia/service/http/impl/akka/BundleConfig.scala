package systems.opalia.service.http.impl.akka

import com.typesafe.config.{Config, ConfigFactory}
import java.nio.file.Path
import systems.opalia.commons.configuration.ConfigHelper._
import systems.opalia.commons.configuration.Reader._
import systems.opalia.commons.net.EndpointAddress
import systems.opalia.interfaces.logging.LogLevel


final class BundleConfig(config: Config) {

  val appName: String = getClass.getPackage.getImplementationTitle
  val appVersion: String = getClass.getPackage.getImplementationVersion

  val logLevel: LogLevel = LogLevel.min(config.as[LogLevel]("log.level"), LogLevel.INFO)

  val akka: Config =
    ConfigFactory.parseString(s"akka.loglevel = $logLevel")
      .withFallback(ConfigFactory.parseString(s"akka.http.server.server-header = $appName/$appVersion"))
      .withFallback(ConfigFactory.parseString(s"akka.http.client.user-agent-header = $appName/$appVersion"))
      .withFallback(config.as[Config]("http").withOnlyPath("akka"))

  object Ssl {

    val enabled: Boolean = config.as[Boolean]("http.ssl.enabled")

    lazy val keyStoreName: String = config.as[String]("http.ssl.key-store-name")
    lazy val keyStorePath: Path = config.as[Path]("http.ssl.key-store-path").normalize
    lazy val keyStorePassword: String = config.as[String]("http.ssl.key-store-password")
  }

  val server: EndpointAddress = config.as[EndpointAddress]("http.server")
}
