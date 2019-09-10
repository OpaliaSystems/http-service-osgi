package systems.opalia.service.http.api

import systems.opalia.interfaces.json.JsonAst


case class ServerErrorData(headers: Map[String, String] = Map.empty,
                           messages: Seq[String] = Nil,
                           details: JsonAst.JsonValue = JsonAst.JsonNull)
