# Office Backend & Collaboration — Design Notes

> How we *would* build the server-dependent features (real-time collaboration, cloud sync,
> version history, sharing) for the Office module. This is a design/architecture doc, not
> shipped code — those features need a backend service + device testing that can't be built or
> verified purely in this repo. It maps each idea onto the app's **current** architecture.

## 0. The architectural constraint we start from

Today every edit does this (`OfficeViewModel.updateDocument`):

1. build a **new immutable `OdfDocument`** via `.copy(...)`
2. push the old doc onto an undo stack
3. `_state.value = ViewState.Loaded(newDoc)` → Compose recomposes from scratch
4. mark dirty; autosave writes the **whole** `.odt/.ods/.odp` via `OdfWriter`

This is a **snapshot/whole-document** model. It's great for local editing + undo, but it's the
wrong granularity for collaboration and efficient sync, because we never produce *operations* —
we only produce new whole-document states. **Every backend feature below hinges on introducing an
operation/delta layer** between the UI intent and `updateDocument`. That refactor is the keystone.

---

## 1. Operation layer (prerequisite for everything)

Introduce a small algebra of **document operations** that (a) can be applied to an `OdfDocument`
to produce the next one, and (b) can be serialized, sent over the wire, transformed, and inverted.

```
sealed interface OdfOp {
  // text
  data class InsertText(val path: BlockPath, val offset: Int, val text: String, val style: SpanStyle?)
  data class DeleteText(val path: BlockPath, val start: Int, val end: Int)
  data class SetSpanStyle(val path: BlockPath, val start: Int, val end: Int, val patch: SpanStylePatch)
  data class SetParaProp(val path: BlockPath, val patch: ParaPropPatch)
  data class InsertBlock(val index: Int, val block: OdfContentBlock)
  data class DeleteBlock(val index: Int)
  data class MoveBlock(val from: Int, val to: Int)
  // sheet
  data class SetCell(val sheet: Int, val row: Int, val col: Int, val patch: CellPatch)
  data class ResizeCol(val sheet: Int, val col: Int, val px: Float?)
  // slide
  data class SetElementBounds(val slide: Int, val el: Int, val x: Float, val y: Float, val w: Float, val h: Float)
  ...
}
fun OdfDocument.apply(op: OdfOp): OdfDocument   // pure
fun OdfOp.invert(before: OdfDocument): OdfOp     // for undo + rollback
```

- `BlockPath` addresses a location stably (see §2 on why plain indices break under concurrency).
- The existing ~120 `OfficeViewModel` mutators become **thin wrappers that emit ops** and call a
  single `dispatch(op)` that (1) applies locally, (2) records for undo, (3) hands to the sync
  engine. Undo/redo becomes op-inversion instead of snapshot stacks (smaller, and *mergeable* with
  remote ops).
- Autosave/export are unchanged (still serialize the current snapshot); the ops are an *additional*
  stream, not a replacement for the on-disk ODF.

This is a big but mechanical refactor and is independently valuable (smaller undo, telemetry,
scripting/macros later).

---

## 2. Real-time collaboration

### 2.1 CRDT vs OT — choice

Two families solve concurrent editing:

- **OT (Operational Transform)** — Google-Docs style. Central server transforms each op against
  concurrent ops. Simple ops, but the transform matrix is subtle and you *must* have a server in
  the loop (hard to do peer-to-peer / offline-first).
- **CRDT (Conflict-free Replicated Data Type)** — ops/state merge deterministically without a
  central authority; naturally offline-first and P2P-capable. Cost: metadata overhead and trickier
  "intention preservation" for rich structure.

**Recommendation: CRDT**, because the app is offline-first (local `.odt` files, autosave) and
Android clients are frequently disconnected. Use a proven library rather than rolling our own:
**Yjs** (via a Kotlin/JNI binding or a Kotlin port) or **Automerge** (Rust core → `automerge-rs`
with a Kotlin FFI). For text specifically, Yjs's `Y.Text` (a sequence CRDT with formatting marks)
maps almost 1:1 onto our paragraph/span model.

### 2.2 Mapping OdfDocument → CRDT

The document becomes a CRDT tree instead of an immutable snapshot:

- `content: Y.Array<Block>` — each block is a `Y.Map`.
- Paragraph block → `{ type, style, spans: Y.Text }` where `Y.Text` carries character-level
  formatting as marks (bold/italic/link/color…) — exactly our `OdfSpan` attributes.
- Table → nested `Y.Array` of rows → `Y.Array` of cells (`Y.Text`).
- Spreadsheet → `Y.Map` keyed by `"$sheet:$row:$col"` (sparse; matches our sparse grid) plus
  `Y.Map`s for column widths / merges / floating.
- Slide element → `Y.Map` of geometry + `Y.Text`.

**This replaces `BlockPath`-by-index** (§1) with CRDT-stable identifiers, which is the real reason
CRDT beats hand-rolled OT here: indices shift under concurrent inserts; CRDT positions don't.

The Compose layer stays: on any CRDT update event we **materialize a plain `OdfDocument`** from the
CRDT and push it through the same `ViewState.Loaded(doc)` path — so `DocumentViews`/`OfficeBottomBar`
don't change at all. Local edits go: UI intent → mutate CRDT → CRDT emits update → re-materialize →
recompose. The materialize step can be incremental later; start with full rebuild (we already do
that per keystroke today, so it's no worse).

### 2.3 Transport & topology

```
Client (Android)  <—— WebSocket ——>  Sync service  <——>  Doc store (per-doc op log + snapshots)
       │  Yjs doc + local IndexedDB/Room persistence
       └── awareness channel (presence: cursors, selection, name, color)
```

- **Sync service**: stateless relay + persistence of the CRDT update stream per document. A room =
  one document id. It appends encrypted update blobs and periodically compacts to a snapshot.
  Off-the-shelf option: `y-websocket` server, or `Hocuspocus` (adds auth hooks, persistence,
  webhooks). We host it (Tupperware/Twine-style container) behind auth.
- **Presence/awareness**: ephemeral (not persisted) — remote carets and selections. Render as
  colored overlays; we already track selection as hoisted indices, so add a `remoteSelections:
  List<RemoteCaret>` to the view state and draw them in `ContinuousParagraphEditor` /
  `FloatingElementLayer`.
- **Offline**: CRDT updates queue in Room while offline; on reconnect the client sends its pending
  updates and receives missed ones — merge is automatic and conflict-free. No "resolve conflicts"
  dialog needed for concurrent edits (that's the whole point of CRDT).

### 2.4 What's hard / gotchas

- **ODF ⇄ CRDT fidelity**: our model has rich, evolving fields; the CRDT schema must cover them or
  edits to unmodeled fields get lost on merge. Mitigate by versioning the CRDT schema and keeping
  a "opaque preserved blob" for unsupported parts (we already do this for imported ODF via
  source-package copy).
- **Large binary media** (images) don't belong in the CRDT — store them content-addressed
  (sha256) in object storage; the CRDT holds only the hash. Matches our `images: Map<path, bytes>`.
- **Formula recalculation** must run locally after each merged change (we already have
  `OdfFormulaEngine`); recalc is deterministic so all clients converge.
- **Undo across users**: undo must only revert *your* ops (Yjs `UndoManager` with a tracked
  origin) — otherwise you undo a collaborator's work. Wire our undo to the CRDT UndoManager.

---

## 3. Cloud sync & storage (documents-at-rest)

Even without live collaboration, "my files everywhere":

- **Model**: each document has a server id, an owner, and a current version. Storage = object store
  (S3-style) holding the serialized `.odt/.ods/.odp` (reuse `OdfWriter`) + a thumbnail + metadata
  row in a DB.
- **Client**: a `SyncRepository` sitting beside `OfficeViewModel`. On `save()` (already exists), if
  the doc is cloud-backed, upload; on open, download. Background `WorkManager` job reconciles.
- **Conflict resolution WITHOUT live collab** (two devices edit offline): can't auto-merge whole
  ODF safely. Options in increasing sophistication:
  1. **Last-writer-wins + keep-a-copy**: on version mismatch, upload as `Name (conflicted copy)`
     and let the user pick — simple, never loses data. Ship this first.
  2. **3-way merge** using the operation log from §1 (rebase local ops onto the server's newer
     base). Requires the op layer; degrades to (1) when ops aren't available.
  3. **Always-on CRDT** (§2) makes this a non-issue.
- **ETags/versions**: conditional PUT (If-Match) to detect races; server rejects stale writes →
  client falls back to conflict copy.

## 4. Version history

- Cheap version: the sync service already stores periodic **snapshots** (ODF blobs) + the op/update
  log. A "History" screen lists snapshots (timestamp, author, thumbnail) and lets you preview/
  restore (restore = new op that sets state to that snapshot, so it's itself undoable).
- With the op layer, we can show **granular history** ("Alice bolded paragraph 3") and
  named versions, like Docs.

## 5. Autosave & local durability (mostly already here)

- Autosave loop exists (`setAutoSave` → `save()`), and undo/redo. Add: **crash-safe journaling** —
  append each op (§1) to a Room-backed WAL so an unsaved session survives a process death, and
  replay on next open. This is local-only and buildable/testable now (no server), and is a good
  first concrete step toward the op layer.

## 6. Auth, sharing, permissions

- **Auth**: OIDC (Google/Apple/enterprise SSO). Client holds a token; sync WebSocket + REST are
  authenticated per request; the sync room enforces per-doc ACL on connect.
- **Sharing model**: per-document ACL — `owner / editor / commenter / viewer`. Map to op-level
  gating: a `commenter` may only emit comment/annotation ops (we already have comment ops); a
  `viewer` emits none and never gets an editable `ViewState`. Share via signed link (role encoded
  + expiry) or explicit user grants.
- **Comments as first-class**: our comment model exists but is paragraph-anchored; for real review
  workflows, anchor comments to CRDT ranges (relative positions) so they stay attached as text
  moves, and add threads/replies/resolve (server stores the thread; client renders the existing
  Comments panel).

---

## 7. Incremental rollout (each step ships value on its own)

1. **Op layer + WAL journaling** (local only, fully testable here) — smaller undo, crash recovery,
   foundation for the rest.
2. **Cloud sync with LWW + conflict-copy** — "files everywhere", low risk.
3. **Version history** from stored snapshots.
4. **CRDT swap-in** for the document model (materialize → same Compose UI) — offline-first merge.
5. **Live presence + real-time editing** over WebSocket (awareness cursors, then shared editing).
6. **Sharing/permissions + review (threaded comments)**.

Steps 1 and parts of 5 (rendering remote carets) are implementable/verifiable in this repo; steps
2–6 need the sync service + auth backend and real multi-device testing, which is why they're a
design here rather than code.

## 8. Rough server stack

- Sync: Kotlin/Ktor or Node **Hocuspocus** for the CRDT room + persistence hooks.
- Store: Postgres (metadata/ACL/versions) + S3-compatible object store (ODF blobs, media by hash).
- Realtime: WebSocket (sticky by doc id) behind a load balancer; Redis pub/sub to fan out rooms
  across instances.
- Auth: OIDC provider + short-lived JWTs; per-room authorization check on connect.
- Media: content-addressed, deduped, served via signed URLs.
```
