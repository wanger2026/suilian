"""Portable Windows source build; prerequisite SDKs are explicit environment settings."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from dependencies import ROOT, WORK, LOCK, run, digest, download, unzip, bootstrap_core, bootstrap_remote, prepare_android_inputs, bundle_rules


def required(name):
    value = os.environ.get(name)
    if not value or not Path(value).exists():
        raise RuntimeError(f'Set {name} to an existing SDK/tool path (see docs/BUILD.md).')
    return Path(value).resolve()


def native():
    bootstrap_core()
    sdk = required('ANDROID_HOME')
    ndk = Path(os.environ.get('ANDROID_NDK_HOME', str(sdk / 'ndk/28.2.13676358')))
    cc = ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang.cmd'
    if not cc.exists(): raise RuntimeError('Android NDK 28.2.13676358 is required.')
    core = ROOT / 'native/core'
    env = os.environ.copy()
    env.update(GOOS='android', GOARCH='arm64', CGO_ENABLED='1', CC=str(cc))
    native_dir = ROOT / 'android/app/src/main/jniLibs/arm64-v8a'
    native_dir.mkdir(parents=True, exist_ok=True)
    run('go', 'test', '-count=1', '-timeout=120s', './component/easytier', cwd=core / 'Clash.Meta')
    run('go', 'test', '-run', 'TestPhysicalNetworkRefreshRecreatesInstance', '-timeout=30s', './adapter/outbound', cwd=core / 'Clash.Meta')
    run('go', 'build', '-trimpath', '-tags=with_gvisor', '-buildmode=c-shared',
        '-ldflags=-w -s -extldflags=-Wl,-z,max-page-size=16384', '-o', native_dir / 'libclash.so', '.', cwd=core, env=env)


def remote():
    bootstrap_remote()
    sdk = required('FLUTTER_ROOT')
    frb = required('FRB_CODEGEN')
    llvm = required('LLVM_HOME')
    flutter = sdk / 'bin/flutter.bat'
    version = subprocess.check_output([str(flutter), '--version', '--machine'], text=True)
    if json.loads(version)['frameworkVersion'] != LOCK['flutter']:
        raise RuntimeError('This source revision requires Flutter ' + LOCK['flutter'])
    source = ROOT / 'upstream/rustdesk'
    WORK.mkdir(parents=True, exist_ok=True)
    project = source / 'flutter'
    # ffigen, invoked by FRB, requires a resolved Dart package configuration.
    run(flutter, 'pub', 'get', '--enforce-lockfile', cwd=project)
    run(frb, '--rust-input', './src/flutter_ffi.rs', '--dart-output', './flutter/lib/generated_bridge.dart',
        '--rust-output', './src/bridge_generated.rs', '--skip-add-mod-to-lib', '--skip-deps-check', '--no-build-runner',
        '--llvm-path', llvm, f'--llvm-compiler-opts=-I{llvm.as_posix()}/include -ffreestanding',
        '--c-output', WORK / 'bridge.h', cwd=source)
    # The upstream Flutter folder supplies its plugin registrations; build from an ASCII path.
    run(flutter, 'pub', 'run', 'build_runner', 'build', '--delete-conflicting-outputs', cwd=project)
    run(flutter, 'test', 'test/wangchuan_development_test.dart', 'test/remote_touch_arena_test.dart', cwd=project)
    output = WORK / 'remote-aot'
    run(flutter, 'assemble', f'--output={output}', '-dTargetPlatform=android-arm64', '-dBuildMode=release',
        '-dTargetFile=lib/main.dart', '-dTreeShakeIcons=false', 'android_aot_bundle_release_android-arm64', cwd=project)
    target = ROOT / 'android/app/src/main/jniLibs/arm64-v8a'
    target.mkdir(parents=True, exist_ok=True)
    shutil.copy2(output / 'arm64-v8a/app.so', target / 'libapp.so')
    with zipfile.ZipFile(sdk / 'bin/cache/artifacts/engine/android-arm64-release/flutter.jar') as archive:
        (target / 'libflutter.so').write_bytes(archive.read('lib/arm64-v8a/libflutter.so'))
    shutil.copytree(output / 'flutter_assets', ROOT / 'android/app/src/main/assets/flutter_assets', dirs_exist_ok=True)
    deps = json.loads((project / '.flutter-plugins-dependencies').read_text('utf-8'))
    (ROOT / 'android/plugins.local.json').write_text(json.dumps(deps), 'utf-8')
    registry = project / 'android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java'
    shutil.copy2(registry, ROOT / 'android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java')
    run('python', ROOT / 'tools/verify_remote_abi.py')


def android():
    prepare_android_inputs()
    bundle_rules()
    jni = ROOT / 'android/app/src/main/jniLibs/arm64-v8a'
    for name in ['libclash.so', 'libapp.so', 'libflutter.so']:
        if not (jni / name).exists(): raise RuntimeError('Run build.py native and remote first; missing ' + name)
    required('JAVA_HOME')
    required('ANDROID_HOME')
    run(ROOT / 'android/gradlew.bat', '--no-daemon', ':app:testDebugUnitTest', ':app:assembleDebug', cwd=ROOT / 'android')
    release = ROOT / 'releases'
    release.mkdir(exist_ok=True)
    target = release / 'SuiLian-0.3.17-test-arm64.apk'
    shutil.copy2(ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk', target)
    print('Built', target.name, 'SHA256', digest(target))


def windows():
    release = ROOT / 'releases'
    release.mkdir(exist_ok=True)
    run('go', 'test', './...', cwd=ROOT / 'companion')
    run('go', 'vet', './...', cwd=ROOT / 'companion')
    run('go', 'build', '-trimpath', '-ldflags=-s -w', '-o', release / 'WangChuanLink.exe', '.', cwd=ROOT / 'companion')
    run('powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ROOT / 'tools/build-manager.ps1')


def package():
    from extract_rustdesk import extract
    bundle_rules()
    release = ROOT / 'releases/windows-0.3.17-test'
    release.mkdir(parents=True, exist_ok=True)
    for name in ['WangChuanLink.exe', 'WangChuanManager.exe']:
        shutil.copy2(ROOT / 'releases' / name, release / name)
    for archive, component in [('easytier-windows-2.7.0.zip', 'easytier'), ('sunshine.zip', 'sunshine')]:
        unzip(download(archive), release / 'runtime' / component, strip=1)
    extract(download('rustdesk-windows.exe'), release / 'runtime/rustdesk')
    shutil.copytree(ROOT / 'android/app/src/main/assets/network-rules', release / 'rules', dirs_exist_ok=True)
    shutil.copytree(ROOT / 'licenses', release / 'licenses', dirs_exist_ok=True)
    shutil.copy2(ROOT / 'LICENSE', release / 'LICENSE')
    shutil.copy2(ROOT / 'THIRD_PARTY.md', release / 'THIRD_PARTY.md')
    for name in ['stop-owned.ps1', 'usb-preflight.ps1', 'usb-prepare.ps1', 'usb-pair-debug.ps1', 'install-test.ps1']:
        shutil.copy2(ROOT / 'tools' / name, release / name)
    shutil.copy2(ROOT / 'tools/启动电脑端.ps1', release / 'start.ps1')
    sdk = required('ANDROID_HOME')
    adb = release / 'runtime/adb'
    adb.mkdir(parents=True, exist_ok=True)
    for name in ['adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt', 'source.properties']:
        src = sdk / 'platform-tools' / name
        if src.exists(): shutil.copy2(src, adb / name)
    apk = ROOT / 'releases/SuiLian-0.3.17-test-arm64.apk'
    if apk.exists(): shutil.copy2(apk, release / apk.name)
    shutil.copy2(ROOT / 'README.md', release / '使用说明.md')
    shutil.copy2(ROOT / 'docs/DEVELOPMENT.md', release / '真机联调说明.md')
    shutil.copy2(ROOT / 'tools/adb-phone.cmd', release / 'adb-phone.cmd')
    print('Local package prepared. Review licenses and signing before redistributing binaries:', release)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('component', choices=['native', 'remote', 'android', 'windows', 'package'])
    args = parser.parse_args()
    temporary = WORK / 'temp'
    temporary.mkdir(parents=True, exist_ok=True)
    os.environ.update(TEMP=str(temporary), TMP=str(temporary))
    globals()[args.component]()
