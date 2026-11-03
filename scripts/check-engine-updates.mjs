import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
const build = readFileSync(new URL('../app/build.gradle',import.meta.url),'utf8');
const current = build.match(/geckoview:geckoview:([^']+)/)?.[1];
if (!current) throw new Error('Pinned engine missing');
const xml = execFileSync('curl',['-fsSL','--max-time','30',
  'https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml'],{maxBuffer:2000000}).toString();
const available = xml.match(/<release>([^<]+)<\/release>/)?.[1];
if (!available || !/^\d+\.0\.\d{14}$/.test(available)) throw new Error('Unexpected release metadata');
console.log(JSON.stringify({current,available,updateAvailable:current!==available,
  securityAdvisories:'https://www.mozilla.org/en-US/security/known-vulnerabilities/firefox/',
  action:current!==available?'Review security fixes and test isolated MP4/WebM before updating.':'Pinned engine matches the stable Maven release.'},null,2));
if(current!==available)process.exitCode=2;
