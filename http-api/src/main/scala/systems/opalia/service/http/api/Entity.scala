package systems.opalia.service.http.api

import systems.opalia.interfaces.json.JsonAst


sealed trait Entity

sealed trait SimpleEntity
  extends Entity

case object NoneEntity
  extends Entity

case class JsonEntity(body: JsonAst.JsonObject)
  extends SimpleEntity

case class CharacterEntity(contentType: String = "text/plain; charset=UTF-8",
                           body: String)
  extends SimpleEntity

case class BinaryEntity(contentType: String = "application/octet-stream",
                        body: IndexedSeq[Byte])
  extends SimpleEntity

case class AssetEntity(id: IndexedSeq[Byte])
  extends SimpleEntity

case class MultipartEntity(body: Map[String, SimpleEntity])
  extends Entity
