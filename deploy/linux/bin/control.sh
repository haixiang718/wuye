#!/usr/bin/env bash
# Explicit operator action only. Does not install or enable services automatically.
set -euo pipefail
wuye_action="${1:-status}"
case "$wuye_action" in
  status) exec systemctl --no-pager status wuye-nonpayment.target ;;
  stop) exec systemctl stop wuye-nonpayment.target ;;
  start)
    test "$(id -u)" = 0 || { echo 'Use an authorized administrator for start.' >&2; exit 1; }
    test -f /etc/wuye/READY_FOR_NONPAYMENT || { echo 'Reviewed configuration marker missing.' >&2; exit 1; }
    # Refuse the current 2GB ingress class; this threshold is not a capacity guarantee.
    wuye_mem_kib="$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)"
    test "$wuye_mem_kib" -ge 3145728 || { echo 'This host is too small for this full application bundle.' >&2; exit 1; }
    python3 /opt/wuye/current/bin/release.py verify-tree --directory /opt/wuye/current
    for wuye_service in eureka order api front job business; do
      python3 /opt/wuye/current/linux/bin/start_service.py "$wuye_service" --check
    done
    exec systemctl start wuye-nonpayment.target
    ;;
  *) echo 'Usage: control.sh start|stop|status' >&2; exit 2 ;;
esac
