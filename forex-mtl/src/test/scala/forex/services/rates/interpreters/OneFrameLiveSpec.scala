package forex.services.rates.interpreters

import cats.effect.{ ContextShift, IO }
import cats.effect.concurrent.Ref
import cats.syntax.flatMap._
import forex.domain.{ Currency, Price, Rate, Timestamp }
import org.http4s.client.Client
import org.http4s.{ HttpApp, Request, Response, Status }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.typelevel.ci.CIString

import java.time.OffsetDateTime
import scala.concurrent.ExecutionContext

class OneFrameLiveSpec extends AnyWordSpec with Matchers {

  private implicit val contextShift: ContextShift[IO] =
    IO.contextShift(ExecutionContext.global)

  private val token = "token"
  private val pair  = Rate.Pair(Currency.USD, Currency.JPY)

  "OneFrameLive" should {
    "decode a One-Frame response" in {
      val timestamp = OffsetDateTime.parse("2026-05-13T10:00:00Z")
      val oneFrame = new OneFrameLive[IO](
        client(Status.Ok, responseBody(timestamp)),
        "http://one-frame.test",
        token
      )

      oneFrame.get(List(pair)).unsafeRunSync() shouldBe Right(
        List(Rate(pair, Price(BigDecimal("123.45")), Timestamp(timestamp)))
      )
    }

    "encode repeated pair query params and token header" in {
      val capturedRequest = Ref.of[IO, Option[Request[IO]]](None).unsafeRunSync()
      val oneFrame = new OneFrameLive[IO](
        Client.fromHttpApp(HttpApp[IO] { request =>
          capturedRequest.set(Some(request)) >>
            IO.pure(Response[IO](Status.Ok).withEntity(responseBody(OffsetDateTime.now())))
        }),
        "http://one-frame.test",
        token
      )

      oneFrame
        .get(List(Rate.Pair(Currency.USD, Currency.JPY), Rate.Pair(Currency.USD, Currency.EUR)))
        .unsafeRunSync()
        .isRight shouldBe true

      val request = capturedRequest.get.unsafeRunSync().get
      request.uri.renderString should include("pair=USDJPY")
      request.uri.renderString should include("pair=USDEUR")
      request.headers.headers.exists(header => header.name == CIString("token") && header.value == token) shouldBe true
    }

    "map non-2xx responses to provider errors" in {
      val oneFrame = new OneFrameLive[IO](
        client(Status.InternalServerError, """{"error":"boom"}"""),
        "http://one-frame.test",
        token
      )

      oneFrame.get(List(pair)).unsafeRunSync().isLeft shouldBe true
    }

    "map malformed JSON to provider errors" in {
      val oneFrame = new OneFrameLive[IO](
        client(Status.Ok, "not-json"),
        "http://one-frame.test",
        token
      )

      oneFrame.get(List(pair)).unsafeRunSync().isLeft shouldBe true
    }
  }

  private def client(status: Status, body: String): Client[IO] =
    Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](status).withEntity(body))))

  private def responseBody(timestamp: OffsetDateTime): String =
    s"""[{"from":"USD","to":"JPY","bid":1.0,"ask":2.0,"price":123.45,"time_stamp":"$timestamp"}]"""

}
