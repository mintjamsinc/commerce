// Bulk job intake: create a QUEUED bulk job (idempotent per type) so the broker picks it up.
// Producers (schedules, other batches) send here instead of calling Shopify directly. The job
// carries the data DOMAINS it touches (resolved from its type) so the lanes can serialize by
// domain (Shopify producer singleton + CMS consumer domain-parallel). Defensive: never throws.
//
// Some job types are PARAMETERIZED: a date-ranged orders backfill carries optional bulkFrom /
// bulkTo (yyyy-MM-dd) headers, stored on the job as params so the producer lane can inject a
// created_at range into the bulk query. Types without dates (inventory-full) send no bounds, so
// params stays empty and their behavior is unchanged.

import commerce.BulkJobs
import commerce.BulkQueries

def type = (binding.hasVariable("bulkJobType") ? binding.getVariable("bulkJobType")?.toString()?.trim() : null)
if (!type) {
    log.warn("enqueueBulkJob: no bulkJobType - skipping")
    return
}

// Idempotent: do not pile up duplicate active jobs of the same type (active now includes
// QUEUED/RUNNING/READY/PROCESSING).
if (BulkJobs.hasActive(repositorySession, type)) {
    log.info("enqueueBulkJob: a ${type} job is already active (QUEUED/RUNNING/READY/PROCESSING) - not enqueuing another")
    return
}

// Optional date-range params (orders backfill). A blank/absent bound is omitted, so a query
// carrying neither bound backfills everything; inventory-full sends no bounds -> params == [:].
def params = [:]
def from = (binding.hasVariable("bulkFrom") ? binding.getVariable("bulkFrom")?.toString()?.trim() : null)
def to   = (binding.hasVariable("bulkTo")   ? binding.getVariable("bulkTo")?.toString()?.trim()   : null)
if (from) params.from = from
if (to)   params.to = to

// Resolve the data domains this job type touches (e.g. inventory-full -> ["inventory"]).
def domains = BulkQueries.domainsForType(type)
def jobId = BulkJobs.create(repositorySession, log, type, domains, params)
if (jobId != null) {
    log.info("enqueueBulkJob: queued ${type} job ${jobId} domains=${domains} params=${params}")
}
