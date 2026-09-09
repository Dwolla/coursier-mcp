package cs.mcp.tools

import cats.effect.IO
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.LoggingLevel
import ch.linkyard.mcp.protocol.Meta
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.server.CallContext
import cs.mcp.CsResult
import cs.mcp.IntegrationTest
import cs.mcp.assumeIO
import cs.mcp.csOnPath
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

class FetchToolSpec extends CatsEffectSuite:

  private val noopContext: CallContext[IO] = new CallContext[IO]:
    override val meta: Meta = Meta.empty
    override def reportProgress(progress: Double, total: Option[Double], message: Option[String]): IO[Unit] = IO.unit
    override def log(level: LoggingLevel, data: Json): IO[Unit] = IO.unit

  private def writeJsonTo(path: String, json: String): IO[Unit] =
    IO.blocking(java.nio.file.Files.writeString(java.nio.file.Path.of(path), json)).void

  private val fakeJson =
    """{
      |  "dependencies": [
      |    {
      |      "coord": "org.typelevel:cats-core_3:2.13.0",
      |      "file": "/cache/cats-core_3-2.13.0.jar",
      |      "directDependencies": [],
      |      "dependencies": []
      |    }
      |  ],
      |  "conflict_resolution": {"a:b:1": "a:b:2"},
      |  "version": "0.1.0"
      |}""".stripMargin

  test("decodes cs's --json-output-file output into a structured result") {
    val fakeRunCs: List[String] => IO[CsResult] = argv =>
      val path = argv(argv.indexOf("--json-output-file") + 1)
      writeJsonTo(path, fakeJson).as(CsResult(0, "", ""))

    val tool = FetchTool[IO](fakeRunCs)
    val args = JsonObject("dependencies" -> List("org.typelevel:cats-core_3:2.13.0").asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Success(_, Some(structured), _) =>
        assertEquals(structured("dependencies").flatMap(_.asArray).map(_.size), Some(1))
        assertEquals(
          structured("conflictResolution"),
          Some(Json.arr(Json.obj("requested" -> "a:b:1".asJson, "resolved" -> "a:b:2".asJson))),
        )
      case other => fail(s"expected a successful structured response, got $other")
    }
  }

  private val fakeJsonWithNullFile =
    """{
      |  "dependencies": [
      |    {
      |      "coord": "com.fasterxml.jackson:jackson-bom:2.17.0",
      |      "file": null,
      |      "directDependencies": [],
      |      "dependencies": []
      |    }
      |  ],
      |  "conflict_resolution": {},
      |  "version": "0.1.0"
      |}""".stripMargin

  test("decodes a pom/BOM dependency whose file is null without crashing") {
    val fakeRunCs: List[String] => IO[CsResult] = argv =>
      val path = argv(argv.indexOf("--json-output-file") + 1)
      writeJsonTo(path, fakeJsonWithNullFile).as(CsResult(0, "", ""))

    val tool = FetchTool[IO](fakeRunCs)
    val args = JsonObject("dependencies" -> List("com.fasterxml.jackson:jackson-bom:2.17.0").asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Success(_, Some(structured), _) =>
        val file = structured("dependencies").flatMap(_.asArray).get.head.hcursor.downField("file")
        assertEquals(file.focus, Some(Json.Null))
      case other => fail(s"expected a successful structured response, got $other")
    }
  }

  test("a non-zero exit code surfaces cs's stderr as a tool error") {
    val fakeRunCs: List[String] => IO[CsResult] = _ => IO.pure(CsResult(1, "", "Error: dependency not found"))
    val tool = FetchTool[IO](fakeRunCs)
    val args = JsonObject("dependencies" -> List("bogus:bogus:0.0.0").asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Error(content, _) =>
        assert(
          content.exists {
            case Content.Text(text, _, _) => text.contains("Error: dependency not found")
            case _                        => false
          },
          s"expected stderr in error content, got $content",
        )
      case other => fail(s"expected an error response, got $other")
    }
  }

  test("fetch resolves a real dependency via the cs binary".tag(IntegrationTest)) {
    assumeIO(csOnPath, "cs is not on PATH") >> {
      val tool = FetchTool.default[IO]
      val args = JsonObject("dependencies" -> List("org.typelevel:cats-core_3:2.13.0").asJson)

      tool.apply(args, noopContext).map {
        case CallTool.Response.Success(_, Some(structured), _) =>
          assertEquals(structured("dependencies").flatMap(_.asArray).exists(_.nonEmpty), true)
        case other => fail(s"expected a successful structured response, got $other")
      }
    }
  }

  test("fetch resolves a real pom/BOM coordinate whose file is null via the cs binary".tag(IntegrationTest)) {
    assumeIO(csOnPath, "cs is not on PATH") >> {
      val tool = FetchTool.default[IO]
      val args = JsonObject("dependencies" -> List("com.fasterxml.jackson:jackson-bom:2.17.0").asJson)

      tool.apply(args, noopContext).map {
        case CallTool.Response.Success(_, Some(structured), _) =>
          assertEquals(structured("dependencies").flatMap(_.asArray).exists(_.nonEmpty), true)
        case other => fail(s"expected a successful structured response, got $other")
      }
    }
  }
