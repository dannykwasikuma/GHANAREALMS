// lib/link-code.ts
// Pure, dependency-free link-code generator (only Node's built-in crypto),
// split out of account-linking.ts so it can be tested without a database.
import crypto from 'crypto';

// No ambiguous characters (0/O, 1/I) - codes get read off a screen and typed in-game.
export const LINK_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
export const LINK_CODE_LENGTH = 6;

export function generateLinkCode(): string {
  let code = '';
  for (let i = 0; i < LINK_CODE_LENGTH; i++) {
    code += LINK_CODE_ALPHABET[crypto.randomInt(LINK_CODE_ALPHABET.length)];
  }
  return code;
}
