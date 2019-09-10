package systems.opalia.service.http.api

import scala.concurrent.Future


trait ServerRequest {

  val uri: String
  val path: String
  val method: Method
  val headers: Map[String, String]
  val arguments: Map[String, String]
  val entityRequirement: EntityRequirement

  def handleEntity(): Future[Entity]
}
