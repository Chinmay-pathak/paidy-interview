package forex.services.rates.interpreters

import cats.effect.{ ContextShift, IO }
import cats.effect.concurrent.{ Ref, Semaphore }
import cats.implicits._
import forex.domain.{ Currency, Price, Rate, Timestamp }
import org.http4s.client.Client
import org.http4s.{ HttpApp, Response, Status }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.OffsetDateTime
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class OneFrameCachedSpec extends AnyWordSpec with Matchers {

  private implicit val contextShift: ContextShift[IO] =
    IO.contextShift(ExecutionContext.global)

  private val pair = Rate.Pair(Currency.USD, Currency.JPY)

  "OneFrameCached" should {
    "return a fresh cached rate without calling One-Frame" in {
      val rate    = Rate(pair, Price(BigDecimal("100.00")), Timestamp(OffsetDateTime.now()))
      val service = cachedService(Map(pair -> rate), failingOneFrame, 5.minutes).unsafeRunSync()

      service.get(pair).unsafeRunSync() shouldBe Right(rate)
    }

    "refresh all pairs when the requested rate is missing" in {
      val callCount = Ref.of[IO, Int](0).unsafeRunSync()
      val timestamp = OffsetDateTime.now().plusMinutes(1).withNano(0)
      val service   = cachedService(Map.empty, oneFrame(callCount, Status.Ok, responseBody(timestamp)), 5.minutes).unsafeRunSync()

      service.get(pair).unsafeRunSync() shouldBe Right(
        Rate(pair, Price(BigDecimal("123.45")), Timestamp(timestamp))
      )
      callCount.get.unsafeRunSync() shouldBe 1
    }

    "not serve stale cached rates when refresh fails" in {
      val staleRate = Rate(pair, Price(BigDecimal("100.00")), Timestamp(OffsetDateTime.now().minusMinutes(10)))
      val service   = cachedService(Map(pair -> staleRate), oneFrame(Status.InternalServerError), 5.minutes).unsafeRunSync()

      service.get(pair).unsafeRunSync().isLeft shouldBe true
    }

    "refresh only once for concurrent stale requests" in {
      val callCount = Ref.of[IO, Int](0).unsafeRunSync()
      val service   = cachedService(Map.empty, oneFrame(callCount, Status.Ok, responseBody(OffsetDateTime.now().plusMinutes(1))), 5.minutes).unsafeRunSync()

      List.fill(20)(service.get(pair)).parSequence.unsafeRunSync().foreach { result =>
        result.isRight shouldBe true
      }
      callCount.get.unsafeRunSync() shouldBe 1
    }
  }

  private def cachedService(
      initialCache: Map[Rate.Pair, Rate],
      oneFrame: OneFrameLive[IO],
      cacheTtl: FiniteDuration
  ): IO[OneFrameCached[IO]] =
    for {
      cache       <- Ref.of[IO, Map[Rate.Pair, Rate]](initialCache)
      refreshLock <- Semaphore[IO](1L)
    } yield new OneFrameCached[IO](oneFrame, cache, refreshLock, cacheTtl)

  private def failingOneFrame: OneFrameLive[IO] =
    oneFrame(Status.InternalServerError)

  private def oneFrame(status: Status): OneFrameLive[IO] =
    new OneFrameLive[IO](
      Client.fromHttpApp(HttpApp[IO](_ => IO.pure(Response[IO](status).withEntity("error")))),
      "http://one-frame.test",
      "token"
    )

  private def oneFrame(callCount: Ref[IO, Int], status: Status, body: String): OneFrameLive[IO] =
    new OneFrameLive[IO](
      Client.fromHttpApp(HttpApp[IO](_ => callCount.update(_ + 1) *> IO.pure(Response[IO](status).withEntity(body)))),
      "http://one-frame.test",
      "token"
    )

  private def responseBody(timestamp: OffsetDateTime): String =
    s"""[{"from":"USD","to":"JPY","bid":1.0,"ask":2.0,"price":123.45,"time_stamp":"$timestamp"}]"""

}
