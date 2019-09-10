package systems.opalia.service.http.api

import scala.concurrent.Future


trait Route {

  val path: String
  val method: Method
  val entityRequirement: EntityRequirement

  def apply(request: ServerRequest): Future[ServerResponse]

  def register(): Unit

  def unregister(): Unit
}
