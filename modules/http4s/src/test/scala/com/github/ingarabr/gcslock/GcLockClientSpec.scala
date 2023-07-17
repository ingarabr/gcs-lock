package com.github.ingarabr.gcslock

import cats.Applicative
import cats.syntax.all.*
import cats.effect.*
import org.http4s.*
import org.http4s.implicits.*
import munit.CatsEffectSuite
import org.http4s.ember.client.EmberClientBuilder

import java.time.{Instant, LocalTime, OffsetDateTime, ZoneId, ZoneOffset}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.math.BigDecimal.int2bigDecimal

abstract class GcLockClientSpec(cloudLock: Resource[IO, GcsLockClient[IO]])
    extends CatsEffectSuite {

  private val now = LocalTime.now(ZoneId.of("Europe/Oslo"))

  test("create and delete a lock") {
    val lockId = LockId("ingarabr-lock-test", s"test/create-and-get-$now")
    val value = cloudLock.use(l =>
      for {
        create <- l.acquireLock(lockId, 1.minute).map(_.map(_.id))
        _ <- IO.sleep(1.seconds)
        delete <- l.clearLock(lockId)
        gone <- l.getLock(lockId)
      } yield (create, delete, gone.isEmpty)
    )
    assertIO(value, (Option(lockId), true, true))
  }

  test("create and release a lock") {
    val lockId = LockId("ingarabr-lock-test", s"test/create-and-get-$now")
    val value = cloudLock.use(l =>
      for {
        create <- l
          .acquireLock(lockId, 1.minute)
          .flatMap(_.liftTo(new IllegalStateException("Failed to acquire lock")))
        _ <- IO.sleep(2.seconds)
        nonMatchingGen <- l.releaseLock(create.copy(generation = create.generation - 1))
        released <- l.releaseLock(create)
      } yield (create.id, nonMatchingGen, released)
    )
    assertIO(value, (lockId, false, true))
  }

  test("create, refresh release a lock") {
    val lockId = LockId("ingarabr-lock-test", s"test/create-and-get-$now")
    val value = cloudLock.use(l =>
      for {
        create <- l
          .acquireLock(lockId, 1.minute)
          .flatMap(_.liftTo(new IllegalStateException("Failed to acquire lock")))
        _ <- IO.sleep(2.seconds)
        refreshed <- l.refreshLock(create, 30.minutes).flatMap {
          case RefreshStatus.Refreshed(newLockMeta) =>
            newLockMeta.pure
          case RefreshStatus.LockMismatch(oldLockMeta) =>
            new IllegalStateException("lock mismatch").raiseError
          case RefreshStatus.Error(err) => err.raiseError
        }
        released <- l.releaseLock(refreshed)
      } yield (create.id, released)
    )
    assertIO(value, (lockId, true))
  }

  test("create and get the same lock with ttl") {
    val uniqueLockId = LockId("ingarabr-lock-test", s"test/create-and-get-$now;")
    val value = cloudLock.use(l =>
      for {
        l1 <- l.acquireLock(uniqueLockId, 1.minute)
        l2 = l.getLock(uniqueLockId)
        _ <- IO.println(l1)
      } yield (l1, l2)
    )
    assertIO_(value.flatMap { case (l1, l2) => l2.assertEquals(l1) })
  }

  test("refresh a lock that does not exists") {
    val uniqueLockId = LockId("ingarabr-lock-test", s"test/create-and-get-$now;")
    val meta = LockMeta(uniqueLockId, OffsetDateTime.now(), 0L)
    val value = cloudLock.use(l =>
      for {
        _ <- l.acquireLock(uniqueLockId, 1.minute)
        l1 <- l.refreshLock(meta, 1.minute)
      } yield (l1)
    )
    assertIO_(value.assertEquals(RefreshStatus.LockMismatch(meta)))
  }

}
