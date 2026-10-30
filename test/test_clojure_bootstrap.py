"""Verify CLI bootstrap caching, integrity checks, and concurrent installation."""
import os
from pathlib import Path
import shlex
import subprocess
import tarfile
import tempfile
import unittest


BOOTSTRAP = Path(__file__).resolve().parents[1] / 'dev' / 'clojure'
VERSION = '1.12.4.1618'
CLI = '''#!/bin/sh
install_dir=PREFIX
printf '%s\\n' "$install_dir" "$@"
exit "${CLI_EXIT_CODE:-0}"
'''


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.env = dict(os.environ, XDG_CACHE_HOME=str(self.root / 'cache with spaces'),
                        PATH=str(self.bin) + os.pathsep + os.environ['PATH'])
        self.prefix = Path(self.env['XDG_CACHE_HOME']) / 'eduhub-rio-mapper' / 'clojure' / VERSION
        self.downloads = self.root / 'downloads'
        self.env['DOWNLOAD_LOG'] = str(self.downloads)
        self.write_executable('curl', '''#!/bin/sh
printf '%s\\n' "$@" >> "$DOWNLOAD_LOG"
for argument do destination=$argument; done
cp "$FIXTURE_ARCHIVE" "$destination"
''')

    def write_executable(self, name, contents):
        path = self.bin / name
        path.write_text(contents)
        path.chmod(0o755)

    def run_cli(self, *arguments):
        return subprocess.run([str(BOOTSTRAP), *arguments], env=self.env,
                              capture_output=True, text=True)

    def fixture_archive(self):
        tools = self.root / 'clojure-tools'
        tools.mkdir()
        (tools / 'clojure').write_text(CLI)
        for name in ['deps.edn', 'example-deps.edn', 'tools.edn', 'exec.jar',
                     f'clojure-tools-{VERSION}.jar']:
            (tools / name).write_text('')
        archive = self.root / 'fixture.tar.gz'
        with tarfile.open(archive, 'w:gz') as output:
            output.add(tools, arcname='clojure-tools')
        self.env['FIXTURE_ARCHIVE'] = str(archive)

    def test_cached_cli_preserves_arguments_and_exit_status(self):
        cli = self.prefix / 'bin' / 'clojure'
        cli.parent.mkdir(parents=True)
        cli.write_text(CLI.replace('PREFIX', shlex.quote(str(self.prefix / 'lib/clojure'))))
        cli.chmod(0o755)
        self.env['CLI_EXIT_CODE'] = '7'
        result = self.run_cli('-M:test', 'argument with spaces', '$(literal)')
        self.assertEqual(result.returncode, 7)
        self.assertEqual(result.stdout.splitlines(),
                         [str(self.prefix / 'lib/clojure'), '-M:test',
                          'argument with spaces', '$(literal)'])
        self.assertFalse(self.downloads.exists())

    def test_child_processes_can_find_the_cached_cli(self):
        cli = self.prefix / 'bin' / 'clojure'
        cli.parent.mkdir(parents=True)
        cli.write_text('#!/bin/sh\ncommand -v clojure\n')
        cli.chmod(0o755)
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(cli))

    def test_corrupt_download_is_rejected_and_can_be_retried(self):
        self.fixture_archive()
        for _ in range(2):
            result = self.run_cli('-Sdescribe')
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(self.prefix.exists())
            self.assertIn("SHA-256 verification", result.stderr)
            self.assertEqual(list(self.prefix.parent.glob('.install.*')), [])
        self.assertEqual(self.downloads.read_text().count('--output'), 2)

    def test_download_failure_is_not_cached(self):
        self.write_executable('curl', '#!/bin/sh\nexit 23\n')
        result = self.run_cli('-Sdescribe')
        self.assertEqual(result.returncode, 23)
        self.assertFalse(self.prefix.exists())
        self.assertEqual(list(self.prefix.parent.glob('.install.*')), [])

    def test_parallel_bootstraps_install_once_and_reuse_cache(self):
        self.fixture_archive()
        # The synthetic archive exercises installation; the corrupt-download test
        # above uses the real checksum tool to cover integrity enforcement.
        self.write_executable('sha256sum', '#!/bin/sh\ncat > /dev/null\n')
        processes = [subprocess.Popen([str(BOOTSTRAP), '-Sdescribe'], env=self.env,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                     for _ in range(2)]
        for process in processes:
            stdout, stderr = process.communicate()
            self.assertEqual(process.returncode, 0, stderr)
            self.assertEqual(stdout.splitlines(), [str(self.prefix / 'lib/clojure'), '-Sdescribe'])
        result = self.run_cli('-M:lint')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.downloads.read_text().count('--output'), 1)
        self.assertIn(f'/releases/download/{VERSION}/clojure-tools-{VERSION}.tar.gz',
                      self.downloads.read_text())
