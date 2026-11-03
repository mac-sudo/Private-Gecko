import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
import crypto from 'node:crypto';

const apk = process.argv[2];
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || `${process.env.HOME}/Library/Android/sdk`;
const aapt = `${sdk}/build-tools/36.0.0/aapt`;
const run = (cmd, args) => execFileSync(cmd, args, {maxBuffer: 80 * 1024 * 1024});
const xml = run(aapt, ['dump', 'xmltree', apk, 'AndroidManifest.xml']).toString();
const nodes = [], stack = [];
for (const line of xml.split('\n')) {
  const element = line.match(/^(\s*)E: ([\w-]+)/);
  if (element) {
    const indent = element[1].length;
    while (stack.length && stack.at(-1).indent >= indent) stack.pop();
    const node = {tag: element[2], indent, attrs: {}, parent: stack.at(-1)?.tag};
    nodes.push(node); stack.push(node); continue;
  }
  const attr = line.match(/^\s*A: ([\w:]+)(?:\([^)]*\))?=(.*)$/);
  if (attr && stack.length) {
    const quoted = attr[2].match(/^"([^"]*)"/);
    stack.at(-1).attrs[attr[1]] = quoted ? quoted[1] : attr[2].trim();
  }
}
const classes = [];
const entries = run('unzip', ['-Z1', apk]).toString().trim().split('\n');
for (const entry of entries.filter(e => /^classes\d*\.dex$/.test(e))) {
  const dex = run('unzip', ['-p', apk, entry]);
  if (dex.subarray(0, 4).toString() !== 'dex\n') throw new Error('Unexpected dex format');
  const strings = dex.readUInt32LE(60), types = dex.readUInt32LE(68);
  const count = dex.readUInt32LE(96), defs = dex.readUInt32LE(100);
  for (let i = 0; i < count; i++) {
    const type = dex.readUInt32LE(defs + i * 32);
    const string = dex.readUInt32LE(types + type * 4);
    let offset = dex.readUInt32LE(strings + string * 4);
    while (dex[offset++] & 128) {}
    classes.push(dex.subarray(offset, dex.indexOf(0, offset)).toString());
  }
}
const markers = ['com/google/firebase/', 'com/google/android/gms/measurement/',
  'com/google/android/gms/ads/identifier/', 'com/appsflyer/', 'com/adjust/sdk/',
  'com/facebook/appevents/', 'io/appmetrica/', 'com/yandex/metrica/', 'io/sentry/',
  'com/google/android/gms/fido/', 'com/google/android/gms/common/',
  'org/mozilla/telemetry/', 'mozilla/telemetry/glean/'];
const summary = {
  apkSha256: crypto.createHash('sha256').update(fs.readFileSync(apk)).digest('hex'),
  manifest: nodes.find(n => n.tag === 'manifest')?.attrs,
  application: nodes.find(n => n.tag === 'application')?.attrs,
  permissions: nodes.filter(n => ['permission','uses-permission'].includes(n.tag)),
  components: nodes.filter(n => ['activity','activity-alias','service','receiver','provider'].includes(n.tag)),
  classCount: classes.length,
  appClasses: classes.filter(c => c.startsWith('Lru/drtrip/')),
  libraryMarkerClassCounts: Object.fromEntries(markers.map(m => [m, classes.filter(c => c.startsWith('L'+m)).length])),
  firebaseClasses: classes.filter(c => c.startsWith('Lcom/google/firebase/')),
  assetEntries: entries.filter(e => e.startsWith('assets/') && e !== 'assets/omni.ja'),
  caveat: 'Class-prefix inventory is not a proof of absence of analytics, native telemetry or web trackers.'
};
console.log(JSON.stringify(summary, null, 2));
