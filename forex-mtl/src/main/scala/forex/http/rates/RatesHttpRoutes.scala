package forex.http
package rates

import cats.data.Validated.{ Invalid, Valid }
import cats.effect.Sync
import cats.syntax.flatMap._
import forex.programs.RatesProgram
import forex.programs.rates.errors.{ Error => RatesProgramError }
import forex.programs.rates.{ Protocol => RatesProgramProtocol }
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

class RatesHttpRoutes[F[_]: Sync](rates: RatesProgram[F]) extends Http4sDsl[F] {

  import Converters._, QueryParams._, Protocol._

  private[http] val prefixPath = "/rates"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root :? FromQueryParam(from) +& ToQueryParam(to) =>
      (from, to) match {
        case (Valid(validFrom), Valid(validTo)) =>
          rates.get(RatesProgramProtocol.GetRatesRequest(validFrom, validTo)).flatMap {
            case Right(rate)  => Ok(rate.asGetApiResponse)
            case Left(error) => toHttpResponse(error)
          }
        case (Invalid(_), _) | (_, Invalid(_)) =>
          BadRequest(ErrorResponse("invalid_currency", "Unsupported currency"))
      }
    case GET -> Root =>
      BadRequest(ErrorResponse("missing_query_params", "Both 'from' and 'to' query parameters are required"))
  }

  private def toHttpResponse(error: RatesProgramError) =
    error match {
      case RatesProgramError.UnsupportedPair(_) =>
        NotFound(ErrorResponse("unsupported_pair", "Unsupported currency pair"))
      case RatesProgramError.RateLookupFailed(_) =>
        BadGateway(ErrorResponse("provider_unavailable", "Unable to refresh rates from One-Frame"))
      case RatesProgramError.NoFreshRateAvailable(_) =>
        ServiceUnavailable(ErrorResponse("no_fresh_rate", "No fresh rate available"))
    }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
