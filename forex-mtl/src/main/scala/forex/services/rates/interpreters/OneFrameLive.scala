package forex.services.rates.interpreters

import cats.effect.Concurrent
import cats.syntax.applicativeError._
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._
import forex.config.OneFrameConfig
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.errors.Error
import io.circe.Decoder
import org.http4s.{ Header, Method, Query, Request, Uri }
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.typelevel.ci.CIString

import java.time.{ LocalDateTime, OffsetDateTime, ZoneOffset }
import scala.util.Try

import OneFrameLive.OneFrameRate

class OneFrameLive[F[_]: Concurrent](
    client: Client[F],
    baseUrl: String,
    token: String
) {

  def get(pairs: List[Rate.Pair]): F[Error Either List[Rate]] =
    if (pairs.isEmpty) {
      List.empty[Rate].asRight[Error].pure[F]
    } else {
      request(pairs).fold(
        error => error.asLeft[List[Rate]].pure[F],
        req =>
          client
            .expect[List[OneFrameRate]](req)
            .map(_.map(_.toDomain).asRight[Error])
            .handleError(error => Error.OneFrameLookupFailed(error.getMessage).asLeft[List[Rate]])
      )
    }

  private def request(pairs: List[Rate.Pair]): Error Either Request[F] =
    Uri
      .fromString(s"${baseUrl.stripSuffix("/")}/rates")
      .leftMap(error => Error.OneFrameLookupFailed(s"Invalid One-Frame base URL: ${error.message}"))
      .map { uri =>
        val query = Query.fromPairs(pairs.map(pair => "pair" -> pairCode(pair)): _*)

        Request[F](Method.GET, uri.copy(query = query))
          .putHeaders(Header.Raw(CIString("token"), token))
      }

  private def pairCode(pair: Rate.Pair): String =
    Currency.show.show(pair.from) + Currency.show.show(pair.to)

  private implicit val currencyDecoder: Decoder[Currency] =
    Decoder.decodeString.emap { value =>
      Currency.fromString(value).toRight(s"Unsupported currency: $value")
    }

  private implicit val timestampDecoder: Decoder[OffsetDateTime] =
    Decoder.decodeString.emap(parseTimestamp)

  private implicit val oneFrameRateDecoder: Decoder[OneFrameRate] =
    Decoder.instance { cursor =>
      for {
        from      <- cursor.downField("from").as[Currency]
        to        <- cursor.downField("to").as[Currency]
        price     <- cursor.downField("price").as[BigDecimal]
        timestamp <- cursor.downField("time_stamp").as[OffsetDateTime]
      } yield OneFrameRate(from, to, price, timestamp)
    }

  private def parseTimestamp(value: String): Either[String, OffsetDateTime] =
    Try(OffsetDateTime.parse(value))
      .orElse(Try(LocalDateTime.parse(value).atOffset(ZoneOffset.UTC)))
      .toEither
      .leftMap(_ => s"Invalid timestamp: $value")

}

object OneFrameLive {
  def apply[F[_]: Concurrent](client: Client[F], config: OneFrameConfig): OneFrameLive[F] =
    new OneFrameLive[F](client, config.baseUrl, config.token)

  private final case class OneFrameRate(
      from: Currency,
      to: Currency,
      price: BigDecimal,
      timestamp: OffsetDateTime
  ) {
    def toDomain: Rate =
      Rate(Rate.Pair(from, to), Price(price), Timestamp(timestamp))
  }
}
