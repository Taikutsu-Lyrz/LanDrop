import assert from 'node:assert/strict';
import fs from 'node:fs';
import {build} from 'esbuild';

// Bundle the actual application runtime, including its JSON dictionaries.
const result = await build({entryPoints: ['src/lib/i18n.ts'], bundle: true, write: false, platform: 'node', format: 'esm'});
const runtime = await import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`);
const storage = new Map();
globalThis.window = {localStorage: {getItem: (key) => storage.get(key), setItem: (key, value) => storage.set(key, value)}};
for (const {code} of runtime.supportedLanguages) {
  runtime.setAppLanguage(code);
  assert.equal(runtime.getInitialLanguage(), code, `saved language ${code}`);
  if (code === 'en') { assert.equal(runtime.translateText('Settings'), 'Settings'); continue; }
  const catalog = JSON.parse(fs.readFileSync(`locales/${code}.json`, 'utf8'));
  const phrases = {...catalog.shared, ...catalog.desktop};
  assert.equal(runtime.translateText('Settings'), phrases.Settings);
  assert.equal(runtime.translateText('  Settings  ', code), `  ${phrases.Settings}  `);
  const nestedWrapper = '${result.message} Commands: ${result.commands.join(" && ")}';
  const innerMessage = 'Direct cable IP setup is only available on Windows.';
  const expectedNested = phrases[nestedWrapper]
    .replace('${result.message}', phrases[innerMessage])
    .replace('${result.commands.join(" && ")}', 'netsh interface ip set address');
  assert.equal(
    runtime.translateText(`${innerMessage} Commands: netsh interface ip set address`, code),
    expectedNested,
    `${code}: nested result.message`
  );
  // Real templates can reorder device names, counts, paths and byte values.
  for (const [source, target] of Object.entries(phrases).filter(([key]) => key.includes('${'))) {
    const values = new Map();
    const pattern = /\$\{[^}]+\}|\$[A-Za-z_]\w*/g;
    const input = source.replace(pattern, (token) => {
      if (!values.has(token)) values.set(token, `value${values.size}漢`);
      return values.get(token);
    });
    const expected = target.replace(pattern, (token) => values.get(token) ?? token);
    assert.equal(runtime.translateText(input, code), expected, `${code}: ${source}`);
  }
}
storage.set('landrop.language', 'invalid-language');
assert.equal(runtime.getInitialLanguage(), 'en');
assert.equal(runtime.translateText('Settings', 'en'), 'Settings');
console.log('Language switching, persistence, English fallback, whitespace and named-template interpolation passed');
