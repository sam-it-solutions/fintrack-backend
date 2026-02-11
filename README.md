# Fintrack Backend

## Local run
1. Start Postgres:

```bash
cd /Users/sampoelmans/Documents/SamITSolutions/financesp/finance-backend
docker compose up -d
```

2. Configure secrets in `src/main/resources/application.yml`:
- `fintrack.crypto.secret` must be a base64-encoded 16/24/32 byte key.
- `fintrack.providers.tink.client-id` and `client-secret` for open banking.
- `fintrack.providers.enablebanking` settings for Enable Banking (app IDs + key paths).
- `fintrack.app.frontend-url` and `fintrack.app.backend-url` for callbacks.
- Optional mail settings under `spring.mail` and `fintrack.mail`.

3. Start the API:

```bash
./mvnw spring-boot:run
```

### Local env helper
For Enable Banking, you can use the local helper script (reads `.env.local`):

```bash
./run-local.sh
```

## Tink callback flow
- The redirect URL should point to:
  `/api/providers/tink/callback`
- After the bank flow completes, the backend triggers a sync and redirects to `fintrack.app.frontend-url`.

## Enable Banking callback flow
- The redirect URL should point to:
  `/api/providers/enablebanking/callback`
- After the bank flow completes, the backend triggers a sync and redirects to `fintrack.app.frontend-url`.

## API quick test
```bash
# Register
curl -X POST http://localhost:8085/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"secret"}'

# Use the token from the response
TOKEN=...

# List providers
curl -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/providers

# Create connection (example: Bitvavo)
curl -X POST http://localhost:8085/api/connections \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"providerId":"bitvavo","displayName":"Bitvavo","config":{"apiKey":"...","apiSecret":"..."}}'

# Sync connection
curl -X POST http://localhost:8085/api/connections/{id}/sync \
  -H "Authorization: Bearer $TOKEN"

# Summary
curl -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/finance/summary
```

## Notes
- Sync endpoints update accounts + transactions per provider.
- Bank connections require re-auth every 90 days (PSD2 requirement).
# fintrack-backend
