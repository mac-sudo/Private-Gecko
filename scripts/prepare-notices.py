#!/usr/bin/env python3
"""Generate local notices from the resolved runtime artifacts and cached POMs.

Run ./gradlew :app:runtimeInventory first. Does not read signing material.
"""
import hashlib, io, json, os, pathlib, zipfile, xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
cache = pathlib.Path(os.environ.get('GRADLE_USER_HOME', pathlib.Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
inventory = json.loads((root/'app/build/reports/runtime-inventory.json').read_text())
assets = root/'app/src/main/assets/documents'
assets.mkdir(parents=True, exist_ok=True)
ns = {'m':'http://maven.apache.org/POM/4.0.0'}
lines = ['# Лицензии и исходники', '',
    'Собственная оболочка: MIT, см. LICENSE. Сторонние компоненты сохраняют свои лицензии.',
    'MAX и Mozilla не являются авторами или спонсорами этой оболочки.', '',
    '## GeckoView', '',
    'GeckoView 159.0.20261022150525, MPL-2.0 и лицензии вложенных компонентов.',
    'Исходники точной ревизии (не модифицированы этим проектом):',
    'https://hg.mozilla.org/releases/mozilla-release/rev/5add844c26613d7e4d84c799d6e23cf987b1240d',
    'Архив исходников:',
    'https://hg.mozilla.org/releases/mozilla-release/archive/5add844c26613d7e4d84c799d6e23cf987b1240d.tar.gz',
    'Альтернативный официальный архив релиза Firefox 159.0:',
    'https://archive.mozilla.org/pub/firefox/releases/159.0/source/firefox-159.0.source.tar.xz',
    'Java-исходники точного Maven-артефакта:',
    'https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/159.0.20261022150525/geckoview-159.0.20261022150525-sources.jar',
    'MPL: https://www.mozilla.org/MPL/2.0/',
    'Полные уведомления вложенных компонентов доступны в Настройки → Лицензии компонентов Gecko.', '',
    '## Разрешённые runtime-артефакты (по опубликованным POM)', '']
notices = []
for item in inventory:
    group, artifact, version = item['coordinate'].split(':')
    directory = cache/group/artifact/version
    candidates = list(directory.glob('*/'+item['artifact']))
    if len(candidates) != 1: raise SystemExit('Artifact missing: '+item['coordinate'])
    data = candidates[0].read_bytes()
    if hashlib.sha256(data).hexdigest() != item['sha256']: raise SystemExit('Artifact hash mismatch')
    poms = list(directory.glob('*/*.pom'))
    if not poms: raise SystemExit('POM missing: '+item['coordinate'])
    pom = ET.parse(poms[0])
    license_pom = pom
    for depth in range(8):
        licenses = [{'name':node.findtext('m:name', namespaces=ns), 'url':node.findtext('m:url', namespaces=ns)}
                    for node in license_pom.findall('m:licenses/m:license', ns)]
        if licenses: break
        parent = license_pom.find('m:parent', ns)
        if parent is None: break
        coords = [parent.findtext('m:'+field,namespaces=ns) for field in ['groupId','artifactId','version']]
        parent_poms = list((cache/coords[0]/coords[1]/coords[2]).glob('*/*.pom'))
        if not parent_poms: break
        license_pom = ET.parse(parent_poms[0])
    if not licenses: raise SystemExit('License declaration missing: '+item['coordinate'])
    item['licenses'] = licenses
    item['source'] = pom.findtext('m:scm/m:url', namespaces=ns) or pom.findtext('m:url', namespaces=ns)
    lines += [item['coordinate'], '; '.join((lic['name'] or '')+' — '+(lic['url'] or '') for lic in licenses), '']
    if group == 'org.mozilla.geckoview':
        with zipfile.ZipFile(io.BytesIO(data)) as aar:
            with zipfile.ZipFile(io.BytesIO(aar.read('assets/omni.ja'))) as omni:
                (assets/'GECKO-LICENSE.html').write_bytes(omni.read('chrome/toolkit/content/global/license.html'))
    # Preserve upstream notices already shipped in runtime JAR/AAR distributions.
    def extract_notices(blob, prefix):
        with zipfile.ZipFile(io.BytesIO(blob)) as archive:
            for name in sorted(archive.namelist()):
                base = pathlib.PurePosixPath(name).name.lower()
                if (base.startswith(('license', 'notice', 'copying')) and not name.endswith('/')
                        and archive.getinfo(name).file_size < 1000000):
                    notices.append('\n## '+prefix+' / '+name+'\n\n'+archive.read(name).decode('utf-8',errors='replace'))
                elif name == 'classes.jar': extract_notices(archive.read(name), prefix+'/classes.jar')
    extract_notices(data, item['coordinate'])
lines += ['## Инструменты и тесты', '',
    'Gradle Wrapper 9.8.0: Apache-2.0, https://github.com/gradle/gradle/tree/v9.8.0',
    'JUnit 4.13.2 (только тесты): EPL-1.0, https://junit.org/junit4/license.html',
    'Hamcrest (только тесты): BSD-3-Clause, https://github.com/hamcrest/JavaHamcrest',
    'flower.mp4 / flower.webm (только verification): CC0, MDN interactive-examples:',
    'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4',
    'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.webm', '',
    'Google Play Services используют Android SDK License, не MIT/Apache оболочки.',
    'Наличие классов не означает включение FIDO: WebAuthn отключён.',
    'Лицензии компонентов Gecko содержат дополнительные условия, в том числе для мультимедиа.', '',
    '## Уведомления из артефактов', *notices]
text = '\n'.join(lines)+'\n'
(root/'THIRD_PARTY_NOTICES.md').write_text(text)
(assets/'THIRD_PARTY_NOTICES.md').write_text(text+'\n'+(root/'LICENSE').read_text())
(root/'DEPENDENCIES.json').write_text(json.dumps(inventory,ensure_ascii=False,indent=2)+'\n')
for name in ['PRIVACY.md','UPDATES.md']:
    (assets/name).write_bytes((root/name).read_bytes())
print('Prepared notices for',len(inventory),'runtime artifacts; no signing files read.')
