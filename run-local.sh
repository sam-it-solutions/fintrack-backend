#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT/.env.local"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

: "${ENABLE_BANKING_ENV:=sandbox}"
: "${ENABLE_BANKING_PRIVATE_KEY_PATH:=/Users/sampoelmans/Downloads/190ca720-88f7-485e-98f5-09ba7c50bda0.pem}"
: "${ENABLE_BANKING_PRIVATE_KEY_PROD_PATH:=/Users/sampoelmans/Downloads/5b39c89a-3eb9-43ad-92ca-34daf3f72382.pem}"

export ENABLE_BANKING_ENV
export ENABLE_BANKING_PRIVATE_KEY_PATH
export ENABLE_BANKING_PRIVATE_KEY_PROD_PATH

exec "$ROOT/mvnw" spring-boot:run
