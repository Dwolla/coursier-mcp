package cs.mcp

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration.*

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

  test("run drains stdout and stderr concurrently, so a large stderr write can't block a small stdout read") {
    // Writes ~5MB to stderr (far more than any OS pipe buffer, typically 64KB)
    // while stdout only gets a few bytes. Sequentially draining stdout before
    // stderr would leave `sh` blocked writing to a full stderr pipe forever.
    val fillStderrThenPrintDone = "yes X | head -c 5000000 1>&2; echo done"
    CsProcess.run[IO](List("-c", fillStderrThenPrintDone), command = "sh").timeout(10.seconds).map { result =>
      assertEquals(result.exitCode, 0)
      assertEquals(result.stdout.trim, "done")
      assert(result.stderr.length >= 5000000, s"expected >= 5,000,000 bytes of stderr, got ${result.stderr.length}")
    }
  }
