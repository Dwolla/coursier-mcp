package cs.mcp

object IntegrationTest extends munit.Tag("integration")

def csOnPath: Boolean =
  sys.env.get("PATH").exists { path =>
    path.split(java.io.File.pathSeparatorChar).exists { dir =>
      new java.io.File(dir, "cs").canExecute
    }
  }
