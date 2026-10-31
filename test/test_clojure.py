"""Verify the CLI fallback without downloading tools or running Clojure."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stderr
import gzip
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import URLError


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("clojure_bootstrap", ROOT / "dev/clojure.py")
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)


def tools_archive(missing=None):
    files = {name: b"{}" for name in ("deps.edn", "example-deps.edn", "tools.edn",
                                     "exec.jar", f"clojure-tools-{CLI.VERSION}.jar")}
    files["clojure"] = b'''#!/bin/sh
install_dir=PREFIX
printf '%s\\n' "$install_dir" "$@"
exit "${CLI_TEST_EXIT:-0}"
'''
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as bundle:
        for name, data in files.items():
            if name != missing:
                member = tarfile.TarInfo(f"clojure-tools/{name}")
                member.size = len(data)
                bundle.addfile(member, io.BytesIO(data))
    return gzip.compress(stream.getvalue(), mtime=0)


class ClojureBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="clojure bootstrap ")
        self.addCleanup(self.temp.cleanup)
        self.cache = Path(self.temp.name) / "cache"
        self.archive = tools_archive()
        digest = patch.object(CLI, "ARCHIVE_SHA256", hashlib.sha256(self.archive).hexdigest())
        digest.start()
        self.addCleanup(digest.stop)
        download = patch.object(CLI.urllib.request, "urlopen",
                                side_effect=lambda *a, **kw: io.BytesIO(self.archive))
        self.download = download.start()
        self.addCleanup(download.stop)
        self.stderr = redirect_stderr(io.StringIO())
        self.stderr.__enter__()
        self.addCleanup(self.stderr.__exit__, None, None, None)

    def test_installs_and_reuses_verified_cli(self):
        executable = CLI.bootstrap(self.cache)
        self.assertTrue(os.access(executable, os.X_OK))
        self.assertTrue((executable.parent / "libexec" / "exec.jar").is_file())
        self.assertEqual(executable, CLI.bootstrap(self.cache))
        self.download.assert_called_once_with(CLI.ARCHIVE_URL, timeout=30)
        result = subprocess.run([str(executable), "-M:test-v5", "argument with spaces"],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([str(executable.parent), "-M:test-v5", "argument with spaces"],
                         result.stdout.splitlines())

    def test_checksum_failure_never_publishes_an_install(self):
        self.archive = b"corrupted download"
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            CLI.bootstrap(self.cache)
        self.assertFalse((self.cache / CLI.VERSION).exists())
        self.archive = tools_archive()
        self.assertTrue(CLI.bootstrap(self.cache).is_file())

    def test_failed_download_can_be_retried(self):
        self.download.side_effect = URLError("offline")
        with self.assertRaises(URLError):
            CLI.bootstrap(self.cache)
        self.assertFalse((self.cache / CLI.VERSION).exists())
        self.download.side_effect = lambda *a, **kw: io.BytesIO(self.archive)
        self.assertTrue(CLI.bootstrap(self.cache).is_file())

    def test_incomplete_archive_never_publishes_an_install(self):
        self.archive = tools_archive(missing="clojure")
        with patch.object(CLI, "ARCHIVE_SHA256", hashlib.sha256(self.archive).hexdigest()):
            with self.assertRaises(KeyError):
                CLI.bootstrap(self.cache)
        self.assertFalse((self.cache / CLI.VERSION).exists())
        self.assertEqual(["install.lock"], [path.name for path in self.cache.iterdir()])

    def test_parallel_bootstraps_download_once(self):
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(CLI.bootstrap, [self.cache] * 4))
        self.assertEqual([results[0]] * 4, results)
        self.download.assert_called_once()

    def test_exec_preserves_arguments(self):
        with patch.object(CLI, "CACHE_DIR", self.cache), patch.object(CLI.os, "execv") as execute:
            CLI.main(["-M:test-v5", "argument with spaces"])
        executable = str(self.cache / CLI.VERSION / "clojure")
        execute.assert_called_once_with(executable, [executable, "-M:test-v5", "argument with spaces"])

    def test_cached_cli_runs_offline_and_preserves_failure_status(self):
        executable = CLI.bootstrap(self.cache)
        self.download.side_effect = URLError("offline")
        self.assertEqual(executable, CLI.bootstrap(self.cache))
        result = subprocess.run([str(executable)], env=dict(os.environ, CLI_TEST_EXIT="23"),
                                capture_output=True, text=True)
        self.assertEqual(23, result.returncode)


if __name__ == "__main__":
    unittest.main()
