#!/usr/bin/env bash
# Installs only this project's ingress site; invoke after inspecting the server.
set -euo pipefail
wuye_stage="$(cd -- "$(dirname -- "$0")" && pwd)"
wuye_mode="${1:-http}"
case "$wuye_mode" in http|https) ;; *) exit 2 ;; esac
test "$(id -u)" = 0 || { echo 'Run as root.' >&2; exit 1; }
. /etc/os-release
test "$ID" = ubuntu || { echo 'This installer has only been prepared for Ubuntu.' >&2; exit 1; }
wuye_site=/etc/nginx/sites-available/wuye-domain-ingress
wuye_link=/etc/nginx/sites-enabled/wuye-domain-ingress
if test -e "$wuye_site" && ! head -n 1 "$wuye_site" | grep -q '^# Managed by wuye domain ingress'; then
    echo 'Refusing to replace an unmanaged ingress configuration.' >&2
    exit 1
fi
if test -L "$wuye_link" && test "$(readlink "$wuye_link")" != "$wuye_site"; then
    echo 'Unexpected existing ingress symlink.' >&2
    exit 1
fi
if test -e "$wuye_link" && ! test -L "$wuye_link"; then
    echo 'Unexpected existing ingress file.' >&2
    exit 1
fi
if test "$wuye_mode" = http; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y nginx certbot
else
    test -s /etc/letsencrypt/live/wuye-ingress/fullchain.pem
    test -s /etc/letsencrypt/live/wuye-ingress/privkey.pem
fi
wuye_backup="/var/backups/wuye-ingress/$(date -u +%Y%m%dT%H%M%SZ)-$$"
install -d -m 700 "$wuye_backup"
test ! -e "$wuye_site" || cp -a "$wuye_site" "$wuye_backup/site.conf"
test ! -L "$wuye_link" || cp -a "$wuye_link" "$wuye_backup/site.link"
install -d -m 755 /var/www/wuye-ingress /var/www/wuye-ingress/acme /var/www/wuye-ingress/verify
install -m 644 "$wuye_stage/_wuye_unavailable.html" /var/www/wuye-ingress/_wuye_unavailable.html
install -m 644 "$wuye_stage/$wuye_mode.conf" "$wuye_site"
ln -sfn "$wuye_site" "$wuye_link"
if ! nginx -t; then
    if test -f "$wuye_backup/site.conf"; then
        cp -a "$wuye_backup/site.conf" "$wuye_site"
    else
        rm -f -- "$wuye_site"
    fi
    if test -L "$wuye_backup/site.link"; then
        cp -a -P "$wuye_backup/site.link" "$wuye_link"
    else
        rm -f -- "$wuye_link"
    fi
    echo 'Nginx validation failed; previous site configuration restored.' >&2
    exit 1
fi
systemctl enable nginx
if systemctl is-active --quiet nginx; then
    systemctl reload nginx
else
    systemctl start nginx
fi
if test "$wuye_mode" = https; then
    wuye_hook=/etc/letsencrypt/renewal-hooks/deploy/wuye-nginx-reload
    if test -f "$wuye_hook" && ! grep -q '^# Managed by wuye domain ingress' "$wuye_hook"; then
        echo 'Refusing to replace unmanaged certificate renewal hook.' >&2
        exit 1
    fi
    install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
    install -m 755 "$wuye_stage/renew-hook.sh" "$wuye_hook"
fi
printf 'Ingress mode: %s\nBackup: %s\n' "$wuye_mode" "$wuye_backup"
