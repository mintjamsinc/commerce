# JCR Runtime Structure

Runtime paths created dynamically by Camel routes. Not stored in this repository.

## Order Storage

```
/content/commerce/orders/
├── raw/                              # Successfully received orders
│   └── {yyyy}/
│       └── {MM}/
│           └── order_{id}.json       # Raw Shopify JSON
└── error/                            # Orders that failed processing
    └── order_{id}.json               # Moved here on error
```

## Node Properties

Status is modelled on two independent axes. See
[`commerce-status.md`](commerce-status.md) for the authoritative status list.

- `commerce:status` — our **integration processing lifecycle** (closed enum:
  `received`, `threshold_pending`, `review_pending`, `monitored`, `error`,
  `deleted`).
- `commerce:source_status` — a mirror of Shopify's **business status**
  (products: `active`/`archived`/`draft`; orders: `financial_status`).

### Order properties

Each order file carries the following JCR properties:

| Property | Type | Description |
|---|---|---|
| `commerce:order_id` | String | Shopify order ID |
| `commerce:customer_email` | String | Customer email |
| `commerce:total_price` | String | Order total |
| `commerce:currency` | String | Currency code (e.g., JPY, USD) |
| `commerce:order_number` | String | Human-readable order number |
| `commerce:status` | String | Processing status: `received` / `error` |
| `commerce:source_status` | String | Shopify `financial_status` (e.g. `paid`) |
| `commerce:errorMessage` | String | Error message (on failure) |
| `commerce:stackTrace` | String | Stack trace (on failure) |

### Product properties

Each product file (`/content/commerce/products/product_{id}.json`) carries:

| Property | Type | Description |
|---|---|---|
| `commerce:product_id` | String | Shopify product ID |
| `commerce:title` | String | Product title |
| `commerce:handle` | String | URL handle |
| `commerce:status` | String | Processing status: `received` / `threshold_pending` / `review_pending` / `monitored` / `error` / `deleted` |
| `commerce:source_status` | String | Shopify business status: `active` / `archived` / `draft` |
| `commerce:vendor` | String | Vendor |
| `commerce:product_type` | String | Product type |
| `commerce:tags` | String | Comma-separated tags |
| `commerce:updated_at` | Date | Shopify `updated_at` |
| `commerce:deletedAt` | String | Deletion timestamp (set on `products/delete`) |

## Configuration

```
/etc/commerce/
├── config/
│   └── shopify.yml                   # Shopify API settings
├── routes/
│   ├── shopify/
│   │   └── order-paid.yaml           # Order received route
│   └── common/
│       └── error-handler.yaml        # Shared error handler
└── processes/                        # BPMN (future)
```

## Endpoints

```
/content/commerce/public/
└── endpoints/
    └── shopify/
        └── webhook.groovy            # Webhook receiver
```

HTTP access: `POST /bin/cms.cgi/{workspace}/commerce/public/endpoints/shopify/webhook.groovy`

## Finder App Workflow

1. Open Finder, navigate to `/content/commerce/orders/`
2. `raw/` folder: successfully received orders, organized by year/month
3. `error/` folder: failed orders with error details in properties
4. Select a file > view properties to inspect `commerce:error_log` and `commerce:error_detail`
5. After fixing the issue, drag the file back to trigger reprocessing
