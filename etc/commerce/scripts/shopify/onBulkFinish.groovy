// bulk_operations/finish webhook handler. Correlates the finished bulk operation (by GID) to
// its job. On success it marks the job READY (Shopify bulk COMPLETED, awaiting a CMS ingest
// slot) and returns fast; it does NOT dispatch the result route directly. The CMS consumer
// lane (runBulkCmsLane) picks READY jobs up and dispatches the heavy download+reconcile
// domain-safely, so disjoint-domain ingests can run in parallel. On a non-completed status it
// marks the job FAILED. Defensive: never throws.

import commerce.BulkJobs

def payload
try {
    def raw = (binding.hasVariable("bulkPayload") ? binding.getVariable("bulkPayload")?.toString() : null)
    payload = raw ? JSON.parse(raw) : null
} catch (Exception e) {
    log.warn("onBulkFinish: bad payload: ${e.message}")
    return
}
if (payload == null) {
    return
}

def gid = payload.admin_graphql_api_id?.toString()
def status = payload.status?.toString()?.toLowerCase()
if (gid == null) {
    log.warn("onBulkFinish: webhook has no admin_graphql_api_id")
    return
}

def job = BulkJobs.findByGid(repositorySession, gid)
if (job == null) {
    log.info("onBulkFinish: no job for bulk ${gid} - ignoring")
    return
}

// Shopify bulk_operations/finish is AT-LEAST-ONCE: a duplicate or late-arriving finish for a job
// we have already advanced (READY / PROCESSING / a terminal state) must be a NO-OP. Only a job
// still RUNNING is actually awaiting this webhook, so gate the whole handler on that. (The guarded
// BulkJobs transitions are belt-and-suspenders on top of this.)
if (job.status?.toString() != "RUNNING") {
    log.info("onBulkFinish: ignoring finish for non-RUNNING job ${job.jobId} (status=${job.status})")
    return
}

if (status != "completed") {
    log.warn("onBulkFinish: bulk ${gid} finished status=${status} - marking job ${job.jobId} failed")
    BulkJobs.markFailed(repositorySession, log, job.jobId?.toString(), "bulk status ${status}")
    return
}

// Completed: mark READY. The CMS consumer lane will claim a domain-safe ingest slot and
// dispatch the heavy result processing (do NOT dispatch it here - that would bypass the
// per-domain serialization the CMS lane enforces).
BulkJobs.markReady(repositorySession, log, job.jobId?.toString())
log.info("onBulkFinish: bulk ${gid} completed - job ${job.jobId} marked READY (CMS lane will ingest)")
