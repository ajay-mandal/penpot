# Fragment reference index and GC

`file-change-refs` (audit consumer `5`) runs on writable workers and indexes
retained file history. It does not mark fragments unless
`PENPOT_FRAGMENTS_GC_ENABLED=true`. The existing file GC, physical purge and
offload do not change.

## Deploy the index

1. Check that consumer `5` has not been assigned to a different consumer.
   Do not reset another consumer's cursor. Stop old runners on the shared
   queue before starting the first worker that can enqueue the new jobs.
   Ensure old web writers are also gone before evaluating the marking gates.
2. Apply `0160-add-file-change-refs`. It creates an empty partitioned table
   and a foreign key to `file_change`; there is no global backfill. The
   foreign key takes a lock on the referenced table. If the normal migration
   window cannot take that lock, plan a maintenance window instead.
3. Start the new workers with marking off. The web process writes full audit
   events regardless of the Nexus and admin-console flags. Watch the size of
   `audit_log`, consumer lag and `index-file-change-refs` failures. Stopping
   the consumer does not remove its cursor: unread full events can remain in
   the buffer until it comes back. Do not delete the cursor to catch up.

The ordinary replay processes events still in the buffer. It does not
reconstruct purged events. Once marking is enabled, a GC job that finds an
unindexed change repairs the missing rows in batches and tries again.
Corrupt or unreadable snapshots leave that file uncovered; check the failed
job and source data rather than filling in an empty array.

## Before enabling marking

- Verify that the deployed writers retain the required revisions and snapshots
  (both metadata and payload), including automatic snapshots, restore backups
  and legacy rows already dated for deletion. The index does not change retention.
- Verify the locked read fix is deployed on all writers. Pause and drain
  marking before administrative rewrites of main or history that bypass the
  normal writer protocol. Files without a live canonical `file_data` main
  are not eligible.
- Keep tiered offload disabled throughout the fleet and drain any pending or
  running `offload-file-data` work. Do not enable marking while a legacy
  producer can queue more offload jobs. The job gate also checks active
  offload rows for its tenant before the GC can acquire a file lock.
- Test the bundle followed by a fragment GET without a cached response.
  Measure `update-file` p95/p99 and the time spent waiting for locks under
  concurrent editing on representative long histories. Compare against
  the same workload with the legacy GC. Agree on the latency budget before
  enabling the job. Do not shorten `PENPOT_FILE_CLEAN_DELAY` below the
  existing default of two days without testing that shorter window.

## Stop or roll back

Turn `PENPOT_FRAGMENTS_GC_ENABLED` off on the consumer and all runners.
Queued marking jobs then exit before locking a file. Wait for already
running marking to finish. Keep the new job definitions running until the
new jobs are drained or isolated before rolling back to older workers.
Disabling marking neither reverses existing marks nor restores purged
payloads. The index can keep running while marking is off.
