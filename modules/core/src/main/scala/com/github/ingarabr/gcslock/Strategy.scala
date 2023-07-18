package com.github.ingarabr.gcslock

import cats.syntax.all.*
import cats.effect.*
import cats.*

import scala.concurrent.duration.*

/** The [[Strategy]] used to create and refresh the lock. The gcs-lock library provides two options.
  *
  *   - A no dependency default strategy, [[NoDependencyStrategy]]. It's a naive implementation and
  *     should only be used when you have few instances accessing the lock.
  *   - Using cats-retry [[CatsRetryStrategy]]. It gives you better and more fine grained control
  *     over the strategy. This is the preferred approach since it by default adds jitter to the
  *     retry strategy.
  */
case class Strategy[F[_]](
    attemptAcquire: F[Option[LockMeta]] => F[LockMeta],
    attemptRefresh: F[Either[Throwable, RefreshStatus]] => F[RefreshStatus],
    refreshInterval: FiniteDuration,
    timeToLive: FiniteDuration
)

object Strategy {
  val defaultLockRefreshInterval: FiniteDuration = 37.seconds
  val defaultLockTimeToLive: FiniteDuration = 5.minutes
  val defaultAcquireRetryInterval: FiniteDuration = 1.minutes
}

object NoDependencyStrategy {

  def waitUntilLockIsAvailable[F[_]: Async: Concurrent](
      acquireRetryMaxAttempts: Int = 100,
      acquireRetryInterval: FiniteDuration = Strategy.defaultAcquireRetryInterval,
      lockRefreshInterval: FiniteDuration = Strategy.defaultLockRefreshInterval,
      lockTimeToLive: FiniteDuration = Strategy.defaultLockTimeToLive
  ): Strategy[F] =
    Strategy[F](
      fa =>
        runRetry(
          fa.map(_.toRight(new IllegalStateException("Lock not acquired"))),
          acquireRetryInterval,
          acquireRetryMaxAttempts
        ),
      fa => runRetry(fa, acquireRetryInterval, 2),
      lockRefreshInterval,
      lockTimeToLive
    )

  def acquireOnlyIfAvailable[F[_]: Async](
      acquireRetryInterval: FiniteDuration = Strategy.defaultAcquireRetryInterval,
      lockRefreshInterval: FiniteDuration = Strategy.defaultLockRefreshInterval,
      lockTimeToLive: FiniteDuration = Strategy.defaultLockTimeToLive
  ): Strategy[F] =
    Strategy[F](
      { _.flatMap(_.liftTo(new IllegalStateException("Lock already acquired"))) },
      fa => runRetry(fa, acquireRetryInterval, 2),
      lockRefreshInterval,
      lockTimeToLive
    )
}
