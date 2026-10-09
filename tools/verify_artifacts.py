"""Verify APK structure and rule hashes; optionally match the original technical baseline."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'releases/SuiLian-0.3.17-test-arm64.apk')
    parser.add_argument('--baseline', action='store_true', help='Require the original test-build hashes, including its Flutter assets.')
    args = parser.parse_args()
    lock = json.loads((ROOT / 'native-artifacts.lock.json').read_text('utf-8'))
    ui = json.loads((ROOT / 'integrated-ui.lock.json').read_text('utf-8'))
    core = json.loads((ROOT / 'direct-core.lock.json').read_text('utf-8'))
    alignments = {}
    with zipfile.ZipFile(args.apk) as archive:
        for component in lock.values():
            for name, expected in component['files'].items():
                if not name.endswith('.so'): continue
                data = archive.read(name)
                if args.baseline or name not in {'lib/arm64-v8a/libapp.so', 'lib/arm64-v8a/libflutter.so', 'lib/arm64-v8a/libclash.so'}:
                    expected = core['files'].get(name, ui['files'].get(name, expected))
                    if hashlib.sha256(data).hexdigest() != expected: raise ValueError('Native hash mismatch: ' + name)
                if data[:6] != b'\x7fELF\x02\x01': raise ValueError('Expected arm64 little-endian ELF: ' + name)
                offset = struct.unpack_from('<Q', data, 32)[0]
                size, count = struct.unpack_from('<HH', data, 54)
                values = [struct.unpack_from('<Q', data, offset + i * size + 48)[0] for i in range(count)
                          if struct.unpack_from('<I', data, offset + i * size)[0] == 1]
                alignments[name] = min(values)
                if min(values) < 16384: raise ValueError('ELF load alignment below 16 KB: ' + name)
        manifest = json.loads(archive.read('assets/network-rules/manifest.json'))
        for item in manifest['providers']:
            if hashlib.sha256(archive.read('assets/network-rules/' + item['asset'])).hexdigest() != item['sha256']:
                raise ValueError('Rule hash mismatch: ' + item['asset'])
        if args.baseline:
            for name, expected in ui['files'].items():
                if hashlib.sha256(archive.read(name)).hexdigest() != expected: raise ValueError('UI hash mismatch: ' + name)
        archive.getinfo('assets/flutter_assets/AssetManifest.bin')
    print(json.dumps({'apk': args.apk.name, 'sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                      'rules': len(manifest['providers']), 'ELF_load_alignment': alignments, 'baseline': args.baseline}, indent=2))


if __name__ == '__main__':
    main()
