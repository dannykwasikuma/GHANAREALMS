#!/bin/bash
# GhanaRealms - Cloudflare Tunnel setup for the Paystack webhook
# Run this ON YOUR VPS, after setup.sh, before you configure Paystack's
# webhook URL in their dashboard.
#
# This gives GhanaRealmsPaystack's webhook listener (default port 8085,
# see plugins-to-build/GhanaRealmsPaystack/src/main/resources/config.yml)
# a stable public HTTPS URL, without buying a domain.

set -e
cd "$(dirname "$0")"

TUNNEL_NAME="ghanarealms-paystack"
WEBHOOK_PORT="8085"   # must match WEBHOOK.PORT in GhanaRealmsPaystack's config.yml

echo "=== 1. Installing cloudflared ==="
if ! command -v cloudflared >/dev/null 2>&1; then
  # Official Cloudflare install instructions for Debian/Ubuntu
  curl -L --output cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
  sudo dpkg -i cloudflared.deb || sudo apt-get install -f -y
  rm -f cloudflared.deb
fi
cloudflared --version

echo ""
echo "=== 2. Log in to your Cloudflare account ==="
echo "This opens a URL you need to open in a browser and approve - it CANNOT"
echo "be automated, this is Cloudflare's own auth flow, same as logging into"
echo "any account. If this VPS has no browser, copy the printed URL to your"
echo "phone/laptop and open it there."
cloudflared tunnel login

echo ""
echo "=== 3. Create the named tunnel (skips if it already exists) ==="
if ! cloudflared tunnel list | grep -q "$TUNNEL_NAME"; then
  cloudflared tunnel create "$TUNNEL_NAME"
else
  echo "Tunnel '$TUNNEL_NAME' already exists, reusing it."
fi

TUNNEL_ID=$(cloudflared tunnel list | grep "$TUNNEL_NAME" | awk '{print $1}')
echo "Tunnel ID: $TUNNEL_ID"

echo ""
echo "=== 4. Write tunnel config ==="
mkdir -p ~/.cloudflared
cat > ~/.cloudflared/config.yml << EOF
tunnel: $TUNNEL_ID
credentials-file: $HOME/.cloudflared/$TUNNEL_ID.json

ingress:
  - service: http://localhost:${WEBHOOK_PORT}
EOF

echo ""
echo "=== 5. Route a hostname to this tunnel ==="
echo "If you own a domain in Cloudflare already, run:"
echo "  cloudflared tunnel route dns $TUNNEL_NAME paystack.yourdomain.com"
echo ""
echo "If you DON'T have a domain, Cloudflare can still give you a stable"
echo "*.cfargotunnel.com address without one - that's what 'quick tunnels'"
echo "upgrade to once you're logged in. Run this instead:"
echo "  cloudflared tunnel token $TUNNEL_NAME"
echo "and use the resulting address in the ingress hostname, or just run"
echo "the tunnel and note the URL it prints on start."

echo ""
echo "=== 6. Start the tunnel ==="
echo "For a permanent background service:"
echo "  sudo cloudflared service install"
echo "  sudo systemctl start cloudflared"
echo ""
echo "Or run it in the foreground to test first:"
echo "  cloudflared tunnel run $TUNNEL_NAME"
echo ""
echo "Whatever hostname you end up with, set it (with /ghanarealms/paystack/webhook"
echo "appended, or whatever WEBHOOK.PATH you configured) as the Webhook URL"
echo "in the Paystack dashboard: Settings -> API Keys & Webhooks."
