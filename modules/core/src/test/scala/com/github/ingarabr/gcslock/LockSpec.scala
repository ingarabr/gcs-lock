package com.github.ingarabr.gcslock

import cats.Applicative
import cats.syntax.all.*
import cats.effect.*
import munit.CatsEffectSuite

import java.time.{Instant, LocalTime, OffsetDateTime, ZoneId, ZoneOffset}
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.math.BigDecimal.int2bigDecimal

class LockSpec extends CatsEffectSuite {

  private def clockStub(offsetDateTime: OffsetDateTime) = new Clock[IO] {
    private val durMillis = FiniteDuration(offsetDateTime.toInstant.toEpochMilli, MILLISECONDS)
    override def applicative: Applicative[IO] = Applicative[IO]
    override def monotonic: IO[FiniteDuration] = IO.pure(durMillis)
    override def realTime: IO[FiniteDuration] = IO.pure(durMillis)
  }

  private val t1 = OffsetDateTime.of(2020, 11, 28, 14, 48, 0, 0, ZoneOffset.UTC)
  private val l = LockMeta(LockId("bucket", "path"), t1, 123)

  test("valid ttl lock - same time") {
    assertIO(l.validTTL(clockStub(t1)), true, "same time")
  }

  test("valid ttl lock - before ttl") {
    assertIO(l.validTTL(clockStub(t1.minusSeconds(1))), true, "before ttl")
  }

  test("not valid ttl lock - after ttl") {
    assertIO(l.validTTL(clockStub(t1.plusSeconds(1))), false, "after ttl")
  }

}
