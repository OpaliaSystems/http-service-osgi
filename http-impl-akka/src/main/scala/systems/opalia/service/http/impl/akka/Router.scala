package systems.opalia.service.http.impl.akka

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import java.util.concurrent.CopyOnWriteArrayList
import scala.collection.immutable.ListMap
import scala.concurrent._
import systems.opalia.commons.net.PathMatching
import systems.opalia.interfaces.json.JsonAst
import systems.opalia.interfaces.logging.Logger
import systems.opalia.interfaces.vfs.FileSystem
import systems.opalia.service.http.api._
import systems.opalia.service.http.api.errors._


final class Router(config: BundleConfig,
                   logger: Logger,
                   loggerStats: Logger,
                   actorSystem: ActorSystem,
                   assetFs: FileSystem,
                   cacheFs: FileSystem)
                  (implicit executionContext: ExecutionContext,
                   materializer: ActorMaterializer)
  extends HttpHandling {

  private val routes =
    new CopyOnWriteArrayList[RouteImpl]()

  def addRoute(route: RouteImpl): Unit =
    synchronized {

      if (routes.stream.filter(x =>
        x.parameterPath.collides(route.parameterPath) && x.method == route.method
      ).count > 0)
        throw new IllegalArgumentException(s"The route “${route.parameterPath}” collides with other route.")

      convertFromApiMethod(route.method)

      routes.add(route)
    }

  def removeRoute(route: RouteImpl): Unit =
    synchronized {

      if (!routes.contains(route))
        throw new IllegalArgumentException(s"Route with path “${route.parameterPath}” is not registered.")

      routes.remove(route)
    }

  def clearRoutes(): Unit =
    synchronized {

      routes.clear()
    }

  def apply(request: HttpRequest): Future[HttpResponse] = {

    val response =
      Future {

        val argumentPath = PathMatching.ArgumentPath(request.uri.path.toString)

        Option(
          routes.stream.filter {
            route =>

              route.method == Method(request.method.value) && route.parameterPath.matches(argumentPath)
          }
            .findFirst.orElse(null)
        )
          .map {
            route =>

              route.apply(new ServerRequest {

                val uri: String = request.uri.toString
                val path: String = route.path
                val method: Method = route.method
                val headers: Map[String, String] = convertToApiHeadersServer(request.headers)
                val arguments: Map[String, String] = route.parameterPath.arguments(argumentPath)
                val entityRequirement: EntityRequirement = route.entityRequirement

                def handleEntity(): Future[Entity] =
                  convertToApiEntity(assetFs, cacheFs, request, uri, route.entityRequirement)
              })
          }
          .getOrElse {

            request.discardEntityBytes() // important to drain incoming HTTP entity stream

            // 404 for unknown actions
            Future.failed(new NotFoundHttpException(ServerErrorData(messages = List(
              "Route to endpoint not found.",
              s"Could not find endpoint: ${request.method.value} ${request.uri.path}"
            ))))
          }
      }
        .flatMap(identity)

    response
      .flatMap(transformResponse)
      .recover {
        case e: Exception =>
          handleErrors(e)
      }
  }

  private def transformResponse(response: ServerResponse): Future[HttpResponse] = {

    response match {

      case OkServerResponse(headers, entityTag, entity) => {

        convertFromApiEntity(assetFs, cacheFs, entity, identity)
          .map {
            case (httpEntity, checksum) =>

              HttpResponse(
                status = StatusCodes.OK,
                headers = convertFromApiHeaders(headers.toSeq, entityTag, checksum),
                entity = httpEntity
              )
          }
      }

      case CreatedServerResponse(headers, entityTag, entity) => {

        convertFromApiEntity(assetFs, cacheFs, entity, identity)
          .map {
            case (httpEntity, checksum) =>

              HttpResponse(
                status = StatusCodes.Created,
                headers = convertFromApiHeaders(headers.toSeq, entityTag, checksum),
                entity = httpEntity
              )
          }
      }

      case AcceptedServerResponse(headers, entity) => {

        convertFromApiEntity(assetFs, cacheFs, entity, identity)
          .map {
            case (httpEntity, checksum) =>

              HttpResponse(
                status = StatusCodes.Accepted,
                headers = convertFromApiHeaders(headers.toSeq, None, checksum),
                entity = httpEntity
              )
          }
      }

      case NoContentServerResponse(headers) => {

        Future.successful(HttpResponse(
          status = StatusCodes.NoContent,
          headers = convertFromApiHeaders(headers.toSeq, None, None)
        ))
      }

      case FoundServerResponse(headers, location) => {

        Future.successful(HttpResponse(
          status = StatusCodes.Found,
          headers = convertFromApiHeaders(headers.toSeq :+ ("Location" -> location), None, None)
        ))
      }

      case NotModifiedServerResponse(headers, entityTag) => {

        Future.successful(HttpResponse(
          status = StatusCodes.NotModified,
          headers = convertFromApiHeaders(headers.toSeq, Some(entityTag), None)
        ))
      }
    }
  }

  private def handleErrors(e: Exception): HttpResponse =
    e match {

      case e: HttpException => {

        logger.debug(s"An client error (${e.getClass.getName}) caught during execution.", e)

        val status = StatusCode.int2StatusCode(e.getStatusCode)

        val entity =
          JsonEntity(createErrorBody(
            status.intValue,
            e.getClass.getSimpleName,
            status.reason :: e.getMessages.toList,
            e.getDetails
          ))

        val (httpEntity, checksum) = convertFromApiSimpleEntity(assetFs, entity, identity)

        HttpResponse(
          status = status,
          headers = convertFromApiHeaders(e.getHeaders.toSeq, None, Some(checksum)),
          entity = httpEntity)
      }

      case e: TimeoutException =>

        logger.warning(s"An server timeout error (${e.getClass.getName}) caught during execution.", e)
        loggerStats.warning("An execution timeout occurred.")

        val status = StatusCodes.ServiceUnavailable

        val entity =
          JsonEntity(createErrorBody(
            status.intValue,
            e.getClass.getSimpleName,
            status.reason :: status.defaultMessage :: Nil,
            JsonAst.JsonNull
          ))

        val (httpEntity, checksum) = convertFromApiSimpleEntity(assetFs, entity, identity)

        HttpResponse(
          status = status,
          headers = convertFromApiHeaders(Seq.empty, None, Some(checksum)),
          entity = httpEntity
        )

      case e =>

        logger.error(s"An server error (${e.getClass.getName}) caught during execution.", e)

        val status = StatusCodes.InternalServerError

        val entity =
          JsonEntity(createErrorBody(
            status.intValue,
            "InternalServerError",
            status.reason :: status.defaultMessage :: Nil,
            JsonAst.JsonNull
          ))

        val (httpEntity, checksum) = convertFromApiSimpleEntity(assetFs, entity, identity)

        HttpResponse(
          status = status,
          headers = convertFromApiHeaders(Seq.empty, None, Some(checksum)),
          entity = httpEntity)
    }

  private def createErrorBody(statusCode: Int,
                              name: String,
                              messages: Seq[String],
                              details: JsonAst.JsonValue): JsonAst.JsonObject = {

    JsonAst.JsonObject(ListMap(
      "code" -> JsonAst.JsonNumberInt(statusCode),
      "error" -> JsonAst.JsonObject(ListMap(
        "name" -> JsonAst.JsonString(name),
        "messages" -> JsonAst.JsonArray(messages.toIndexedSeq.map(JsonAst.JsonString)),
        "details" -> details
      ))
    ))
  }
}
