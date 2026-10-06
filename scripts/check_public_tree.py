#!/usr/bin/env python3
"""Fail CI if credential containers or private-key blocks enter the source tree."""
from pathlib import Path
import re
import hashlib
import subprocess

root = Path(__file__).resolve().parents[1]
names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
blocked_suffixes = {'.pk8', '.p7b', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.apk', '.aab'}
private_block = re.compile(rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}')
# 仅允许这一份有意公开的开发密钥，其他密钥继续禁止。
public_development_keystore = 'dev-signing/diplay-development.jks'
public_development_keystore_sha256 = '32e4b0c23c75479a4ac5cce1119bddaee0806c864c6056fde672cadb2c19079f'
failures = []
for name in filter(None, names):
    path = root / name
    if not path.is_file():
        continue
    if name == public_development_keystore:
        if hashlib.sha256(path.read_bytes()).hexdigest() != public_development_keystore_sha256:
            failures.append(name)
        continue
    if '.private' in path.relative_to(root).parts or path.suffix.lower() in blocked_suffixes:
        failures.append(name)
    elif private_block.search(path.read_bytes()):
        failures.append(name)
if failures:
    raise SystemExit('Credential or distribution files are forbidden in the public tree:\n' + '\n'.join(failures))
print('Public tree check passed: no unexpected credential containers or private-key blocks.')
