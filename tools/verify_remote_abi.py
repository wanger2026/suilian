"""Check the generated Dart bridge against the retained official RustDesk ELF."""
import json
import re
import struct
from pathlib import Path

root = Path(__file__).resolve().parents[1]
data = (root/'android/app/src/main/jniLibs/arm64-v8a/librustdesk.so').read_bytes()
assert data[:6] == b'\x7fELF\x02\x01'
offset = struct.unpack_from('<Q', data, 40)[0]
size, count = struct.unpack_from('<HH', data, 58)
sections = [struct.unpack_from('<IIQQQQIIQQ', data, offset+i*size) for i in range(count)]
symbols = set()
for section in sections:
    if section[1] != 11:  # SHT_DYNSYM
        continue
    strings = sections[section[6]]
    names = data[strings[4]:strings[4]+strings[5]]
    for p in range(section[4], section[4]+section[5], section[9]):
        name, info, visibility, index, value, length = struct.unpack_from('<IBBHQQ', data, p)
        if index:
            symbols.add(names[name:names.find(b'\0', name)].decode('utf-8'))
bridge = (root/'upstream/rustdesk/flutter/lib/generated_bridge.dart').read_text(encoding='utf-8')
required = set(re.findall(r"'(wire_[a-z0-9_]+|new_[a-zA-Z0-9_]+|free_WireSyncReturn|store_dart_post_cobject|drop_dart_object|get_dart_object|init_frb_dart_api_dl)'", bridge))
missing = sorted(required-symbols)
report = {'required': len(required), 'exported': len(symbols), 'missing': missing,
          'scope': 'Symbol presence only; runtime ABI still needs Android device validation.'}
(root/'releases').mkdir(exist_ok=True)
(root/'releases/remote-abi-verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
assert len(required) > 200 and not missing
