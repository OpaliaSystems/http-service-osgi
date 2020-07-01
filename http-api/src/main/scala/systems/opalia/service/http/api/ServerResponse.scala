package systems.opalia.service.http.api


class ServerResponse(statusCode: Int,
                     headers: Map[String, String],
                     entityTag: Option[EntityTag],
                     entity: Entity) {

  def getStatusCode: Int =
    statusCode

  def getHeaders: Map[String, String] =
    headers

  def getEntityTag: Option[EntityTag] =
    entityTag

  def getEntity: Entity =
    entity
}

object ServerResponse {

  def ok(headers: Map[String, String] = Map.empty,
         entityTag: Option[EntityTag] = None,
         entity: Entity = NoneEntity): ServerResponse = {

    new ServerResponse(200, headers, entityTag, entity)
  }

  def created(headers: Map[String, String] = Map.empty,
              entityTag: Option[EntityTag] = None,
              entity: Entity = NoneEntity): ServerResponse = {

    new ServerResponse(201, headers, entityTag, entity)
  }

  def accepted(headers: Map[String, String] = Map.empty,
               entity: Entity = NoneEntity): ServerResponse = {

    new ServerResponse(202, headers, None, entity)
  }

  def noContent(headers: Map[String, String] = Map.empty): ServerResponse = {

    new ServerResponse(204, headers, None, NoneEntity)
  }

  def found(headers: Map[String, String] = Map.empty,
            location: String): ServerResponse = {

    new ServerResponse(302, headers + ("Location" -> location), None, NoneEntity)
  }

  def notModified(headers: Map[String, String] = Map.empty,
                  entityTag: EntityTag): ServerResponse = {

    new ServerResponse(304, headers, Some(entityTag), NoneEntity)
  }
}
