ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "com.dwolla"

val fs2Version = "3.13.0"
val catsEffectVersion = "3.7.1"
val mcpVersion = "0.3.5"
val munitVersion = "1.3.6"
val munitCatsEffectVersion = "2.2.0"
val munitScalacheckVersion = "1.3.1"

lazy val root = (project in file("."))
  .settings(
    name := "cs-mcp",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "ch.linkyard.mcp" %% "mcp-server" % mcpVersion,
      "ch.linkyard.mcp" %% "jsonrpc2-stdio" % mcpVersion,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    // MainSpec spawns `java -cp <classpath> cs.mcp.Main` and relies on
    // sys.props("java.class.path") reflecting the real runtime classpath.
    // Without forking, tests run in sbt's own JVM, whose classpath is just
    // sbt-launch.jar.
    Test / fork := true,
  )
