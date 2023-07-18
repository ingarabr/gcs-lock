package com.github.ingarabr.gcslock

import cats.Applicative
import cats.effect.kernel.Sync
import cats.syntax.all.*
import retry.*

import scala.concurrent.duration.FiniteDuration

object CatsRetryStrategy {

  def strategy[F[_]: Sync: Sleep](
      acquirePolicy: RetryPolicy[F],
      refreshPolicy: RetryPolicy[F],
      onError: (Throwable, RetryDetails) => F[Unit],
      lockRefreshInterval: FiniteDuration = Strategy.defaultLockRefreshInterval,
      lockTimeToLive: FiniteDuration = Strategy.defaultLockTimeToLive
  ): Strategy[F] = {
    Strategy[F](
      { fa =>
        retryingOnAllErrors(acquirePolicy, onError)(
          fa.flatMap(_.liftTo(new IllegalStateException("Lock already acquired")))
        )
      },
      { fa => retryingOnAllErrors(refreshPolicy, onError)(fa.rethrow) },
      lockRefreshInterval,
      lockTimeToLive
    )
  }

  private def defaultJitter[F[_]: Applicative] =
    RetryPolicies.fullJitter(Strategy.defaultAcquireRetryInterval)

  def waitUntilLockIsAvailable[F[_]: Sync: Sleep](acquireRetryMaxAttempts: Int = 100): Strategy[F] =
    strategy[F](
      RetryPolicies.limitRetries(acquireRetryMaxAttempts) |+| defaultJitter,
      RetryPolicies.limitRetries(2) |+| defaultJitter,
      { (_, _) => Sync[F].unit }
    )

  def acquireOnlyIfAvailable[F[_]: Sync: Sleep]: Strategy[F] =
    strategy[F](
      RetryPolicies.alwaysGiveUp,
      RetryPolicies.limitRetries(2) |+| defaultJitter,
      { (_, _) => Sync[F].unit }
    )

}
