package cs.mcp.tools

import cats.effect.IO
import cats.effect.kernel.Ref
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.Tool.CallTool
import cs.mcp.CsResult
import cs.mcp.IntegrationTest
import cs.mcp.assumeIO
import cs.mcp.csOnPath
import cs.mcp.noopContext
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

class CompleteDepToolSpec extends CatsEffectSuite:

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
    assumeIO(csOnPath, "cs is not on PATH") >> {
    val tool = CompleteDepTool.default[IO]
    val args = JsonObject("prefix" -> "org.typelevel:cats-effect".asJson)

    tool.apply(args, noopContext).map {
      case CallTool.Response.Success(_, Some(structured), _) =>
        assertEquals(structured("completions").flatMap(_.asArray).exists(_.nonEmpty), true)
      case other => fail(s"expected a successful structured response, got $other")
    }
      }
  }

  test("a scalaVersion argument is passed through as --scala-version") {
    for
      capturedArgv <- Ref.of[IO, List[String]](Nil)
      fakeRunCs: (List[String] => IO[CsResult]) = argv => capturedArgv.set(argv).as(CsResult(0, "", ""))
      tool = CompleteDepTool[IO](fakeRunCs)
      args = JsonObject("prefix" -> "org.typelevel::cats-effect".asJson, "scalaVersion" -> "3".asJson)
      _ <- tool.apply(args, noopContext)
      argv <- capturedArgv.get
    yield assertEquals(argv, List("complete-dep", "--scala-version", "3", "org.typelevel::cats-effect"))
  }

  test("no scalaVersion argument omits --scala-version") {
    for
      capturedArgv <- Ref.of[IO, List[String]](Nil)
      fakeRunCs: (List[String] => IO[CsResult]) = argv => capturedArgv.set(argv).as(CsResult(0, "", ""))
      tool = CompleteDepTool[IO](fakeRunCs)
      args = JsonObject("prefix" -> "org.typelevel:cats-effect".asJson)
      _ <- tool.apply(args, noopContext)
      argv <- capturedArgv.get
    yield assertEquals(argv, List("complete-dep", "org.typelevel:cats-effect"))
  }

  test(
    "the sbt-style :: shorthand only resolves to unsuffixed names when scalaVersion is given".tag(IntegrationTest)
  ) {
    val tool = CompleteDepTool.default[IO]
    val withoutScalaVersion = JsonObject("prefix" -> "org.typelevel::cats-effect".asJson)
    val withScalaVersion =
      JsonObject("prefix" -> "org.typelevel::cats-effect".asJson, "scalaVersion" -> "3".asJson)

    for
      _ <- assumeIO(csOnPath, "cs is not on PATH")
      withoutResponse <- tool.apply(withoutScalaVersion, noopContext)
      withResponse <- tool.apply(withScalaVersion, noopContext)
    yield (withoutResponse, withResponse) match
      case (
            CallTool.Response.Success(_, Some(without), _),
            CallTool.Response.Success(_, Some(withV), _),
          ) =>
        assertEquals(without("completions"), Some(List.empty[String].asJson))
        assert(
          withV("completions").flatMap(_.asArray).exists(_.contains("cats-effect-kernel".asJson)),
          s"expected unsuffixed 'cats-effect-kernel' among completions, got $withV",
        )
      case other => fail(s"expected two successful structured responses, got $other")
  }
