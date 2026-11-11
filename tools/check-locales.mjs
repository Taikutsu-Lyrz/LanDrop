import fs from 'node:fs';
import assert from 'node:assert/strict';

const sections = ['shared', 'android', 'desktop'];
const english = JSON.parse(fs.readFileSync('locales/en.json', 'utf8'));
const tokens = (text) => [...text.matchAll(/\$\{[^}]+\}|\$[A-Za-z_]\w*/g)].map((match) => match[0]).sort();
for (const language of ['prs-AF', 'ja', 'es', 'tr', 'pt']) {
  const catalog = JSON.parse(fs.readFileSync(`locales/${language}.json`, 'utf8'));
  const android = JSON.parse(fs.readFileSync(`android/app/src/main/res/raw/${language === "prs-AF" ? "prs_af" : language}.json`, 'utf8'));
  assert.deepEqual(catalog, android, `${language}: desktop/Android catalogs differ`);
  assert.equal(catalog.locale, language);
  assert.equal(catalog.direction, language === 'prs-AF' ? 'rtl' : 'ltr');
  for (const state of ['Not connected', 'Connected', 'Busy']) {
    assert.ok(catalog.shared[state] && catalog.shared[state] !== state, `${language}: untranslated connection state ${state}`);
  }
  for (const section of sections) {
    for (const key of Object.keys(english[section])) {
      assert.equal(typeof catalog[section]?.[key], 'string', `${language}/${section}: missing ${key}`);
      assert.ok(catalog[section][key].trim(), `${language}/${section}: empty ${key}`);
      assert.deepEqual(tokens(catalog[section][key]), tokens(key), `${language}/${section}: interpolation mismatch ${key}`);
    }
  }
  console.log(`${language}: all ${sections.reduce((n, section) => n + Object.keys(english[section]).length, 0)} catalog entries and interpolation tokens verified`);
}
