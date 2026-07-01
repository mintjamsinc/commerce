// Bulk job intake: create a QUEUED bulk job (idempotent per type) so the single lane picks
// it up. Producers (schedules, other batches) send here instead of calling Shopify directly.
// Defensive: never throws.

import commerce.BulkJobs

def type = (binding.hasVariable("bulkJobType") ? binding.getVariable("bulkJobType")?.toString()?.trim() : null)
if (!type) {
    log.warn("enqueueBulkJob: no bulkJobType - skipping")
    return
}

// Idempotent: do not pile up duplicate active jobs of the same type.
if (BulkJobs.hasActive(repositorySession, type)) {
    log.info("enqueueBulkJob: a ${type} job is already QUEUED/RUNNING - not enqueuing another")
    return
}

def jobId = BulkJobs.create(repositorySession, log, type)
if (jobId != null) {
    log.info("enqueueBulkJob: queued ${type} job ${jobId}")
}
