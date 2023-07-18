package com.github.ingarabr.gcslock

import scala.concurrent.duration.*
import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.time.Instant
import java.util.concurrent.TimeUnit

class GcsLockSpec extends CatsEffectSuite {

  override def munitTimeout: Duration = new FiniteDuration(71 * 3, TimeUnit.SECONDS)

  test("lock") {
    def durNow() = Duration.create(Instant.now().toEpochMilli, TimeUnit.MILLISECONDS)
    val start = durNow()
    def startDiff() = durNow().minus(start)
    val lockId = LockId("ingarabr-lock-test", s"test/real-use-case-3")

    def runApp(id: Int): IO[Unit] = for {
      _ <- IO.println(s"$id (${startDiff()}): Got lock")
      _ <- IO.sleep(25.seconds)
      _ <- IO.println(s"$id (${startDiff()}): Done")
    } yield ()

    val strategy = NoDependencyStrategy
      .waitUntilLockIsAvailable[IO](
        acquireRetryInterval = 5.seconds,
        lockRefreshInterval = 20.seconds,
        acquireRetryMaxAttempts = 20
      )

    def owner(id: String) = LockOwner.Resolve(IO.pure(id))

    val res: IO[Unit] = Http4sGcsLockClientSpec.cloudLock
      .map(GcsLock[IO](_))
      .use(gcsLock =>
        for {
          f1 <- gcsLock.create(lockId, owner("f1"), strategy).use(_ => runApp(1)).start
          f2 <- gcsLock.create(lockId, owner("f2"), strategy).use(_ => runApp(2)).start
          f3 <- gcsLock.create(lockId, owner("f3"), strategy).use(_ => runApp(3)).start
          _ <- List(f1, f2, f3).traverse(_.join)
        } yield ()
      )
    assertIO_(res)
  }

}
