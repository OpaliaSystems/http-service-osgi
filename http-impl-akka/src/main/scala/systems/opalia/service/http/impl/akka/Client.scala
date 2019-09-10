package systems.opalia.service.http.impl.akka

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import scala.concurrent.{ExecutionContext, Future}
import systems.opalia.interfaces.logging.Logger
import systems.opalia.interfaces.vfs.FileSystem
import systems.opalia.service.http.api._


final class Client(config: BundleConfig,
                   logger: Logger,
                   loggerStats: Logger,
                   actorSystem: ActorSystem,
                   assetFs: FileSystem,
                   cacheFs: FileSystem)
                  (implicit executionContext: ExecutionContext,
                   materializer: ActorMaterializer)
  extends HttpHandling {

  def call(request: ClientRequest): Future[ClientResponse] = {

    Future {

      convertFromApiEntity(assetFs, cacheFs, request.entity, identity)
        .flatMap {
          case (httpEntity, checksum) =>

            Http()(actorSystem).singleRequest(HttpRequest(
              method = convertFromApiMethod(request.method),
              uri = Uri(request.uri),
              headers = convertFromApiHeaders(request.headers.toSeq, None, checksum),
              entity = httpEntity))
        }
    }
      .flatMap(identity)
      .map {
        response =>

          new ClientResponse {

            val statusCode: Int = response.status.intValue()
            val headers: Seq[(String, String)] = convertToApiHeadersClient(response.headers)

            def statusReason: String =
              response.status.reason()

            def handleEntity(entityRequirement: EntityRequirement): Future[Entity] =
              convertToApiEntity(assetFs, cacheFs, response, request.uri, entityRequirement)
          }
      }
  }
}
