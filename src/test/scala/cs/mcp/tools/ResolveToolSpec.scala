package cs.mcp.tools

import cats.effect.IO
import cats.effect.kernel.Ref
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.protocol.{Content, LoggingLevel, Meta}
import ch.linkyard.mcp.server.CallContext
import cs.mcp.{CsResult, IntegrationTest, assumeIO, csOnPath}
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite

class ResolveToolSpec extends CatsEffectSuite:

  private val noopContext: CallContext[IO] = new CallContext[IO]:
    override val meta: Meta = Meta.empty
    override def reportProgress(progress: Double, total: Option[Double], message: Option[String]): IO[Unit] = IO.unit
    override def log(level: LoggingLevel, data: Json): IO[Unit] = IO.unit

  test("parses cs resolve's org:name:version:configuration lines into structured dependencies") {
    val fakeRunCs: List[String] => IO[CsResult] = _ =>
      IO.pure(CsResult(
        0,
        "org.scala-lang:scala-library:2.13.14:default\norg.typelevel:cats-core_3:2.13.0:default\n",
        "",
      ))

    val tool = ResolveTool[IO](fakeRunCs)
    val args = JsonObject("dependencies" -> List("org.typelevel:cats-core_3:2.13.0").asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Success(_, Some(structured), _) =>
        assertEquals(
          structured("dependencies"),
          Some(List(
            Json.obj(
              "organization" -> "org.scala-lang".asJson,
              "name" -> "scala-library".asJson,
              "version" -> "2.13.14".asJson,
              "configuration" -> "default".asJson,
            ),
            Json.obj(
              "organization" -> "org.typelevel".asJson,
              "name" -> "cats-core_3".asJson,
              "version" -> "2.13.0".asJson,
              "configuration" -> "default".asJson,
            ),
          ).asJson),
        )
      case other => fail(s"expected a successful structured response, got $other")
    }
  }

  test("a non-zero exit code surfaces cs's stderr as a tool error") {
    val fakeRunCs: List[String] => IO[CsResult] = _ =>
      IO.pure(CsResult(1, "", "Resolution error: Error downloading org.typelevel:doesnotexist_3:99.99.99"))
    val tool = ResolveTool[IO](fakeRunCs)
    val args = JsonObject("dependencies" -> List("org.typelevel:doesnotexist_3:99.99.99").asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Error(content, _) =>
        assert(
          content.exists {
            case Content.Text(text, _, _) => text.contains("Resolution error")
            case _                        => false
          },
          s"expected stderr in error content, got $content",
        )
      case other => fail(s"expected an error response, got $other")
    }
  }

  test("an unparseable output line surfaces a tool error instead of silently dropping data") {
    val fakeRunCs: List[String] => IO[CsResult] = _ => IO.pure(CsResult(0, "not-a-valid-coordinate-line\n", ""))
    val tool = ResolveTool[IO](fakeRunCs)
    val args = JsonObject("dependencies" -> List("org.typelevel:cats-core_3:2.13.0").asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Error(content, _) =>
        assert(
          content.exists {
            case Content.Text(text, _, _) => text.contains("not-a-valid-coordinate-line")
            case _                        => false
          },
          s"expected the bad line quoted in error content, got $content",
        )
      case other => fail(s"expected an error response, got $other")
    }
  }

  test("a scalaVersion argument is passed through as --scala-version") {
    for
      capturedArgv <- Ref.of[IO, List[String]](Nil)
      fakeRunCs: (List[String] => IO[CsResult]) = argv =>
        capturedArgv.set(argv).as(CsResult(0, "", ""))
      tool = ResolveTool[IO](fakeRunCs)
      args = JsonObject(
        "dependencies" -> List("org.typelevel::cats-core:2.13.0").asJson,
        "scalaVersion" -> "3".asJson,
      )
      _ <- tool.apply(args, noopContext)
      argv <- capturedArgv.get
    yield assertEquals(argv, List("resolve", "--scala-version", "3", "org.typelevel::cats-core:2.13.0"))
  }

  test("resolves a real dependency's transitive graph via the cs binary".tag(IntegrationTest)) {
    assumeIO(csOnPath, "cs is not on PATH") >> {
      val tool = ResolveTool.default[IO]
      val args = JsonObject("dependencies" -> List("org.typelevel:cats-core_3:2.13.0").asJson)

      tool.apply(args, noopContext).map {
        case CallTool.Response.Success(_, Some(structured), _) =>
          val organizations = structured("dependencies").flatMap(_.asArray).getOrElse(Vector.empty)
            .flatMap(_.asObject).flatMap(_("organization")).flatMap(_.asString)
          assert(organizations.contains("org.typelevel"), s"expected org.typelevel among resolved deps, got $structured")
        case other => fail(s"expected a successful structured response, got $other")
      }
    }
  }
