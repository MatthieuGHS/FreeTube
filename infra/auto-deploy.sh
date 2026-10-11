#!/usr/bin/env bash
# Deploys origin/main when it moves. Run every 2 minutes by freetube-deploy.timer, as ubuntu.
# The body is a function called on the last line: bash parses it whole before running it, so the
# merge below can rewrite this very file without breaking the current run.
set -euo pipefail

main() {
	cd "$(dirname "$(readlink -f "$0")")/.."
	local deployed=.git/freetube-deployed failed=.git/freetube-failed target previous
	git fetch --quiet origin main
	target=$(git rev-parse origin/main)
	# Compared with the last DEPLOYED commit, not HEAD: a failed build must not count as deployed.
	[[ $target == "$(cat "$deployed" 2>/dev/null)" ]] && return
	[[ $target == "$(cat "$failed" 2>/dev/null)" ]] && return # already failed: wait for the next push
	previous=$(cat "$deployed" 2>/dev/null || true)
	echo "deploying ${target:0:7}"
	echo "$target" >"$failed"
	git merge --ff-only --quiet origin/main # local edits or a rewritten history stop here, logged
	cd infra
	docker compose build # a failed build leaves the running containers untouched
	docker compose up -d
	# The Caddyfile is a single-file bind mount: git replaces the file, the running container keeps the old
	# one (even `caddy reload` reads it). Only a new container mounts the new file.
	if [[ -z $previous ]] || ! git diff --quiet "$previous" "$target" -- Caddyfile; then
		docker compose up -d --force-recreate caddy
	fi
	docker image prune -f >/dev/null
	docker builder prune -f --filter until=168h >/dev/null
	cd ..
	mv "$failed" "$deployed"
	echo "deployed ${target:0:7}"
}

main "$@"
