#!/usr/bin/env python3
"""Allowlisted source archive. Never includes signing material or local caches."""
import hashlib, pathlib, re, sys, zipfile
root = pathlib.Path(__file__).resolve().parents[1]
target = pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else root/'dist/Private-Gecko-0.9.0-beta.1-source.zip'
target.parent.mkdir(parents=True,exist_ok=True)
roots = ['.github','app/src','gradle','scripts']
files = [p for name in roots for p in (root/name).rglob('*') if p.is_file()]
files += [p for p in root.iterdir() if p.is_file() and (p.suffix=='.md' or p.name in
    ['LICENSE','.gitignore','gradlew','gradlew.bat','settings.gradle','build.gradle','gradle.properties','DEPENDENCIES.json'])]
files.append(root/'app/build.gradle')
files.append(root/'app/gradle.lockfile')
blocked = re.compile(r'\.(p12|jks|keystore|pass|lineage|pem|key|apk|aab|log)$',re.I)
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(set(files)):
        name=path.relative_to(root).as_posix()
        if any(s in path.parts for s in ['__pycache__','.DS_Store']):
            continue
        if path.is_symlink() or blocked.search(name):
            raise SystemExit('Unsafe source entry: '+name)
        # Old unused launcher artwork is excluded from the public source.
        if name.startswith('app/src/main/res/mipmap') or name.startswith('app/src/main/res/drawable-nodpi/'):
            continue
        data=path.read_bytes()
        if path.suffix in ['.java','.md','.json','.xml','.gradle','.properties','.sh','.mjs','.py','.yml']:
            text=data.decode('utf-8')
            user_prefix = '/' + 'Users' + '/'
            if user_prefix in text or re.search(r'-----BEGIN (?:[A-Z ]*PRIVATE KEY)',text):
                raise SystemExit('Private path or key in '+name)
        info=zipfile.ZipInfo('Private-Gecko/'+name,(2026,11,3,0,0,0))
        info.external_attr=(0o100755 if name=='gradlew' or name.endswith('.sh') else 0o100644)<<16
        archive.writestr(info,data,compress_type=zipfile.ZIP_DEFLATED)
print(target.name,hashlib.sha256(target.read_bytes()).hexdigest())
