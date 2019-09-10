package systems.opalia.service.http.api


case class ClientRequest(uri: String,
                         method: Method,
                         headers: Map[String, String] = Map.empty,
                         entity: Entity = NoneEntity)
