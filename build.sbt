ThisBuild / organization := "com.dwolla"
ThisBuild / homepage := Option(url("https://github.com/Dwolla/coursier-mcp"))
ThisBuild / tlBaseVersion := "0.1"
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / tlFatalWarnings := githubIsWorkflowBuild.value
ThisBuild / startYear := Option(2026)
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+coursier-mcp@dwolla.com",
    url("https://dwolla.com")
  ),
)
ThisBuild / mergifyStewardConfig ~= { _.map {
  _.withAuthor("dwolla-oss-scala-steward[bot]")
    .withMergeMinors(true)
}}
ThisBuild / tlCiReleaseBranches += "main"
// Scala 3.9 requires JDK 17+ to compile and run; the sbt-github-actions
// default of temurin@8 is below that minimum.
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
// Installs `cs` on the CI runner so the real-binary integration tests
// (tagged, csOnPath-guarded) actually exercise cs instead of skipping.
ThisBuild / githubWorkflowBuildPreamble +=
  WorkflowStep.Use(
    UseRef.Public("coursier", "setup-action", "v3"),
    name = Some("Setup coursier (cs)")
  )

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
      "org.typelevel" %% "mouse" % "1.4.0" % Test,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    // MainSpec spawns `java -cp <classpath> cs.mcp.Main` and relies on
    // sys.props("java.class.path") reflecting the real runtime classpath.
    // Without forking, tests run in sbt's own JVM, whose classpath is just
    // sbt-launch.jar.
    Test / fork := true,
  )
