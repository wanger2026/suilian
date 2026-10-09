import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('deps', ROOT / 'tools/dependencies.py')
deps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deps)


class SourceToolTests(unittest.TestCase):
    def test_checksum_cache_reused_without_network(self):
        with tempfile.TemporaryDirectory() as name:
            work = Path(name)
            (work / 'downloads').mkdir()
            source = work / 'downloads/example.zip'
            source.write_bytes(b'public-fixture')
            lock = {'downloads': {'example.zip': {'url': 'https://example.invalid/archive', 'sha256': hashlib.sha256(b'public-fixture').hexdigest()}}}
            with patch.object(deps, 'WORK', work), patch.object(deps, 'LOCK', lock), patch.object(deps.urllib.request, 'urlopen') as network:
                self.assertEqual(deps.download('example.zip'), source)
                network.assert_not_called()

    def test_zip_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            work = Path(name)
            for member in ('../../outside.txt', 'root/../../outside.txt', '..\\outside.txt'):
                archive = work / 'input.zip'
                with zipfile.ZipFile(archive, 'w') as z: z.writestr(member, b'fixture')
                with self.assertRaises(ValueError): deps.unzip(archive, work / 'output')
            self.assertFalse((work / 'outside.txt').exists())

    def test_pinned_lock_has_https_and_sha256(self):
        for value in deps.LOCK['downloads'].values():
            self.assertTrue(value['url'].startswith('https://'))
            self.assertRegex(value['sha256'], r'^[0-9a-f]{64}$')

    def test_existing_unmarked_source_not_overwritten(self):
        with tempfile.TemporaryDirectory() as name:
            work = Path(name)
            (work / 'native/core/Clash.Meta').mkdir(parents=True)
            (work / 'patches').mkdir()
            (work / 'patches/mihomo.patch').write_text('fixture')
            with patch.object(deps, 'ROOT', work), self.assertRaises(RuntimeError): deps.bootstrap_core()


if __name__ == '__main__':
    unittest.main()

