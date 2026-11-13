#!/usr/bin/env node
// Creates an admin user. Run with: node scripts/create-admin.js <username> <password>
// Requires DATABASE_URL to be set (source your .env.local or export it first).

require('dotenv').config({ path: '.env.local' });
const { Pool } = require('pg');
const bcrypt = require('bcryptjs');

async function main() {
  const [username, password] = process.argv.slice(2);
  if (!username || !password) {
    console.error('Usage: node scripts/create-admin.js <username> <password>');
    process.exit(1);
  }
  if (password.length < 12) {
    console.error('Password must be at least 12 characters - this is an admin account.');
    process.exit(1);
  }

  const pool = new Pool({ connectionString: process.env.DATABASE_URL });
  const hash = await bcrypt.hash(password, 12);
  try {
    await pool.query(
      'INSERT INTO admin_users (username, password_hash, role) VALUES ($1, $2, $3)',
      [username, hash, 'superadmin']
    );
    console.log(`Admin user '${username}' created.`);
  } catch (err) {
    console.error('Failed to create admin user:', err.message);
    process.exit(1);
  } finally {
    await pool.end();
  }
}

main();
