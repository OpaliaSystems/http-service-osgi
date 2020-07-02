package systems.opalia.service.http.api


sealed trait EntityRequirement

object EntityRequirement {

  case object None
    extends EntityRequirement

  case object Stream
    extends EntityRequirement

  case object Json
    extends EntityRequirement

  case object Characters
    extends EntityRequirement

  case object Binary
    extends EntityRequirement

  case object Asset
    extends EntityRequirement

  case object Multipart
    extends EntityRequirement

}
