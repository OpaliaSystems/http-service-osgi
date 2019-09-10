package systems.opalia.service.http.api


sealed trait ServerResponse {

  val headers: Map[String, String]
}

case class OkServerResponse(headers: Map[String, String] = Map.empty,
                            entityTag: Option[EntityTag] = None,
                            entity: Entity = NoneEntity)
  extends ServerResponse

case class CreatedServerResponse(headers: Map[String, String] = Map.empty,
                                 entityTag: Option[EntityTag] = None,
                                 entity: Entity = NoneEntity)
  extends ServerResponse

case class AcceptedServerResponse(headers: Map[String, String] = Map.empty,
                                  entity: Entity = NoneEntity)
  extends ServerResponse

case class NoContentServerResponse(headers: Map[String, String] = Map.empty)
  extends ServerResponse

case class FoundServerResponse(headers: Map[String, String] = Map.empty,
                               location: String)
  extends ServerResponse

case class NotModifiedServerResponse(headers: Map[String, String] = Map.empty,
                                     entityTag: EntityTag)
  extends ServerResponse
