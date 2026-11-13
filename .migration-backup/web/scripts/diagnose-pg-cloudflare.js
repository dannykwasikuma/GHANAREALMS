// Diagnostic only - never fails the build. Prints what is actually on disk for
// pg-cloudflare so a Cloudflare build log shows whether dist/ exists in the
// installed package, and which pg-cloudflare versions are installed.
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..', 'node_modules');
function show(label, p) {
  console.log(`[diagnose] ${label}: ${fs.existsSync(p) ? 'EXISTS' : 'MISSING'}  (${p})`);
}
try {
  const dir = path.join(root, 'pg-cloudflare');
  show('pg-cloudflare package.json', path.join(dir, 'package.json'));
  show('pg-cloudflare dist/index.js', path.join(dir, 'dist', 'index.js'));
  if (fs.existsSync(dir)) console.log('[diagnose] pg-cloudflare contents:', fs.readdirSync(dir).join(', '));
  if (fs.existsSync(path.join(dir, 'package.json'))) {
    const pkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
    console.log('[diagnose] pg-cloudflare version:', pkg.version, 'exports:', JSON.stringify(pkg.exports));
  }
  show('pg lib/stream.js', path.join(root, 'pg', 'lib', 'stream.js'));
} catch (e) {
  console.log('[diagnose] non-fatal error:', e.message);
}
