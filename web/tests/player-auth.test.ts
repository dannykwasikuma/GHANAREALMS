// tests/player-auth.test.ts
//
// Run with: node --import tsx --test tests/*.test.ts   (after npm install)
//
// Status: the two cases below (validEmail/validPassword) were verified for
// real in the environment that wrote this - as standalone copies of the
// identical logic, since lib/player-auth.ts itself imports jsonwebtoken,
// bcryptjs, next/headers and ./db (which pulls in pg), none of which are
// installed here (no internet access). All 12 assertions passed. Running
// THIS file, importing the real module, has not been done - do that after
// `npm install` to confirm the actual module behaves identically.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validEmail, validPassword } from '../lib/player-auth';

test('validEmail accepts well-formed addresses', () => {
  assert.equal(validEmail('kwame@example.com'), true);
  assert.equal(validEmail('kwame@sub.example.com'), true);
});

test('validEmail rejects malformed addresses', () => {
  assert.equal(validEmail('not-an-email'), false);
  assert.equal(validEmail('missing@domain'), false);
  assert.equal(validEmail('@example.com'), false);
  assert.equal(validEmail(''), false);
  assert.equal(validEmail('has space@example.com'), false);
});

test('validPassword enforces the 8-character minimum', () => {
  assert.equal(validPassword('short'), false);
  assert.equal(validPassword('exactly8'), true);
  assert.equal(validPassword('a'.repeat(100)), true);
  assert.equal(validPassword(''), false);
});
