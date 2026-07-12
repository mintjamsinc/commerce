// Boot-time one-shot migration runner.
//
// Invoked once per boot by the commerce-migrate timer route (repeatCount=1, as
// the service user). Delegates to commerce.Migrations: an ordered registry of
// idempotent migrations, each guarded by a permanent JCR marker
// (/content/commerce/migrations/{id}.json) so it runs exactly once per
// repository. Each migration follows migrate → verify → hard delete; on a
// verification failure no marker is written and the next boot retries.
//
// Best-effort: a failure is logged, never thrown.

import commerce.Migrations

// Cluster guard: the boot timer fires on every node; only the node that wins
// this lease runs the registry, the others skip (their markers are shared via
// JCR anyway). In a standalone deployment the lease is granted immediately.
def __clusterLease = cluster.tryLock("commerce-migrations", 1800000)
if (__clusterLease == null) {
    log.info("runMigrations: another cluster node is running the migrations - skipping")
    return
}
try {
    def report = Migrations.runAll(repositorySession, log)
    log.info("runMigrations: ran=${report.ran} skipped=${report.skipped.size()} failed=${report.failed ?: 'none'}")
} finally {
    __clusterLease.close()
}
