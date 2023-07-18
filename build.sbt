import Versions.V

inThisBuild(
  Seq(
    organization := "com.github.ingarabr",
    scalacOptions += "-no-indent",
    scalaVersion := V.Scala,
    testFrameworks += new TestFramework("munit.Framework"),
    homepage := Some(url("https://github.com/ingarabr/gcs-lock")),
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    developers := List(
      Developer(
        "ingarabr",
        "Ingar Abrahamsen",
        "ingar.abrahamasen@gmail.com",
        url("https://github.com/ingarabr/")
      )
    )
  )
)

lazy val root = (project in file("."))
  .settings(
    name := "gcp-lock-root",
    publish := {},
    publish / skip := true
  )
  .aggregate(core, `cats-retry`, http4s)

lazy val core = (project in file("modules/core"))
  .settings(
    name := "gcs-lock-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % V.CatsEffect,
      "org.typelevel" %% "munit-cats-effect-3" % V.MunitCatsEffect % Test,
      "org.scalameta" %% "munit" % V.Munit % Test
    )
  )
lazy val `cats-retry` = (project in file("modules/cats-retry"))
  .dependsOn(core)
  .settings(
    name := "gcs-lock-cats-retry",
    libraryDependencies ++= Seq(
      "com.github.cb372" %% "cats-retry" % V.CatsRetry,
      "org.typelevel" %% "cats-effect" % V.CatsEffect,
      "org.typelevel" %% "munit-cats-effect-3" % V.MunitCatsEffect % Test,
      "org.scalameta" %% "munit" % V.Munit % Test
    )
  )

lazy val http4s = (project in file("modules/http4s"))
  .dependsOn(core)
  .settings(
    name := "gcs-lock-http4s",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % V.Http4s,
      "org.http4s" %% "http4s-circe" % V.Http4s,
      "org.http4s" %% "http4s-dsl" % V.Http4s,
      "org.typelevel" %% "cats-effect" % V.CatsEffect,
      "org.typelevel" %% "munit-cats-effect-3" % V.MunitCatsEffect % Test,
      "org.scalameta" %% "munit" % V.Munit % Test
    )
  )
