#!/usr/bin/env bash
# Dedicated, restricted account for H5 and management listeners on server loopback.
set -euo pipefail
test "$(id -u)" = 0 || exit 1
wuye_stage="$(cd -- "$(dirname -- "$0")" && pwd)"
wuye_ssh_config=/etc/ssh/sshd_config.d/90-wuye-tunnel.conf
test -d /etc/ssh/sshd_config.d
grep -Eq '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config.d/\*\.conf' /etc/ssh/sshd_config
if test -e "$wuye_ssh_config" && ! head -n 1 "$wuye_ssh_config" | grep -q '^# Managed by wuye domain ingress'; then
    echo 'Refusing to replace unmanaged SSH configuration.' >&2
    exit 1
fi
if id wuye-tunnel >/dev/null 2>&1; then
    test "$(getent passwd wuye-tunnel | cut -d: -f6)" = /var/lib/wuye-tunnel
    test "$(getent passwd wuye-tunnel | cut -d: -f7)" = /usr/sbin/nologin
else
    useradd --system --create-home --home-dir /var/lib/wuye-tunnel --shell /usr/sbin/nologin --password '*' wuye-tunnel
fi
wuye_public_key="$(tr -d '\r\n' < "$wuye_stage/tunnel.pub")"
printf '%s\n' "$wuye_public_key" | grep -Eq '^ssh-ed25519 [A-Za-z0-9+/=]+ wuye-domain-tunnel$'
install -d -m 700 -o wuye-tunnel -g wuye-tunnel /var/lib/wuye-tunnel/.ssh
touch /var/lib/wuye-tunnel/.ssh/authorized_keys
wuye_backup="/var/backups/wuye-ingress/ssh-$(date -u +%Y%m%dT%H%M%SZ)-$$"
install -d -m 700 "$wuye_backup"
test ! -f "$wuye_ssh_config" || cp -a "$wuye_ssh_config" "$wuye_backup/tunnel.conf"
cp -a /var/lib/wuye-tunnel/.ssh/authorized_keys "$wuye_backup/authorized_keys"
# Replace only this deployment key's restrictions, preserving unrelated entries.
python3 - "$wuye_public_key" <<'WUYE_KEYS'
import pathlib, sys
key = sys.argv[1]
path = pathlib.Path('/var/lib/wuye-tunnel/.ssh/authorized_keys')
lines = [line for line in path.read_text().splitlines() if not line.endswith(' ' + key)]
lines.append('restrict,port-forwarding,permitlisten="127.0.0.1:18080",permitlisten="127.0.0.1:18081",command="/usr/sbin/nologin" ' + key)
path.write_text('\n'.join(lines) + '\n')
WUYE_KEYS
chmod 600 /var/lib/wuye-tunnel/.ssh/authorized_keys
chown wuye-tunnel:wuye-tunnel /var/lib/wuye-tunnel/.ssh/authorized_keys
cat > "$wuye_ssh_config" <<'WUYE_SSH'
# Managed by wuye domain ingress.
Match User wuye-tunnel
    AuthenticationMethods publickey
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowTcpForwarding remote
    AllowStreamLocalForwarding no
    PermitListen 127.0.0.1:18080 127.0.0.1:18081
    PermitTTY no
    PermitTunnel no
    X11Forwarding no
    AllowAgentForwarding no
    GatewayPorts no
Match all
WUYE_SSH
if ! /usr/sbin/sshd -t; then
    cp -a "$wuye_backup/authorized_keys" /var/lib/wuye-tunnel/.ssh/authorized_keys
    if test -f "$wuye_backup/tunnel.conf"; then
        cp -a "$wuye_backup/tunnel.conf" "$wuye_ssh_config"
    else
        rm -f -- "$wuye_ssh_config"
    fi
    echo 'SSH validation failed; previous configuration restored.' >&2
    exit 1
fi
systemctl reload ssh
printf 'Restricted tunnel account configured.\nBackup: %s\n' "$wuye_backup"
