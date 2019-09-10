package systems.opalia.service.http.api

import scala.concurrent.Future


trait HttpService {

  def newRoute(path: String,
               method: Method,
               entityRequirement: EntityRequirement,
               fetch: (ServerRequest) => Future[ServerResponse]): Route

  def call(request: ClientRequest): Future[ClientResponse]
}
