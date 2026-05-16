package forex.services.rates.interpreters

import cats.effect.Sync
import cats.effect.concurrent.{ Ref, Semaphore }
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import forex.domain.Rate
import forex.services.rates.Algebra
import forex.services.rates.errors.Error
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime
import scala.concurrent.duration.FiniteDuration

class OneFrameCached[F[_]: Sync](
    oneFrame: OneFrameLive[F],
    cache: Ref[F, Map[Rate.Pair, Rate]],
    refreshLock: Semaphore[F],
    cacheTtl: FiniteDuration
) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    if (!allPairs.contains(pair)) {
      Sync[F].pure(Error.UnsupportedPair("Unsupported currency pair").asLeft[Rate])
    } else {
      freshCached(pair).flatMap {
        case Some(rate) => Sync[F].pure(rate.asRight[Error])
        case None       => refreshLock.withPermit(refreshAndGet(pair))
      }
    }

  private def refreshAndGet(pair: Rate.Pair): F[Error Either Rate] =
    freshCached(pair).flatMap {
      case Some(rate) => Sync[F].pure(rate.asRight[Error])
      case None =>
        logRefresh >>
        oneFrame.get(allPairs).flatMap {
          case Left(error) => Sync[F].pure(error.asLeft[Rate])
          case Right(rates) =>
            cache.set(rates.map(rate => rate.pair -> rate).toMap) >>
              freshCached(pair).map {
                case Some(rate) => rate.asRight[Error]
                case None       => Error.NoFreshRateAvailable("No fresh rate available").asLeft[Rate]
              }
        }
    }

  private def freshCached(pair: Rate.Pair): F[Option[Rate]] =
    for {
      currentTime <- now
      rates       <- cache.get
    } yield {
      rates.get(pair).filter(rate => isFresh(rate, currentTime))
    }

  private def now: F[OffsetDateTime] =
    Sync[F].delay(OffsetDateTime.now())

  private def isFresh(rate: Rate, currentTime: OffsetDateTime): Boolean =
    rate.timestamp.value.isAfter(currentTime.minusNanos(cacheTtl.toNanos))

  private val allPairs: List[Rate.Pair] =
    Rate.Pair.all

  private def logRefresh: F[Unit] =
    Sync[F].delay(OneFrameCached.logger.info("Refreshing all rates from One-Frame"))

}

object OneFrameCached {
  private val logger = LoggerFactory.getLogger("forex.services.rates.interpreters.OneFrameCached")
}
