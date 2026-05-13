package forex.services.rates

import cats.Applicative
import cats.effect.Concurrent
import cats.effect.concurrent.{ Ref, Semaphore }
import cats.syntax.flatMap._
import cats.syntax.functor._
import forex.config.{ OneFrameConfig, RatesConfig }
import forex.domain.Rate
import interpreters._
import org.http4s.client.Client

object Interpreters {
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()

  def live[F[_]: Concurrent](
      client: Client[F],
      oneFrameConfig: OneFrameConfig,
      ratesConfig: RatesConfig
  ): F[Algebra[F]] =
    for {
      cache       <- Ref.of[F, Map[Rate.Pair, Rate]](Map.empty)
      refreshLock <- Semaphore[F](1L)
    } yield new OneFrameCached[F](
      OneFrameLive[F](client, oneFrameConfig),
      cache,
      refreshLock,
      ratesConfig.cacheTtl
    )
}
