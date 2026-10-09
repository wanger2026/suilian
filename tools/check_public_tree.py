"""Check the exact Git source set without opening personal runtime data."""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DENIED_DIRS = {'runtime', 'outputs', 'proofs', 'diagnostics', '.build', 'releases', 'upstream', '.gradle', 'build', 'jniLibs', 'flutter_assets', 'network-rules', 'dexopt'}
DENIED_EXT = {'.apk', '.exe', '.dll', '.so', '.aar', '.db', '.sqlite', '.pem', '.key', '.jks', '.keystore', '.p12', '.dpapi', '.log', '.zip', '.prof', '.profm', '.iml', '.pyc'}
USER_PATH = re.compile(r'[A-Za-z]:[\\/]Users[\\/][^\\/\s]+', re.I)
PRIVATE_KEY = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')


def main():
    files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode('utf-8').split('\0')
    errors = []
    checked = 0
    for relative in filter(None, files):
        p = ROOT / relative
        checked += 1
        if DENIED_DIRS.intersection(Path(relative).parts) or p.suffix.lower() in DENIED_EXT:
            errors.append(f'{relative}: runtime/generated/sensitive file type')
            continue
        if p.name in {'local.properties', 'key.properties', 'plugins.local.json'} or p.name.startswith('.env'):
            errors.append(f'{relative}: local configuration')
        data = p.read_bytes()
        if b'\x00' in data:
            continue
        try: text = data.decode('utf-8-sig')
        except UnicodeDecodeError: continue
        if USER_PATH.search(text): errors.append(f'{relative}: personal user-directory path')
        if PRIVATE_KEY.search(text): errors.append(f'{relative}: private key marker')
    if not checked: raise SystemExit('No tracked source files; stage the intended publication set first.')
    if errors: raise SystemExit('\n'.join(errors))
    print(f'PASS public source tree: {checked} tracked files; no runtime artifacts, private key files or user-directory paths')


if __name__ == '__main__':
    main()

