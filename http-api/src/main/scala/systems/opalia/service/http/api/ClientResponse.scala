package systems.opalia.service.http.api

import scala.concurrent.Future


trait ClientResponse {

  val statusCode: Int
  val headers: Seq[(String, String)]

  def statusReason: String

  def handleEntity(entityRequirement: EntityRequirement = EntityRequirement.None): Future[Entity]
}
