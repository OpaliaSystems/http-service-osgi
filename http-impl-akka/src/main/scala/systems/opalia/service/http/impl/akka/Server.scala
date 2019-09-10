package systems.opalia.service.http.impl.akka

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import java.nio.file.Files
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.concurrent.{ExecutionContext, Future}
import systems.opalia.commons.io.FileUtils
import systems.opalia.interfaces.logging.Logger


final class Server(config: BundleConfig,
                   logger: Logger,
                   loggerStats: Logger,
                   actorSystem: ActorSystem,
                   router: Router)
                  (implicit executionContext: ExecutionContext,
                   materializer: ActorMaterializer) {

  private val serverSource =
    Http()(actorSystem).bind(config.server.hostString, config.server.port, createConnectionContext())

  val bindingFuture: Future[Http.ServerBinding] =
    serverSource.to(Sink.foreach {
      connection =>

        logger.info(s"Accepted new connection from ${connection.remoteAddress}.")

        connection handleWithAsyncHandler router.apply

    }).run()

  private def createConnectionContext() = {

    if (config.Ssl.enabled) {

      val keyStore =
        Option(KeyStore.getInstance(config.Ssl.keyStoreName))
          .getOrElse(throw new IllegalArgumentException(s"Cannot find key store ${config.Ssl.keyStoreName}."))

      FileUtils.using(Files.newInputStream(config.Ssl.keyStorePath)) {
        inputStream =>

          keyStore.load(inputStream, config.Ssl.keyStorePassword.toCharArray)
      }

      val kmf = KeyManagerFactory.getInstance("SunX509")

      kmf.init(keyStore, config.Ssl.keyStorePassword.toCharArray)

      val tmf = TrustManagerFactory.getInstance("SunX509")

      tmf.init(keyStore)

      val sslContext = SSLContext.getInstance("TLS")

      sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, new SecureRandom())

      ConnectionContext.https(sslContext)

    } else
      Http()(actorSystem).defaultServerHttpContext
  }
}
