import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const apk = process.argv[2];
if (!apk) throw new Error('Usage: node scripts/check-release.mjs APK [--unsigned]');
const audit = JSON.parse(execFileSync(process.execPath,
  [fileURLToPath(new URL('./audit-apk.mjs', import.meta.url)), apk], {maxBuffer:2000000}));
function check(value, message) { if (!value) throw new Error(message); }
const build = readFileSync(new URL('../app/build.gradle',import.meta.url),'utf8');
check(audit.manifest.package === 'ru.drtrip.maxprivategecko.privatemediatest', 'Unexpected package');
check(audit.manifest['android:versionName'] === build.match(/versionName '([^']+)'/)[1], 'Wrong version');
check(audit.manifest['android:versionCode'] === '(type 0x10)0x'+Number(build.match(/versionCode (\d+)/)[1]).toString(16), 'Wrong versionCode');
check(audit.application['android:debuggable'] !== '(type 0x12)0xffffffff', 'Debuggable release');
check(audit.application['android:allowBackup'] === '(type 0x12)0x0', 'Backup allowed');
check(audit.application['android:usesCleartextTraffic'] === '(type 0x12)0x0', 'Cleartext allowed');
const allowed = new Set(['android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE',
  'android.permission.POST_NOTIFICATIONS','android.permission.WAKE_LOCK',
  'ru.drtrip.maxprivategecko.privatemediatest.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION']);
for (const p of audit.permissions.filter(p=>p.tag==='uses-permission'))
  check(allowed.has(p.attrs['android:name']), 'Unexpected permission: '+p.attrs['android:name']);
for (const c of audit.components.filter(c=>c.attrs['android:exported']==='(type 0x12)0xffffffff')) {
  check(c.attrs['android:name']==='ru.drtrip.maxprivategecko.MainActivity'
    || (c.attrs['android:name']==='androidx.profileinstaller.ProfileInstallReceiver'
      && c.attrs['android:permission']==='android.permission.DUMP'), 'Unexpected exported component');
}
check(!audit.appClasses.some(c=>c==='Lru/drtrip/maxprivategecko/HardeningVerification;'), 'Verification helper in release');
check(!audit.assetEntries.some(e=>/flower\.(mp4|webm)$/.test(e)), 'Fixture in release');
for (const name of ['PRIVACY.md','UPDATES.md','THIRD_PARTY_NOTICES.md','GECKO-LICENSE.html'])
  check(audit.assetEntries.includes('assets/documents/'+name), 'Missing document '+name);
const config=execFileSync('unzip',['-p',apk,'assets/privacy-gecko.yaml'],{encoding:'utf8'});
for (const pref of ['media.peerconnection.enabled','media.navigator.enabled','geo.enabled',
  'security.webauth.webauthn','toolkit.telemetry.enabled','datareporting.healthreport.uploadEnabled'])
  check(config.split('\n').some(line=>line.trim()===pref+': false'), 'Missing restriction: '+pref);
const activity=readFileSync(new URL('../app/src/main/java/ru/drtrip/maxprivategecko/MainActivity.java',import.meta.url),'utf8');
for (const setting of ['isolatedProcessEnabled(true)','remoteDebuggingEnabled(false)',
  'consoleOutput(false)','debugLogging(false)']) check(activity.includes('.'+setting), 'Source setting changed: '+setting);
const guard=JSON.parse(execFileSync('unzip',['-p',apk,'assets/max_guard/manifest.json'],{encoding:'utf8'}));
check(!guard.permissions.includes('<all_urls>'), 'Broad extension access');
const entries = execFileSync('unzip',['-Z1',apk],{encoding:'utf8'}).trim().split('\n');
check(entries.filter(e=>e.startsWith('lib/')).every(e=>e.startsWith('lib/arm64-v8a/')), 'Unexpected ABI');
check(!entries.some(e=>/\.(p12|jks|keystore|pass|lineage|pem|key)$/.test(e)), 'Possible key material packaged');
if (!process.argv.includes('--unsigned')) {
  const sdk=process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || `${process.env.HOME}/Library/Android/sdk`;
  const verified=execFileSync(`${sdk}/build-tools/36.0.0/apksigner`,
    ['verify','--min-sdk-version','28','--verbose','--print-certs',apk],{encoding:'utf8'});
  check(verified.includes('Verified using v3 scheme (APK Signature Scheme v3): true'), 'V3 missing');
  check(verified.includes('ce2879796a3b5c7063017527eef815b89c9c2cfff3187b33f418449616f46f31'), 'Wrong release certificate');
}
console.log(JSON.stringify({passed:true,version:audit.manifest['android:versionName'],sha256:audit.apkSha256,
  signed:!process.argv.includes('--unsigned'),note:'Static checks; device matrix and traffic auditing are separate.'},null,2));
