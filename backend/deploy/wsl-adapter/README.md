# WSL adapter handoff (separate from BE)

The `MAR_Step55G_MoveAdapter` and `ros_pose_telemetry.py` sources are not present in this repository or workspace, so no source patch was fabricated. Apply the following acceptance contract in that separate repository, then review the real diff there.

## Required ownership model

- One long-lived process owns one MQTT client ID and one robot transport session.
- Telemetry is a supervised component of that process (or one separately named client); never start two adapters/telemetry publishers for the same robot.
- Persist `operationKey`, `jobId`, `jobStepId`, command hash, and terminal result before acknowledging. A duplicate command replays the stored state/result and never sends a second Nav2 goal.
- Accept the next MOVE only after the previous operation has a terminal Nav2 result and has released its goal handle.
- On network loss, stop accepting work and retain the journal. Do not infer success. Reconnect with a new transport session, send HELLO plus the full journal, and wait for BE reconciliation.
- CANCEL/STOP is idempotent and correlated to the exact `jobStepId`/`operationKey`.

## Preflight gate (must remain fail-closed)

Run in the ROS 2 Jazzy environment with `ROS_DOMAIN_ID=72`. The gate must observe a stable window (recommended 10 consecutive seconds) containing exactly one usable `/map`, `/clock`, `/odom`, and `/scan` publisher, a fresh `map -> base_link` transform, and all required Nav2 lifecycle nodes ACTIVE. Discovery gets a bounded retry window; failure exits non-zero and must never invoke `run.sh --arm`.

Example supervised order (replace paths with the actual adapter repository):

```bash
export ROS_DOMAIN_ID=72
python3 ./preflight_ros.py --timeout-sec 60 --stable-sec 10
flock -n /run/user/$UID/mediassistrover-adapter.lock \
  ./run.sh --arm --seconds 900
```

The 900-second argument is intentionally unchanged. Extending it does not convert the current one-MOVE session into a durable service.

## Telemetry changes required in the real source

Record structured, non-secret fields: ROS simulated capture time, TF age, `time.monotonic()`, heartbeat publish time/result, transport/producer session IDs, and map identity/hash. Initialize the TF listener before declaring telemetry ready; publish `fresh=false` with no pose value while TF or `/clock` is unavailable. Never reuse a prior transform with a new timestamp.

Heartbeat cadence should be 1 second. With the BE freshness threshold of 3 seconds, commission only when the 10-minute stationary test shows no unexplained stale periods and MOVE telemetry stays ordered. Keep JWT/password and full sensitive payloads out of logs.

## Supervised runtime tests (not run in this workspace)

1. Start Gazebo/Nav2, pass the stable preflight, then start exactly one adapter.
2. Confirm HELLO/profile/map SHA acknowledgement and all eight BE SUBACKs before READY.
3. Run one endpoint MOVE, then a 2+ endpoint group route; verify each later MOVE is dispatched only after the prior Nav2 `SUCCEEDED` result.
4. Test CANCEL while moving, broker disconnect, stale TF, adapter restart, and SSE auth expiry.
5. Preserve journals and database rows after every recovery test.
