package com.github.ingarabr.gcslock

import cats.effect.*
import cats.syntax.all.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

private[gcslock] def offsetDateTimeUtc[F[_]: Clock] =
  Clock[F].applicative.map(Clock[F].realTime)(d =>
    OffsetDateTime.from(Instant.ofEpochMilli(d.toMillis).atZone(ZoneOffset.UTC))
  )

private[gcslock] def runRetryF[F[_]: Async: Concurrent, A](
    fa: F[Option[A]],
    delay: FiniteDuration,
    retryLeft: Int
): F[A] =
  fa.flatMap {
    case Some(value) => value.pure[F]
    case None =>
      if (retryLeft - 1 == 0)
        new TimeoutException("Reached max number of attempts").raiseError[F, A]
      else Async[F].delayBy(runRetryF(fa, delay, retryLeft - 1), delay)
  }
