#!/bin/sh

# This file is part of eduhub-rio-mapper
#
# Copyright (C) 2026 SURFnet B.V.
#
# This program is free software: you can redistribute it and/or
# modify it under the terms of the GNU Affero General Public License
# as published by the Free Software Foundation, either version 3 of
# the License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
# Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public
# License along with this program.  If not, see
# <https://www.gnu.org/licenses/>.

# Checks that docker-compose.yml can build its services locally: every
# referenced Dockerfile must exist and no platform may be pinned (that
# forces emulation on non-amd64 hosts).

set -e

cd "$(dirname "$0")/.."

check() {
    docker compose config --no-env-resolution --format json | python3 -I -c '
import json, os, sys
errors = []
for name, service in json.load(sys.stdin)["services"].items():
    build = service.get("build")
    if build is None:
        continue
    dockerfile = os.path.join(build["context"], build.get("dockerfile", "Dockerfile"))
    if not os.path.isfile(dockerfile):
        errors.append(f"{name}: dockerfile {dockerfile} does not exist")
    if "platform" in service:
        errors.append(f"{name}: platform is pinned to " + service.get("platform"))
for error in errors:
    print(error, file=sys.stderr)
sys.exit(1 if errors else 0)
'
}

check
OOAPI_VERSION=v5 check
OOAPI_VERSION=v6 check
echo "docker-compose.yml OK"
