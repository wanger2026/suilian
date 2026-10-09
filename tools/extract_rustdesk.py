"""Unpack the pinned upstream portable payload without executing the bootstrap."""
import brotli
import hashlib
import struct
from pathlib import Path

def extract(source, destination):
    data = Path(source).read_bytes()
    marker = b'rustdesk'
    positions = [i for i in range(len(data)) if data[i:i+8] == marker]
    for start in positions:
        p = start+8
        entries = []
        try:
            while data[p:p+8] != marker:
                n = struct.unpack_from('>I', data, p)[0]; p += 4
                if not 1 <= n <= 500: raise ValueError()
                name = data[p:p+n].decode('utf-8'); p += n
                n = struct.unpack_from('>I', data, p)[0]; p += 4
                if not 1 <= n <= len(data)-p-32: raise ValueError()
                raw = data[p:p+n]; p += n
                expected = data[p:p+32].decode('ascii'); p += 32
                content = brotli.decompress(raw)
                if hashlib.md5(content).hexdigest() != expected: raise ValueError()
                entries.append((name, content))
            if not any(n.lower().endswith('rustdesk.exe') for n,_ in entries): continue
            dest = Path(destination).resolve()
            for name,content in entries:
                path = (dest/name.replace('\\','/')).resolve()
                if not path.is_relative_to(dest): raise ValueError('unsafe entry')
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            return len(entries)
        except (ValueError, UnicodeError, OSError, struct.error, brotli.error):
            continue
    raise RuntimeError('Pinned RustDesk payload not found')

if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source')
    parser.add_argument('destination')
    args = parser.parse_args()
    print(extract(args.source, args.destination))
