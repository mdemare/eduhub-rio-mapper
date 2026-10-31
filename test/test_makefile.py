"""Exercise the real Makefile with stand-ins for expensive external commands."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
FAST = [
    "python3 -B -m unittest discover -s test -p test_makefile.py",
    "clojure -M:lint",
    "clojure -M:test-common common",
    "clojure -M:test-v5 v5-fast",
    "clojure -M:test-v6 v6-fast",
]
SLOW = [
    "clojure -M:proof-specs",
    "clojure -M:test-v5 v5-vcr",
    "clojure -M:test-v6 v6-vcr",
    "clojure -M:test-v5 v5-redis",
    "clojure -M:test-v6 v6-redis",
    "clojure -M:test-v5 ho-opleiding-v5",
    "clojure -M:test-v6 ho-opleiding-v6",
    "clojure -M:test-v5 v5-e2e --no-capture-output",
    "clojure -M:test-v6 v6-e2e --no-capture-output",
    "clojure -M:watson",
]


class MakefileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        shutil.copyfile(ROOT / "Makefile", self.work / "Makefile")
        for directory in ("bin", "src-common", "src-v5", "src-v6", "resources"):
            (self.work / directory).mkdir()
        self.log = self.work / "commands.jsonl"
        # Use the absolute interpreter so the python3 stand-in cannot recurse.
        stub = f"#!{sys.executable}\n" + '''import json, os, sys
command = " ".join([os.path.basename(sys.argv[0]), *sys.argv[1:]])
with open(os.environ["COMMAND_LOG"], "a") as stream:
    stream.write(json.dumps([command, os.environ.get("VCR_RECORD")]) + "\\n")
sys.exit(23 if command == os.environ.get("FAIL_COMMAND") else 0)
'''
        for command in ("clojure", "python3"):
            executable = self.work / "bin" / command
            executable.write_text(stub)
            executable.chmod(0o755)

    def run_make(self, target, fail="", parallel=False):
        self.log.unlink(missing_ok=True)
        env = dict(os.environ, PATH=f"{self.work / 'bin'}:{os.environ['PATH']}",
                   COMMAND_LOG=str(self.log), FAIL_COMMAND=fail, VCR_RECORD="true")
        # Do not inherit caller flags such as -i (ignore failures) or -n.
        env.pop("MAKEFLAGS", None)
        env.pop("MFLAGS", None)
        result = subprocess.run(
            ["make", "--no-print-directory", *(["-j4"] if parallel else []), target],
            cwd=self.work, env=env, capture_output=True, text=True,
        )
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        return result, calls

    def test_check_runs_only_fast_checks_once(self):
        result, calls = self.run_make("check")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(FAST, [command for command, _ in calls])

    def test_full_check_covers_every_suite_and_both_builds(self):
        for parallel in (False, True):
            with self.subTest(parallel=parallel):
                result, calls = self.run_make("full-check", parallel=parallel)
                self.assertEqual(0, result.returncode, result.stderr)
                commands = [command for command, _ in calls]
                self.assertCountEqual(FAST + SLOW, commands[:len(FAST + SLOW)])
                builds = commands[len(FAST + SLOW):]
                self.assertEqual(4, len(builds))
                for version in ("v5", "v6"):
                    self.assertTrue(any(command.startswith(f"clojure -M:{version} -e ")
                                        for command in builds))
                    self.assertTrue(any(command.startswith("clojure -M:uberjar ")
                                        and f"--target target/eduhub-rio-mapper-{version}.jar" in command
                                        for command in builds))
                for command, mode in calls:
                    if "-fast" in command or any(
                        suite in command for suite in ("v5-vcr", "v6-vcr", "ho-opleiding-")
                    ):
                        self.assertEqual("false", mode, command)

    def test_full_check_propagates_each_failure(self):
        result, calls = self.run_make("full-check")
        self.assertEqual(0, result.returncode, result.stderr)
        for command, _ in calls:
            with self.subTest(command=command):
                result, _ = self.run_make("full-check", fail=command)
                self.assertNotEqual(0, result.returncode, result.stdout)

    def test_check_propagates_each_failure(self):
        for command in FAST:
            with self.subTest(command=command):
                result, _ = self.run_make("check", fail=command)
                self.assertNotEqual(0, result.returncode, result.stdout)

    def test_targets_run_even_when_matching_files_exist(self):
        targets = ("check", "full-check", "test-make", "test-fast", "lint",
                   "test-common", "proof-specs", "playback-v5", "playback-v6",
                   "test-redis", "test-v5-redis", "test-v6-redis", "test-e2e",
                   "test-v5-e2e", "test-v6-e2e", "playback-ho-opleiding-v5",
                   "playback-ho-opleiding-v6", "watson", "jar")
        for target in targets:
            (self.work / target).touch()
        result, calls = self.run_make("full-check")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(len(FAST + SLOW) + 4, len(calls))

    def test_legacy_test_keeps_recorded_interaction_coverage(self):
        result, calls = self.run_make("test")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["clojure -M:test-common common", "clojure -M:test-v5 v5",
                          "clojure -M:test-v6 v6"], [command for command, _ in calls])


if __name__ == "__main__":
    unittest.main()
