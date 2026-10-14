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

# Builds resources/truststore.jks from scratch: a PKCS12 store with an
# empty password (see keystore/credentials) holding the root CAs below,
# aliased by their lowercased CN.
#
# keytool refuses passwords shorter than 6 characters, so the store is
# made with openssl instead; -jdktrust (OpenSSL >= 3.2) marks the
# certificates as Java trustedCertEntry.

DEV_DIR="$(dirname "$0")"
TRUSTSTORE="$DEV_DIR/../resources/truststore.jks"

TMP="$(mktemp "$TRUSTSTORE.XXXXXX")"
trap 'rm -f "$TMP"' EXIT

# The PEMs are passed to openssl directly rather than piped through cat:
# sh has no pipefail, so a missing PEM would silently be left out.
openssl pkcs12 -export -nokeys \
            -in "$DEV_DIR/staat-der-nederlanden-private-root.pem" \
            -certfile "$DEV_DIR/trial-pkioverheid-root-g3.pem" \
            -jdktrust anyExtendedKeyUsage \
            -caname "staat der nederlanden private root ca - g1" \
            -caname "trial pkioverheid root ca - g3" \
            -passout pass: \
            -out "$TMP"

chmod 644 "$TMP"
mv "$TMP" "$TRUSTSTORE"
