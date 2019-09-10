package systems.opalia.service.http.impl.akka

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import systems.opalia.interfaces.logging.LoggingService
import systems.opalia.interfaces.soa.Bootable
import systems.opalia.interfaces.vfs.VfsService
import systems.opalia.service.http.api._


final class HttpServiceBootable(config: BundleConfig,
                                classLoader: ClassLoader,
                                loggingService: LoggingService,
                                vfsService: VfsService)
  extends HttpService
    with Bootable[Unit, Unit] {

  private val logger = loggingService.newLogger(classOf[HttpService].getName)
  private val loggerStats = loggingService.newLogger(s"${classOf[HttpService].getName}-statistics")

  private val actorSystem = ActorSystem("HttpSystem", config.akka, classLoader)
  private implicit val executionContext = actorSystem.dispatcher
  private implicit val materializer = ActorMaterializer()(actorSystem)

  private val assetFs = vfsService.getFileSystem("asset")
  private val cacheFs = vfsService.getFileSystem("cache")

  private val client = new Client(config, logger, loggerStats, actorSystem, assetFs, cacheFs)
  private val router = new Router(config, logger, loggerStats, actorSystem, assetFs, cacheFs)
  private val server = new Server(config, logger, loggerStats, actorSystem, router)

  def call(request: ClientRequest): Future[ClientResponse] = {

    client.call(request)
  }

  def newRoute(path: String,
               method: Method,
               entityRequirement: EntityRequirement,
               fetch: (ServerRequest) => Future[ServerResponse]): Route =
    new RouteImpl(router, path, method, entityRequirement, fetch)

  protected def setupTask(): Unit = {

    Await.result(server.bindingFuture, Duration.Inf)
  }

  protected def shutdownTask(): Unit = {

    Await.result(actorSystem.terminate(), Duration.Inf)
    router.clearRoutes()
  }
}
