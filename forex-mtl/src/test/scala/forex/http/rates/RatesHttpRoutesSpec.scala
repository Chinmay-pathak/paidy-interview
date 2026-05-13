package forex.http.rates

import cats.effect.IO
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.programs.RatesProgram
import forex.programs.rates.Protocol.GetRatesRequest
import forex.programs.rates.errors.Error
import org.http4s.implicits._
import org.http4s.{ Request, Status, Uri }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.OffsetDateTime

class RatesHttpRoutesSpec extends AnyWordSpec with Matchers {

  "RatesHttpRoutes" should {
    "return 200 for a valid rates request" in {
      val rate = Rate(
        Rate.Pair(Currency.USD, Currency.JPY),
        Price(BigDecimal("123.45")),
        Timestamp(OffsetDateTime.now())
      )
      val routes = new RatesHttpRoutes[IO](program(Right(rate))).routes.orNotFound

      val response = routes.run(request(uri"/rates?from=USD&to=JPY")).unsafeRunSync()

      response.status shouldBe Status.Ok
    }

    "return 400 for an invalid currency" in {
      val routes = new RatesHttpRoutes[IO](program(Left(Error.RateLookupFailed("should not be called")))).routes.orNotFound

      val response = routes.run(request(uri"/rates?from=XXX&to=JPY")).unsafeRunSync()
      val body     = response.as[String].unsafeRunSync()

      response.status shouldBe Status.BadRequest
      body should include("invalid_currency")
    }

    "return 502 for provider failures" in {
      val routes = new RatesHttpRoutes[IO](program(Left(Error.RateLookupFailed("boom")))).routes.orNotFound

      val response = routes.run(request(uri"/rates?from=USD&to=JPY")).unsafeRunSync()
      val body     = response.as[String].unsafeRunSync()

      response.status shouldBe Status.BadGateway
      body should include("provider_unavailable")
    }
  }

  private def program(result: Error Either Rate): RatesProgram[IO] =
    new RatesProgram[IO] {
      override def get(request: GetRatesRequest): IO[Error Either Rate] =
        IO.pure(result)
    }

  private def request(uri: Uri): Request[IO] =
    Request[IO](uri = uri)

}

