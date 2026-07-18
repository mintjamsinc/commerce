// Derive the month-nested raw-store path for an ingested payload.
//
// The single fold rule for the raw mirror stores: the node lives under the
// UTC year/month of the payload's own business timestamp (Shopify emits
// shop-local offsets — they are CONVERTED to UTC, never read as-is), so
// placement is identical no matter which path ingested the data (webhook,
// replay, bulk backfill) and never depends on the server's timezone.
//
// Inputs (script attributes, mapped from exchange headers):
//   raw_store : store segment under /content/commerce ("orders" | "payments" | "refunds")
//   raw_name  : file name (e.g. "order_123.json")
//   raw_at    : business timestamp (ISO-8601, offset honoured) — optional
//   raw_at2   : fallback timestamp when raw_at is absent — optional
// Output (?outputs=rawPath): the full resource path to store at.
//
// Defensive: a missing/unparseable timestamp folds under the current UTC
// month rather than failing the ingest.

import commerce.Api

def hv = { String name -> binding.hasVariable(name) ? binding.getVariable(name) : null }

def store = hv("raw_store")?.toString()
def name = hv("raw_name")?.toString()

def at = hv("raw_at")
if (Api.epochMs(at) == null) {
    at = hv("raw_at2")
}
def ym = Api.utcYearMonth(at)

context.setAttribute("rawPath", "/content/commerce/${store}/raw/${ym[0]}/${ym[1]}/${name}".toString())
