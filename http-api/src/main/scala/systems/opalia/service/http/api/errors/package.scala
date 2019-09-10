package systems.opalia.service.http.api

import systems.opalia.interfaces.json.JsonAst
import systems.opalia.interfaces.soa.ClientFaultException


package object errors {

  sealed abstract class HttpException(statusCode: Int,
                                      data: ServerErrorData)
    extends ClientFaultException(data.messages.mkString("\n")) {

    def getStatusCode: Int =
      statusCode

    def getHeaders: Map[String, String] =
      data.headers

    def getMessages: Seq[String] =
      data.messages

    def getDetails: JsonAst.JsonValue =
      data.details
  }

  class BadRequestHttpException(data: ServerErrorData)
    extends HttpException(400, data)

  class UnauthorizedHttpException(data: ServerErrorData)
    extends HttpException(401, data)

  class ForbiddenHttpException(data: ServerErrorData)
    extends HttpException(403, data)

  class NotFoundHttpException(data: ServerErrorData)
    extends HttpException(404, data)

  class ConflictHttpException(data: ServerErrorData)
    extends HttpException(409, data)

  class GoneHttpException(data: ServerErrorData)
    extends HttpException(410, data)

  class PreconditionFailedHttpException(data: ServerErrorData)
    extends HttpException(412, data)

  class UnsupportedMediaTypeHttpException(data: ServerErrorData)
    extends HttpException(415, data)

  class UnprocessableEntityHttpException(data: ServerErrorData)
    extends HttpException(422, data)

  class LockedHttpException(data: ServerErrorData)
    extends HttpException(423, data)

  class PreconditionRequiredHttpException(data: ServerErrorData)
    extends HttpException(428, data)

  class TooManyRequestsHttpException(data: ServerErrorData)
    extends HttpException(429, data)

  class UnavailableForLegalReasonsHttpException(data: ServerErrorData)
    extends HttpException(451, data)

}
