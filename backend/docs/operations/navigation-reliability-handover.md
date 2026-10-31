# Navigation reliability handover

## What this BE change delivers

- Admin endpoint catalog filtering by `mapId`, `roomId`, `endpointType`, `active`, `accessible`, and `q`, while retaining paged full DTOs with UUID/map/pose/status.
- MQTT readiness is separate from TCP connectivity. All eight required filters must return SUBACK reason code 0, 1, or 2. Code 135 and partial SUBACKs keep dispatch unavailable and trigger bounded exponential retry with jitter, including the socket-connected/subscription-not-ready case.
- Fresh MQTT poses are range/map validated and an older TF capture cannot be appended after the latest captured pose.
- Robot SSE events have event IDs and a 1-second reconnect hint. Timeout/error/send-failure removes the subscription without trying to serialize an API error into an already-started event stream.
- Flyway V35 adds `WF_DYNAMIC_ENDPOINT_NAVIGATION` and `WF_HOSPITAL_MULTI_STOP` as schema-3 DRAFT copies of the canonical navigation graph. It does not bypass Admin validation, compilation, rollout readiness, or publication.

## Endpoint commissioning result

No endpoint row was created. The repository proves the map asset metadata and only a static-map candidate at `(3.5000, 2.1250)`. Static inspection found pixel `(840,209)` free, about `0.2926 m` from a non-free PGM cell, and in the same raw free-space component as the prior pose `(3.950,1.500)`. This is not a Nav2 plan and does not account for the deployed footprint, inflation, costmaps, keep-out/no-stop filters, doors, or room metadata. See `docs/commissioning/HOSPITAL_F1-v2-candidates.json`.

Commission each endpoint through the existing Admin API only after a supervised Nav2 `ComputePathToPose` succeeds with the deployed map hash and costmaps. Create it initially with `active=false`, `accessible=false`, and `publicVisible=false`; link the verified room/zone; repeat the plan from representative approach poses; then activate explicitly. The database generates the UUID. Never put an assumed UUID in FE.

## Workflow publication

V35 intentionally leaves both workflows DRAFT. After endpoint commissioning, use Admin Workflows to validate and publish each workflow. Publication must produce a compiled definition hash and a real `publishedAt`/publisher. Confirm through:

| Migration-owned draft | Deterministic workflow ID | Current state after V35 |
|---|---|---|
| `WF_DYNAMIC_ENDPOINT_NAVIGATION` v1 | `31ad334d-4c04-f337-5d67-41673033fbcd` | DRAFT, does not accept jobs |
| `WF_HOSPITAL_MULTI_STOP` v1 | `9708c919-060e-7de2-1113-c38041083c88` | DRAFT, does not accept jobs |

These IDs become usable only if the same rows are successfully validated and published; clients should still discover them from the published catalog instead of hardcoding them.

```http
GET /api/v1/workflows/published?serviceType=NAVIGATION
GET /api/v1/workflows/published/{workflowId}
```

The single-destination request uses the source contract (which includes more optional fields than the abbreviated product note):

```json
{
  "mapId": "<active-map-uuid>",
  "workflowId": "<published-workflow-uuid>",
  "roomId": null,
  "originEndpointId": null,
  "destinationEndpointId": "<active-endpoint-uuid>",
  "destinationGroupId": null,
  "targetMode": "ENDPOINT",
  "navigationMode": "NORMAL",
  "accessibilityNeed": null,
  "passengerCount": 1,
  "arrivalInstruction": null,
  "priority": 50,
  "note": "Supervised navigation",
  "data": {}
}
```

For multi-stop use `targetMode=ENDPOINT_GROUP`, set `destinationGroupId`, clear `destinationEndpointId`, and order active group members by `sequenceNo`. The cloned canonical graph loops only after the prior MOVE reports success; the existing outbox/journal/operation-key guards remain authoritative.

## Verification order

1. Railway/MySQL: backup, deploy V35, verify Flyway, map code/version/hash, and confirm both workflows are DRAFT.
2. EMQX: retain `All Users -> Deny #`; verify the `mar-be` principal has only the required rules and observe eight successful SUBACK codes.
3. ROS/Gazebo: use the separate WSL handoff, stable preflight, correct map hash, TF, clock, sensors, and ACTIVE Nav2 lifecycle nodes.
4. Admin: commission endpoints, create ordered endpoint group, validate and publish workflows.
5. Adapter, then FE. Before every supervised MOVE confirm one adapter, one telemetry owner, no unreconciled operation, fresh pose, and dispatch readiness.

Local BE checks:

```powershell
.\deploy\verify-be.ps1
.\deploy\verify-be.ps1 -WithRuntime   # requires Docker/Testcontainers or TEST_DB_URL
```

## Test status on 2026-10-21 (Asia/Saigon)

| Scope | Result | Evidence |
|---|---|---|
| Java 21 compile | PASS | 480 source files compiled |
| Focused unit tests | PASS | 8 run, 0 failures/errors/skips |
| Full `mvn verify` | BLOCKED | 584 discovered; 338 integration errors share missing Docker/Testcontainers root cause; 10 skipped |
| V35 MySQL migration test | NOT RUN | Requires MySQL/Testcontainers (`TEST_DB_URL` not set; Docker unavailable) |
| EMQX wire ACL runtime | NOT RUN | No broker credentials/connectivity supplied |
| Railway DB verification | NOT RUN | No production credentials supplied |
| WSL/ROS/Nav2/Gazebo | BLOCKED | WSL is not installed on this machine; adapter sources absent |
| Robot MOVE/CANCEL/10-minute telemetry | NOT RUN | Requires supervised robot/Gazebo session |

Rollback: do not delete workflow/job history. If V35 has been deployed but not published, leave the DRAFT rows or remove them only through the existing Admin draft-delete API after confirming no Job references. If published, archive via Admin API. Roll back application code independently; never `flyway clean`, reset production, or delete recovery journals.
