# Reports & Audit Export

Category G, #25. Turns the audit trails the platform already keeps in JCR into
operator-facing reports, as JSON or CSV.

| Report | Source | Contents |
|---|---|---|
| `sales` | order resources (`/content/commerce/orders/raw`) | daily orders + revenue per currency, totals, top products |
| `operations` | outbound-write audit (`/content/commerce/sync`, #2) | every CMS → Shopify write: when, action, status, error |

## Endpoint

```
GET …/endpoints/reports.groovy?type=sales&days=30[&format=json|csv]
GET …/endpoints/reports.groovy?type=operations&days=30[&status=ok|failed|dryrun][&format=json|csv]
```

- `format=json` (default) returns the structured report; `format=csv` streams a
  spreadsheet-friendly file with a `Content-Disposition` attachment.
- `days` bounds the window (1–365); the traversal only opens the month folders that
  window touches.

Sales CSV columns: `date, orders, currency, revenue` (one row per date × currency).
Operations CSV columns: `at, action, status, error`.

Lives outside `/content/public`, so the CGI enforces authentication and ACLs.

## Notes

- Built on `commerce.Reports` (pure, defensive JCR traversal) — see
  [commerce-shared-classes.md](commerce-shared-classes.md).
- Revenue is summed **per currency** (never across currencies).
- The inbound event log (#1/#4) is exported separately via the events endpoint
  (`events.groovy`), and reconciliation drift via `reconcile.groovy`; together with
  this endpoint they cover sales + the inbound / outbound / consistency audit trails.
