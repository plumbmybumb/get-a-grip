#!/usr/bin/env python3
"""Generate bundled notices from upstream texts and the resolved release POM inventory.
First run: ./android/build.sh --no-configuration-cache -I scripts/dependency-licenses.gradle :app:publicDependencyLicenses
"""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
poms = sorted((root / 'android/app/build/reports/runtime-poms').glob('*.pom'))
if not poms:
    raise SystemExit('Run the dependency-licenses Gradle task first.')
rows = []
for pom in poms:
    tree = ET.parse(pom).getroot()
    coordinate = ':'.join(pom.stem.rsplit('_', 2))
    names = [e.text for e in tree.findall('m:licenses/m:license/m:name', ns)]
    if not names:
        if coordinate.startswith(('com.google.zxing:core:', 'com.google.guava:listenablefuture:')):
            names = ['Apache-2.0 (upstream license; inherited POM metadata)']
        else:
            raise SystemExit(f'Missing license: {coordinate}')
    if coordinate.startswith(('com.google.android.gms:', 'com.google.mlkit:', 'com.google.firebase:')):
        raise SystemExit(f'Review non-FOSS runtime: {coordinate}')
    rows.append(f'{coordinate} — {", ".join(names)}')

text = '''Get a Grip — third-party notices

Original app source and assets: Copyright 2026 Nuri Bruner, MPL 2.0.
Third-party material retains its own license; MPL does not replace those terms.
Source: https://github.com/plumbmybumb/get-a-grip

Gauge protocol support includes ports from hangtime-grip-connect:
Copyright (c) 2024 Stevie-Ray Hartog, BSD-2-Clause.
https://github.com/Stevie-Ray/hangtime-grip-connect

Android BLE transport: Nordic Semiconductor, BSD-3-Clause.
DataStore's repackaged Protobuf: Google and contributors, BSD-3-Clause.
AndroidX/Compose, Kotlin/kotlinx, ZXing, ZXing Android Embedded, Guava, and
other Apache-licensed components are used under Apache License 2.0.
The Gradle wrapper is also Apache-2.0. Test/build tools retain their own licenses
and are downloaded separately; they are not relicensed by this repository.

Apple's SDK frameworks and system symbols are platform-provided and are not
redistributed as open-source implementations here. Get a Grip is not affiliated
with or endorsed by Apple, Google, or the supported gauge manufacturers.

Resolved Android release components (including platform/BOM metadata):
'''
text += '\n'.join(rows) + '\n'
for path in sorted((root / 'LICENSES').glob('*.txt')):
    text += '\n' + '=' * 72 + '\n' + path.name + '\n\n' + path.read_text()
for dest in ['THIRD_PARTY_NOTICES.txt', 'Sources/Legal/THIRD_PARTY_NOTICES.txt',
             'android/app/src/main/assets/THIRD_PARTY_NOTICES.txt']:
    p = root / dest
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)
for dest in ['Sources/Legal/MPL-2.0.txt', 'android/app/src/main/assets/MPL-2.0.txt']:
    p = root / dest
    p.write_text((root / 'LICENSE').read_text())
print(f'Wrote bundled notices for {len(rows)} resolved components.')
