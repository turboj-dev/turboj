const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');
const glob = require('@actions/glob');

(async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'turboj-upload-glob-'));
  const repo = path.join(root, 'repo');
  const outside = path.join(root, 'outside-applications');
  const own = path.join(repo, 'app/target/surefire-reports/TEST-owned.xml');
  const nested = path.join(repo, 'refactorj/refactorj-jdt/target/failsafe-reports/TEST-nested.xml');
  const foreign = path.join(outside, 'Foreign.app/target/surefire-reports/TEST-outside.xml');
  for (const file of [own, nested, foreign]) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, '<testsuite/>\n');
  }
  const alias = path.join(repo, 'target/package.owned/image/Applications');
  fs.mkdirSync(path.dirname(alias), { recursive: true });
  fs.symlinkSync(outside, alias, 'dir');
  const broad = await (await glob.create(path.join(repo, '**/target/surefire-reports/*.xml'))).glob();
  const patterns = ['*/target/surefire-reports/*.xml', '*/*/target/surefire-reports/*.xml', '*/target/failsafe-reports/*.xml', '*/*/target/failsafe-reports/*.xml'];
  const bounded = await (await glob.create(patterns.map(p => path.join(repo, p)).join('\n'))).glob();
  assert(broad.some(p => p.endsWith('/TEST-outside.xml')), 'Broad pattern must reproduce traversal beyond repository');
  assert.deepEqual(bounded.sort(), [own, nested].sort());
  console.log(JSON.stringify({ package: '@actions/glob', version: '0.5.0', outsideSymlinkFollowedByRecursivePattern: true, broad: broad.map(p => path.relative(repo, p)), bounded: bounded.map(p => path.relative(repo, p)), result: 'PASS' }, null, 2));
})().catch(error => { console.error(error); process.exitCode = 1; });
