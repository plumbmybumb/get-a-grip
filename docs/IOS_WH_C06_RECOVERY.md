# WH-C06 recovery for the next iOS release

Status: development changes; no version/build bump, archive or submission.

The WH-C06 broadcasts readings rather than accepting Bluetooth command writes.
iOS already restarted its scan after ten seconds without readings, but a recovery
search could then run forever and its Connect controls offered no way to cancel.

The update adds a bounded recovery sequence: three acquisition windows of fifteen
seconds, separated by two- and five-second delays. If none finds the scale, the
search ends with “No scale found” and the user can connect again. An initial
connection attempt retains its fifteen-second deadline. Search cancellation is
available on Today, the gauge screen, max measurement and during a workout.

Healthy scans keep running. Tare remains software-only and does not restart a
scan. This change does not copy Android's periodic scan renewal; no equivalent
iOS slowdown has been established. Timing, measurement freshness thresholds,
background pause policy and the other gauge clients are unchanged.

Diagnostics describe actual scan activity instead of claiming commands were
written to the scale. Consecutive identical scan facts are coalesced so useful
connection history stays in the report. No Bluetooth addresses, serial numbers
or advertisement payloads are added to reports.

## Validation

The iOS simulator build succeeds with Swift 6 strict concurrency. All **642 iOS
tests pass** with zero failures, including 30 new production-client lifecycle
tests and four scan-diagnostics tests. The client tests use an injected radio
transport and virtual monotonic time, covering initial timeout, three recovery
attempts, exhaustion followed by reconnect, cancellation during scans/backoff,
radio changes, retired callbacks and cancelled jobs, tare and advertiser
isolation, no invented samples, and twelve minutes of healthy delivery without
scan renewal. Existing store background/resume tests also pass.

Independent review found no blocking lifecycle issues. The other BLE clients,
engine files, app identities, permissions and version/build settings were not
changed. The test simulator was shut down after verification.

Before release, verify with a real iPhone and WH-C06: sustained force response,
scale power-off/on recovery, Cancel followed by Connect, unloaded tare, and
leaving/returning to the app. Simulator lifecycle tests cannot verify radio or
firmware behavior. CoreBluetooth does not expose Android's per-advertisement
observation timestamp; callback guards do not prove that every native observation
was collected after the current scan began.
