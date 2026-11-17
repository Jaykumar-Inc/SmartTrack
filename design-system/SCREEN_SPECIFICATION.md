# SmartTrack interface specification

Tokens: `tokens.json`. Windows styling: `desktop/src/main/resources/smarttrack.css`. Android: `android/app/src/main/res/values/{colors,themes}.xml`. Use 8 px/dp spacing increments, 8 px corners, 48 dp mobile touch targets, 14 px body, 12 px secondary labels and tabular/monospace desktop readings. Minimum desktop window: 1000 × 700. Primary window: 1280 × 840. No transitions are needed to convey correctness; charts deliberately disable animation.

| Screen | User purpose | Functional controls / evidence |
|---|---|---|
| Overview / Home | See the current signal and provenance | Device selection, refresh, review alerts; contact/validity labels on health |
| Health | Assess usable health estimates over time | 1/6/24/168 hour desktop history; native mobile signal charts; null gaps |
| Motion | Inspect physical axes, orientation and activity | Real values and units; experimental detector state; no decorative model |
| Location | Assess receiver communication separately from satellite lock | Valid-position OpenStreetMap link only; historical coordinate table on desktop |
| Alerts | Review device events | Owner-scoped backend acknowledgement; separate local BLE review on mobile |
| Devices | Register, select and provision device identities | Registration dialog, one-time token display, token rotation, revision-checked configuration; mobile BLE discovery/connect/disconnect |
| Analytics / History | Inspect retained data | Bounded queries, range summary, CSV export on desktop; 24-hour refresh on mobile |
| Settings | Configure connection and local experience | Backend address/test, display timezone, sign out; optional mobile cache sync, clock/interval BLE writes, cache clearing |

Desktop shell: 216 px sidebar, 64–72 px topbar, 32 px workspace margin, 16 px panel gaps, 24 px panel padding. Data is prominent but limited to a few comparable values. Secondary device context lives in the header.

Mobile shell: compact toolbar, persistent source/freshness strip, scrollable 24 dp content inset and four-item navigation (Home, Health, Devices, More). More opens Motion, Location, Alerts, History and Settings. Safe drawing insets accommodate edge-to-edge Android releases.

States are components, not decorative overlays: awaiting signal explains how to connect; no registered devices links to registration; no history shows an empty table/chart; requests show connection/loading status; errors remain human-readable; stale/cached data retain provenance. A missing number displays an em dash, never zero. Sensor health, transport connection, timestamp quality and physiological quality are independent states.

Experimental health and fall notices remain visible in relevant screens. Critical alerts use textual severity as well as color. Charts provide axes and units. Keyboard access uses normal focus traversal and Ctrl+R on desktop. Dark appearance is the only delivered theme. English is the initial language; complete localization is pending.
