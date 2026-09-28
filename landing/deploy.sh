#!/usr/bin/env bash
# Publish landing/site/ to fitrahtube.com (reverse proxy -> web VM; the web root is owned by the VM
# user, so no sudo). Every host, port and credential comes from the repo-root .env (git-ignored)
# and is never echoed. Run python3 landing/build.py first.
set -euo pipefail
cd "$(dirname "$0")"
[ -f site/index.html ] || { echo "deploy.sh: run python3 landing/build.py first" >&2; exit 1; }
env_get() { grep "^$1=" ../.env | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//'; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT; chmod 700 "$tmp"
env_get SERVER_HOSTING_ADMIN_DASHBOARD_VM_PASSWORD > "$tmp/jump"
env_get ADMIN_DASHBOARD_SERVER_PASSWORD > "$tmp/vm"
jump="$(env_get SERVER_HOSTING_ADMIN_DASHBOARD_VM_USER)@$(env_get SERVER_HOSTING_ADMIN_DASHBOARD_VM_HOSTNAME)"
jump_port=$(env_get SERVER_HOSTING_ADMIN_DASHBOAURD_VM_PORT)   # key name as spelled in .env
vm="$(env_get ADMIN_DASHBOARD_SERVER_USER)@$(env_get ADMIN_DASHBOARD_SERVER_IP)"
proxy="sshpass -f $tmp/jump ssh -o PubkeyAuthentication=no -p $jump_port -W %h:%p $jump"
sshpass -f "$tmp/vm" rsync -a --delete --chmod=a+rX \
    -e "ssh -o PubkeyAuthentication=no -o ProxyCommand='$proxy'" site/ "$vm:/var/www/fitrahtube-home/"
echo "deploy.sh: published; check https://fitrahtube.com/"
