package cs.mcp.tools

import cats.effect.IO
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.LoggingLevel
import ch.linkyard.mcp.protocol.Meta
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.server.CallContext
import cs.mcp.CsResult
import cs.mcp.IntegrationTest
import cs.mcp.csOnPath
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

class CompleteDepToolSpec extends CatsEffectSuite:

  private val noopContext: CallContext[IO] = new CallContext[IO]:
    override val meta: Meta = Meta.empty
    override def reportProgress(progress: Double, total: Option[Double], message: Option[String]): IO[Unit] = IO.unit
    override def log(level: LoggingLevel, data: Json): IO[Unit] = IO.unit

  test("decodes cs complete-dep's line-oriented stdout into a list of completions") {
    val fakeRunCs: List[String] => IO[CsResult] = _ =>
      IO.pure(CsResult(0, "cats-effect_2.12\ncats-effect_2.13\ncats-effect_3\n", ""))

    val tool = CompleteDepTool[IO](fakeRunCs)
    val args = JsonObject("prefix" -> "org.typelevel:cats-effect".asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Success(_, Some(structured), _) =>
        assertEquals(
          structured("completions"),
          Some(List("cats-effect_2.12", "cats-effect_2.13", "cats-effect_3").asJson),
        )
      case other => fail(s"expected a successful structured response, got $other")
    }
  }

  test("a non-zero exit code surfaces cs's stderr as a tool error") {
    val fakeRunCs: List[String] => IO[CsResult] = _ => IO.pure(CsResult(1, "", "No argument to complete passed"))
    val tool = CompleteDepTool[IO](fakeRunCs)
    val args = JsonObject("prefix" -> "".asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Error(content, _) =>
        assert(
          content.exists {
            case Content.Text(text, _, _) => text.contains("No argument to complete passed")
            case _                        => false
          },
          s"expected stderr in error content, got $content",
        )
      case other => fail(s"expected an error response, got $other")
    }
  }

  test("complete-dep completes a real Maven coordinate via the cs binary".tag(IntegrationTest)) {
    assume(csOnPath, "cs is not on PATH")
    val tool = CompleteDepTool.default[IO]
    val args = JsonObject("prefix" -> "org.typelevel:cats-effect".asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Success(_, Some(structured), _) =>
        assertEquals(structured("completions").flatMap(_.asArray).exists(_.nonEmpty), true)
      case other => fail(s"expected a successful structured response, got $other")
    }
  }
