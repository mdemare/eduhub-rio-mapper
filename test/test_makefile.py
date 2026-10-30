"""Exercise check orchestration without Redis, network access, or JVM startup."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
FAST = [
    ['python3', '-m', 'unittest', 'discover', '-s', 'test', '-p', 'test_*.py'],
    ['clojure', '-M:lint'],
    ['clojure', '-M:test-common', 'common'],
    ['clojure', '-M:test-v5', 'v5'],
    ['clojure', '-M:test-v6', 'v6'],
]
SLOW = [
    ['clojure', '-M:test-common', 'common-slow'],
    ['clojure', '-M:proof-specs'],
    ['clojure', '-M:test-v5', 'v5-vcr'],
    ['clojure', '-M:test-v6', 'v6-vcr'],
    ['clojure', '-M:test-v5', 'v5-redis'],
    ['clojure', '-M:test-v6', 'v6-redis'],
    ['clojure', '-M:test-v5', 'ho-opleiding-v5'],
    ['clojure', '-M:test-v6', 'ho-opleiding-v6'],
    ['clojure', '-M:test-v5', 'v5-e2e', '--no-capture-output'],
    ['clojure', '-M:test-v6', 'v6-e2e', '--no-capture-output'],
    ['clojure', '-M:watson'],
    ['clojure', '-M:antq'],
]


class MakefileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        shutil.copy(ROOT / 'Makefile', self.work)
        for name in ['src-v5', 'src-v6', 'src-common', 'resources', 'bin']:
            (self.work / name).mkdir()
        self.log = self.work / 'commands.log'
        self.env = dict(os.environ, PATH=str(self.work / 'bin') + os.pathsep + os.environ['PATH'],
                        COMMAND_LOG=str(self.log), VCR_RECORD='true')
        # Do not inherit make's flags/jobserver or unrelated injection settings.
        for name in ['MAKEFLAGS', 'MFLAGS', 'MAKEOVERRIDES', 'FAIL_COMMAND', 'CLOJURE']:
            self.env.pop(name, None)
        for name in ['clojure', 'python3']:
            script = self.work / 'bin' / name
            script.write_text('''#!/bin/sh
command_name=${0##*/}
record=$(
    printf '%s\\n' "$command_name" "$VCR_RECORD" "$#"
    printf '%s\\n' "$@"
)
printf '%s\\n' "$record" >> "$COMMAND_LOG"
command_text=$(printf '%s\\n' "$command_name" "$@")
[ "$command_text" != "$FAIL_COMMAND" ]
''')
            script.chmod(0o755)

    def run_make(self, target, *options):
        self.log.unlink(missing_ok=True)
        result = subprocess.run(['make', '--no-print-directory', *options, target],
                                cwd=self.work, env=self.env, capture_output=True, text=True)
        records = []
        lines = self.log.read_text().splitlines() if self.log.exists() else []
        while lines:
            name, mode, count = lines[:3]
            length = int(count)
            records.append(([name] + lines[3:3 + length], mode))
            lines = lines[3 + length:]
        return result, records

    def test_check_runs_only_fast_checks(self):
        result, records = self.run_make('check')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([record[0] for record in records], FAST)

    def test_check_uses_cached_bootstrap_when_cli_is_missing(self):
        cache = self.work / 'cache'
        cli = cache / 'eduhub-rio-mapper/clojure/1.12.4.1618/bin/clojure'
        cli.parent.mkdir(parents=True)
        shutil.copy(self.work / 'bin/clojure', cli)
        (self.work / 'bin/clojure').unlink()
        (self.work / 'dev').mkdir()
        shutil.copy(ROOT / 'dev/clojure', self.work / 'dev/clojure')
        for name in ['make', 'find', 'bash']:
            (self.work / 'bin' / name).symlink_to(shutil.which(name))
        self.env.update(PATH=str(self.work / 'bin'), XDG_CACHE_HOME=str(cache))
        result, records = self.run_make('check')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([record[0] for record in records], FAST)

    def test_explicit_cli_path_can_contain_spaces(self):
        cli = self.work / 'custom tools' / 'clojure'
        cli.parent.mkdir()
        shutil.copy(self.work / 'bin/clojure', cli)
        self.env['CLOJURE'] = str(cli)
        result, records = self.run_make('check')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([record[0] for record in records], FAST)

    def test_full_check_preserves_all_suites_and_builds_both_jars(self):
        result, records = self.run_make('full-check')
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = [record[0] for record in records]
        self.assertEqual(commands[:len(FAST + SLOW)], FAST + SLOW)
        self.assertEqual(len(commands), len(FAST + SLOW) + 4)
        for version in ['v5', 'v6']:
            self.assertTrue(any(command[:2] == ['clojure', f'-M:{version}'] for command in commands))
            self.assertTrue(any(command[:2] == ['clojure', '-M:uberjar'] and
                                command[-1] == f'target/eduhub-rio-mapper-{version}.jar'
                                for command in commands))
        for command, mode in records:
            if any(suite in command for suite in ['v5-vcr', 'v6-vcr', 'ho-opleiding-v5', 'ho-opleiding-v6']):
                self.assertEqual(mode, 'false', command)

    def test_every_command_failure_reaches_make(self):
        _, records = self.run_make('full-check')
        for command, _ in records:
            with self.subTest(command=command):
                self.env['FAIL_COMMAND'] = "\n".join(command)
                result, _ = self.run_make('full-check')
                self.assertNotEqual(result.returncode, 0)

    def test_parallel_failure_is_not_hidden(self):
        self.env['FAIL_COMMAND'] = "\n".join(["clojure", "-M:test-v6", "v6-redis"])
        result, _ = self.run_make('full-check', '-j4')
        self.assertNotEqual(result.returncode, 0)

    def test_checks_are_phony_and_always_run(self):
        for name in ['check', 'full-check', 'test-build', 'lint', 'test', 'test-common',
                     'test-v5', 'test-v6', 'test-redis', 'test-e2e', 'playback-v5',
                     'playback-v6', 'playback-ho-opleiding-v5', 'playback-ho-opleiding-v6',
                     'test-v5-redis', 'test-v6-redis', 'test-v5-e2e', 'test-v6-e2e',
                     'proof-specs', 'watson', 'antq', 'test-common-slow']:
            (self.work / name).touch()
        for _ in range(2):
            result, records = self.run_make('full-check')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual([record[0] for record in records][:len(FAST + SLOW)], FAST + SLOW)


if __name__ == '__main__':
    unittest.main()
