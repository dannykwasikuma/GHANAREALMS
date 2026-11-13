import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateLinkCode, LINK_CODE_ALPHABET, LINK_CODE_LENGTH } from '../lib/link-code';

test('link code has the expected length', () => {
  assert.equal(generateLinkCode().length, LINK_CODE_LENGTH);
});

test('link code only uses unambiguous characters', () => {
  for (let i = 0; i < 2000; i++) {
    for (const ch of generateLinkCode()) assert.ok(LINK_CODE_ALPHABET.includes(ch));
  }
  assert.ok(!/[01OI]/.test(LINK_CODE_ALPHABET));
});

test('link codes are not trivially repeating', () => {
  const seen = new Set<string>();
  for (let i = 0; i < 500; i++) seen.add(generateLinkCode());
  assert.ok(seen.size > 490);
});
