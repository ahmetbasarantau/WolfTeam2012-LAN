import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


@unittest.skipUnless(sys.platform == 'win32', 'Windows job supervision')
class DiagnosticsTests(unittest.TestCase):
    def test_child_ownership_and_root_exit_code(self):
        from client_diagnostics import supervise
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            # The root starts a child then exits immediately. The job must keep
            # supervising that child, rather than treating the root exit as done.
            child = root / 'child.txt'
            child_source = f"import time; from pathlib import Path; time.sleep(.5); Path({str(child)!r}).write_text('finished')"
            source = f"import subprocess,sys; subprocess.Popen([sys.executable,'-c',{child_source!r}]); raise SystemExit(7)"
            code = supervise(sys.executable, ['-c', source], root, root / 'run', timeout=5)
            self.assertEqual(code, 7)
            self.assertEqual(child.read_text(), 'finished')
            self.assertEqual(json.loads((root/'run/client-exit.json').read_text())['root_exit_code'], 7)

    def test_timeout_kills_only_owned_job(self):
        from client_diagnostics import supervise
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(20)'])
            try:
                code = supervise(sys.executable, ['-c', 'import time; time.sleep(20)'], root, root/'run', timeout=.2)
                self.assertEqual(code, 124)
                self.assertIsNone(unrelated.poll())
            finally:
                unrelated.terminate()
                unrelated.wait(3)
