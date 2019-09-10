package systems.opalia.service.http.impl.akka

import scala.concurrent.Future
import systems.opalia.commons.net.PathMatching
import systems.opalia.service.http.api._


class RouteImpl(router: Router,
                val path: String,
                val method: Method,
                val entityRequirement: EntityRequirement,
                fetch: (ServerRequest) => Future[ServerResponse])
  extends Route {

  val parameterPath = PathMatching.ParameterPath(path)

  def apply(request: ServerRequest): Future[ServerResponse] =
    fetch(request)

  def register(): Unit =
    router.addRoute(this)

  def unregister(): Unit =
    router.removeRoute(this)
}
