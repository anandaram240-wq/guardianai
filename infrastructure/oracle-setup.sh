#!/bin/bash
# ============================================================
# GuardianAI — Oracle Cloud Always Free Server Setup Script
# Run this ONCE on your Oracle Ubuntu ARM VM
# Cost: $0 / ₹0 / £0 — Forever Free
# ============================================================

set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; PURPLE='\033[0;35m'; CYAN='\033[0;36m'
NC='\033[0m'; BOLD='\033[1m'

log_step() { echo -e "\n${BLUE}${BOLD}[STEP]${NC} $1"; }
log_ok()   { echo -e "${GREEN}${BOLD}[✓]${NC} $1"; }
log_warn() { echo -e "${YELLOW}${BOLD}[!]${NC} $1"; }
log_err()  { echo -e "${RED}${BOLD}[✗]${NC} $1"; exit 1; }

[[ $EUID -ne 0 ]] && log_err "Run as root: sudo bash oracle-setup.sh"
PUBLIC_IP=$(curl -s ifconfig.me || echo "YOUR_IP")

echo -e "${PURPLE}${BOLD}GuardianAI Server Setup — 100% Free Stack${NC}"
echo -e "${CYAN}IP: ${PUBLIC_IP}${NC}\n"

# 1. System update
log_step "1/10 — Updating system"
apt-get update -y && apt-get upgrade -y
apt-get install -y curl wget git unzip ufw ca-certificates gnupg certbot net-tools
log_ok "System updated"

# 2. Docker
log_step "2/10 — Installing Docker"
if ! command -v docker &>/dev/null; then
  curl -fsSL https://get.docker.com | sh
  systemctl enable docker && systemctl start docker
fi
if ! command -v docker-compose &>/dev/null; then
  curl -SL "https://github.com/docker/compose/releases/download/v2.24.5/docker-compose-linux-aarch64" \
    -o /usr/local/bin/docker-compose && chmod +x /usr/local/bin/docker-compose
fi
log_ok "Docker $(docker --version | cut -d' ' -f3) ready"

# 3. Directories
log_step "3/10 — Creating directories"
mkdir -p /opt/guardianai/{adguard/{config,work},coturn,ntfy/{config,cache},api-server,ssl,logs}
chmod -R 755 /opt/guardianai
log_ok "Directories created at /opt/guardianai/"

# 4. Firewall
log_step "4/10 — Configuring UFW firewall"
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp   comment 'SSH'
ufw allow 53/tcp   comment 'DNS TCP'
ufw allow 53/udp   comment 'DNS UDP'
ufw allow 853/tcp  comment 'DNS-over-TLS'
ufw allow 80/tcp   comment 'HTTP'
ufw allow 443/tcp  comment 'HTTPS'
ufw allow 3000/tcp comment 'AdGuard Admin'
ufw allow 3001/tcp comment 'GuardianAI API'
ufw allow 8080/tcp comment 'ntfy.sh'
ufw allow 3478/tcp comment 'TURN TCP'
ufw allow 3478/udp comment 'TURN UDP'
ufw allow 5349/tcp comment 'TURNS TLS'
ufw allow 49152:65535/udp comment 'TURN Media'
ufw --force enable
log_ok "Firewall configured"

# 5. .env file
log_step "5/10 — Creating .env config"
cat > /opt/guardianai/.env << ENVEOF
# GuardianAI Server Config — Edit before starting!
DOMAIN=${PUBLIC_IP}
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_SERVICE_KEY=YOUR_SERVICE_KEY
SUPABASE_ANON_KEY=YOUR_ANON_KEY
NTFY_BASE_URL=http://${PUBLIC_IP}:8080
TURN_USERNAME=guardianai
TURN_PASSWORD=$(openssl rand -base64 24 | tr -d '\n')
PORT=3001
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
NODE_ENV=production
ADGUARD_USERNAME=admin
ADGUARD_PASSWORD=$(openssl rand -base64 16 | tr -d '\n')
ENVEOF
chmod 600 /opt/guardianai/.env
log_ok ".env created — EDIT Supabase keys!"

# 6. Coturn config
log_step "6/10 — Coturn TURN server config"
source /opt/guardianai/.env
cat > /opt/guardianai/coturn/turnserver.conf << TURNEOF
listening-port=3478
tls-listening-port=5349
fingerprint
lt-cred-mech
realm=turn.guardianai.local
server-name=guardianai-turn
user=${TURN_USERNAME}:${TURN_PASSWORD}
no-loopback-peers
no-multicast-peers
no-cli
verbose
log-file=/var/log/coturn.log
min-port=49152
max-port=65535
TURNEOF
log_ok "Coturn config written"

# 7. ntfy config
log_step "7/10 — ntfy.sh push server config"
cat > /opt/guardianai/ntfy/config/server.yml << NTFYEOF
base-url: "http://${PUBLIC_IP}:8080"
listen-http: ":80"
cache-file: /var/cache/ntfy/cache.db
cache-duration: "12h"
auth-file: /var/lib/ntfy/user.db
auth-default-access: "deny-all"
attachment-cache-dir: /var/cache/ntfy/attachments
attachment-total-size-limit: "2G"
attachment-file-size-limit: "15M"
visitor-request-limit-burst: 60
visitor-request-limit-replenish: "10s"
log-level: info
NTFYEOF
log_ok "ntfy.sh config written"

# 8. Docker Compose
log_step "8/10 — Writing docker-compose.yml"
cat > /opt/guardianai/docker-compose.yml << 'DCEOF'
version: '3.9'
services:
  adguardhome:
    image: adguard/adguardhome:latest
    container_name: guardianai-dns
    restart: always
    ports:
      - "53:53/tcp"
      - "53:53/udp"
      - "853:853/tcp"
      - "3000:3000/tcp"
      - "80:80/tcp"
      - "443:443/tcp"
    volumes:
      - ./adguard/config:/opt/adguardhome/conf
      - ./adguard/work:/opt/adguardhome/work
    networks: [guardianai]

  coturn:
    image: coturn/coturn:latest
    container_name: guardianai-turn
    restart: always
    network_mode: "host"
    volumes:
      - ./coturn/turnserver.conf:/etc/coturn/turnserver.conf:ro

  ntfy:
    image: binwiederhier/ntfy:latest
    container_name: guardianai-notify
    restart: always
    command: serve
    ports: ["8080:80"]
    volumes:
      - ./ntfy/config/server.yml:/etc/ntfy/server.yml:ro
      - ./ntfy/cache:/var/cache/ntfy
      - ntfy-data:/var/lib/ntfy
    networks: [guardianai]

  guardian-api:
    build: ./api-server
    container_name: guardianai-api
    restart: always
    ports: ["3001:3001"]
    env_file: .env
    depends_on: [adguardhome, ntfy]
    volumes: [./logs:/app/logs]
    networks: [guardianai]
    healthcheck:
      test: ["CMD","curl","-f","http://localhost:3001/health"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  ntfy-data:

networks:
  guardianai:
    driver: bridge
DCEOF
log_ok "docker-compose.yml written"

# 9. Blocklist auto-update cron
log_step "9/10 — Blocklist auto-update cron"
cat > /opt/guardianai/update-blocklists.sh << 'CRONEOF'
#!/bin/bash
source /opt/guardianai/.env
LOG="/opt/guardianai/logs/blocklist-update.log"
TS=$(date '+%Y-%m-%d %H:%M:%S')
echo "[$TS] Refreshing blocklists..." >> "$LOG"
CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "http://localhost:3000/control/filtering/refresh" \
  --user "${ADGUARD_USERNAME}:${ADGUARD_PASSWORD}" \
  -H "Content-Type: application/json" -d '{"whitelist":false}')
echo "[$TS] HTTP $CODE" >> "$LOG"
CRONEOF
chmod +x /opt/guardianai/update-blocklists.sh
(crontab -l 2>/dev/null; echo "0 3 * * * /opt/guardianai/update-blocklists.sh") | crontab -
log_ok "Daily 3 AM blocklist cron set"

# 10. Pull images
log_step "10/10 — Pulling Docker images"
cd /opt/guardianai
docker pull adguard/adguardhome:latest
docker pull coturn/coturn:latest
docker pull binwiederhier/ntfy:latest
docker pull node:20-alpine
log_ok "Images pulled"

echo -e "\n${GREEN}${BOLD}╔══════════════════════════════════════════════╗"
echo -e "║    GuardianAI Server Setup Complete! ✓       ║"
echo -e "╚══════════════════════════════════════════════╝${NC}"
echo -e "\n${CYAN}Next Steps:${NC}"
echo -e " 1. ${YELLOW}nano /opt/guardianai/.env${NC}  (add Supabase keys)"
echo -e " 2. ${YELLOW}cd /opt/guardianai && docker-compose up -d${NC}"
echo -e " 3. AdGuard admin: ${YELLOW}http://${PUBLIC_IP}:3000${NC}"
echo -e " 4. Set child DNS to: ${YELLOW}${PUBLIC_IP}${NC}"
echo -e "\n${GREEN}Cost: ₹0/month — FOREVER FREE 🎉${NC}\n"
