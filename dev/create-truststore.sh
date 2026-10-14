#!/bin/sh

# This file is part of eduhub-rio-mapper
#
# Copyright (C) 2022 SURFnet B.V.
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

set -ex

PEM_FILE="$(dirname "$0")/staat-der-nederlanden-private-root.pem"
TRUSTSTORE_DEST_DIR="$(dirname "$0")/../resources"

keytool -import \
        -file "$PEM_FILE" \
        -alias firstCA \
        -keystore "$TRUSTSTORE_DEST_DIR/truststore.jks"
