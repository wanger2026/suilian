"""Fetch pinned public inputs. Never reads an installed app or private profile."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = Path(os.environ.get('SUILIAN_WORK', str(ROOT / '.build'))).resolve()
LOCK = json.loads((ROOT / 'dependencies.lock.json').read_text('utf-8'))


def run(*args, cwd=ROOT, env=None):
    subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=True)


def digest(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else hashlib.sha256(stream.read()).hexdigest()


def download(name):
    item = LOCK['downloads'][name]
    target = WORK / 'downloads' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and digest(target) == item['sha256']:
        return target
    partial = target.with_suffix(target.suffix + '.partial')
    request = urllib.request.Request(item['url'], headers={'User-Agent': 'SuiLian-source-build'})
    with urllib.request.urlopen(request, timeout=90) as response, partial.open('wb') as stream:
        shutil.copyfileobj(response, stream)
    if digest(partial) != item['sha256']:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f'Checksum mismatch: {name}')
    partial.replace(target)
    return target


def unzip(source, destination, strip=0):
    destination = Path(destination).resolve()
    with zipfile.ZipFile(source) as archive:
        for item in archive.infolist():
            if item.is_dir():
                continue
            parts = Path(item.filename.replace('\\', '/')).parts[strip:]
            if not parts:
                continue
            target = destination.joinpath(*parts).resolve()
            if not target.is_relative_to(destination):
                raise ValueError('Unsafe archive entry')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(item))


def bootstrap_core():
    target = ROOT / 'native/core/Clash.Meta'
    patch = ROOT / 'patches/mihomo.patch'
    marker = target / '.suilian-source.json'
    expected = {'commit': LOCK['sources']['mihomo']['commit'], 'patch': digest(patch)}
    if marker.exists() and json.loads(marker.read_text('utf-8')) == expected:
        return
    if target.exists():
        raise RuntimeError('Core source exists without a matching marker. Preserve any changes and use a fresh checkout.')
    unzip(download('mihomo-source.zip'), target, strip=1)
    run('git', 'init', '--quiet', target)
    run('git', 'apply', '--check', patch, cwd=target)
    run('git', 'apply', patch, cwd=target)
    marker.write_text(json.dumps(expected), 'utf-8')


def bootstrap_remote():
    target = ROOT / 'upstream/rustdesk'
    patch = ROOT / 'patches/rustdesk.patch'
    marker = target / '.suilian-source.json'
    item = LOCK['sources']['rustdesk']
    expected = {'commit': item['commit'], 'patch': digest(patch)}
    if marker.exists() and json.loads(marker.read_text('utf-8')) == expected:
        return
    if target.exists():
        raise RuntimeError('RustDesk source exists without a matching marker. Use a fresh checkout; no local changes were overwritten.')
    target.parent.mkdir(parents=True, exist_ok=True)
    run('git', 'clone', '--filter=blob:none', '--no-checkout', item['url'], target)
    run('git', 'checkout', '--detach', item['commit'], cwd=target)
    run('git', 'submodule', 'update', '--init', '--recursive', cwd=target)
    run('git', 'apply', '--check', patch, cwd=target)
    run('git', 'apply', patch, cwd=target)
    marker.write_text(json.dumps(expected), 'utf-8')


def prepare_android_inputs():
    native = ROOT / 'android/app/src/main/jniLibs/arm64-v8a'
    native.mkdir(parents=True, exist_ok=True)
    # Only unchanged native components; modified UI and VPN engine are rebuilt.
    wanted = {
        'rustdesk.apk': ['librustdesk.so', 'libc++_shared.so'],
        'flclash.apk': ['libcore.so'],
        'moonlight.apk': ['libmoonlight-core.so'],
    }
    for archive_name, names in wanted.items():
        with zipfile.ZipFile(download(archive_name)) as archive:
            for name in names:
                member = 'lib/arm64-v8a/' + name
                if name == 'libc++_shared.so' and member not in archive.namelist():
                    continue
                (native / name).write_bytes(archive.read(member))
    libs = ROOT / 'android/app/libs'
    libs.mkdir(parents=True, exist_ok=True)
    with tarfile.open(download('rustls-platform-verifier-android-0.1.1.crate')) as archive:
        for member in archive.getmembers():
            if member.isfile() and member.name.endswith('.aar'):
                (libs / Path(member.name).name).write_bytes(archive.extractfile(member).read())


def bundle_rules():
    destination = ROOT / 'android/app/src/main/assets/network-rules'
    destination.mkdir(parents=True, exist_ok=True)
    providers = []
    for i, rule in enumerate(LOCK['rules']):
        source = download(rule['download'])
        name = f'{i:02d}.mrs'
        shutil.copy2(source, destination / name)
        providers.append(dict(name=f'public-{i:02d}', label=rule['label'], group=rule['group'],
                              target=rule['target'], asset=name, path='rules/' + name,
                              format='mrs', behavior=rule['behavior'],
                              url=LOCK['downloads'][rule['download']]['url'], sha256=digest(source)))
    (destination / 'manifest.json').write_text(json.dumps(dict(version=LOCK['rulesCommit'], providers=providers),
                                                         ensure_ascii=False, indent=2), 'utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('component', choices=['core', 'remote', 'android', 'rules', 'all'])
    args = parser.parse_args()
    if args.component in ('core', 'all'): bootstrap_core()
    if args.component in ('remote', 'all'): bootstrap_remote()
    if args.component in ('android', 'all'): prepare_android_inputs()
    if args.component in ('rules', 'all'): bundle_rules()


if __name__ == '__main__':
    main()
