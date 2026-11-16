// Writes the JSON.stringify reference cases used by the Java tests (SPEC §6):
// each line is "<input code units in hex> TAB <JSON.stringify output code units in hex>".
// Run: node tools/make-json-fixture.js > test/fixtures/json-stringify.txt
'use strict';
const cases = [];
for (let c = 0; c < 0x80; c++) cases.push([c]);
for (const c of [0x80, 0x9f, 0xa0, 0xad, 0x2028, 0x2029, 0xfeff, 0xfffe, 0xffff, 0xd800, 0xdbff, 0xdc00, 0xdfff]) cases.push([c]);
cases.push([0xd83d, 0xde00]);          // a surrogate pair
cases.push([0xde00, 0xd83d]);          // reversed: two lone surrogates
cases.push([0x61, 0xd800]);            // high surrogate at the end
cases.push([0xdc00, 0x61]);            // low surrogate at the start
cases.push([0x2019, 0x2014, 0x4e2d]);  // ordinary non-ASCII
cases.push([]);                        // empty string
const hex = (units) => units.map((u) => u.toString(16).padStart(4, '0')).join(' ');
for (const units of cases) {
  const s = String.fromCharCode(...units);
  const out = JSON.stringify(s);
  const outUnits = [];
  for (let i = 0; i < out.length; i++) outUnits.push(out.charCodeAt(i));
  process.stdout.write(hex(units) + '\t' + hex(outUnits) + '\n');
}
