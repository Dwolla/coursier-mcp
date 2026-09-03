package cs.mcp

import cats.effect.IO
import munit.CatsEffectSuite

class CsProcessSpec extends CatsEffectSuite:

  test(
    "run(List(\"version\")) returns exit code 0 and stdout containing a version string".tag(IntegrationTest)
  ) {
    assume(csOnPath, "cs is not on PATH")
    CsProcess.run[IO](List("version")).map { result =>
      assertEquals(result.exitCode, 0)
      assert(result.stdout.trim.nonEmpty, s"expected non-empty stdout, got: ${result.stdout}")
    }
  }
