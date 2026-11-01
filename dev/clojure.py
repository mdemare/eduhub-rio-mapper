#!/usr/bin/env python3
"""Bootstrap the pinned Clojure CLI for Make when no CLI is on PATH."""

import fcntl
import hashlib
import io
import os
from pathlib import Path
import shlex
import sys
import tarfile
import tempfile
import urllib.request


VERSION = "1.12.6.1673"
ARCHIVE_SHA256 = "fe9194858e75d5af13c2e2aff92d710674d5bc5105f2b42f90a7d94d82ec023c"
ARCHIVE_URL = f"https://github.com/clojure/brew-install/releases/download/{VERSION}/clojure-tools-{VERSION}.tar.gz"
CACHE_DIR = Path(__file__).resolve().parents[1] / ".cpcache" / "clojure-cli"


def bootstrap(cache_dir):
    """Install once, publishing only a complete, checksum-verified CLI."""
    cache_dir = Path(cache_dir).resolve()
    cache_dir.mkdir(parents=True, exist_ok=True)
    install_dir = cache_dir / VERSION
    executable = install_dir / "clojure"
    # Parallel Make targets may all need the CLI on their first invocation.
    with (cache_dir / "install.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if executable.is_file() and os.access(executable, os.X_OK):
            return executable
        print(f"Installing Clojure CLI {VERSION} in {install_dir}", file=sys.stderr)
        with urllib.request.urlopen(ARCHIVE_URL, timeout=30) as response:
            archive = response.read()
        if hashlib.sha256(archive).hexdigest() != ARCHIVE_SHA256:
            raise ValueError("Clojure CLI download checksum mismatch")
        with tempfile.TemporaryDirectory(prefix="install-", dir=cache_dir) as temp:
            stage = Path(temp) / VERSION
            (stage / "libexec").mkdir(parents=True)
            # Read only known files rather than extracting archive paths.
            with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as bundle:
                for name in ("deps.edn", "example-deps.edn", "tools.edn", "exec.jar",
                             f"clojure-tools-{VERSION}.jar", "clojure"):
                    source = bundle.extractfile(f"clojure-tools/{name}")
                    if source is None:
                        raise ValueError(f"Missing Clojure CLI file: {name}")
                    with source:
                        data = source.read()
                    destination = stage / ("libexec" if name.endswith(".jar") else "") / name
                    if name == "clojure":
                        data = data.decode().replace("PREFIX", shlex.quote(str(install_dir))).encode()
                    destination.write_bytes(data)
            (stage / "clojure").chmod(0o755)
            stage.rename(install_dir)
    return executable


def main(args):
    executable = bootstrap(CACHE_DIR)
    os.execv(str(executable), [str(executable), *args])


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (OSError, ValueError, KeyError, tarfile.TarError) as error:
        print(f"Clojure CLI bootstrap failed: {error}", file=sys.stderr)
        sys.exit(1)
