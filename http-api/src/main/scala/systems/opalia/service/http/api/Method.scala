package systems.opalia.service.http.api


sealed class Method(name: String) {

  override def equals(that: Any): Boolean =
    that match {

      case that: Method if (this.toString == that.toString) => true
      case _ => false
    }

  override def toString: String =
    name

  override def hashCode: Int =
    name.hashCode
}

object Method {

  val head: Method =
    new Method("HEAD")

  val get: Method =
    new Method("GET")

  val post: Method =
    new Method("POST")

  val put: Method =
    new Method("PUT")

  val patch: Method =
    new Method("PATCH")

  val delete: Method =
    new Method("DELETE")

  def apply(name: String): Method =
    new Method(name.toUpperCase)
}
