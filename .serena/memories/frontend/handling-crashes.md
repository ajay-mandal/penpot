# Frontend Runtime Crash Handling

## Detect a runtime workspace crash

Runtime crashes usually show the Internal Error page with title text "Something bad happened" and class `main_ui_static__download-link`. If the crash follows an edit via JS API / `execute_code`, inspect the editor's `update-file-v2` response as well as the runtime exception; a save rejection is not itself a workspace crash.

After a crash, `execute_code` can become unusable because no plugin instances are connected and any data in its `storage` is lost, but `cljs_repl` usually still works.

Check crash state:

```clojure
(some? (:exception @app.main.store/state))
```

It returns `true` when the Internal Error page is showing and `false` on a healthy workspace or after a successful reload.

## Read the runtime cause

The exception is stored at `(:exception @app.main.store/state)`. Useful keys:

- `:type`, `:code`, `:status`: error class, e.g. `:validation`, `:referential-integrity`, `400`.
- `:hint`, `:details`: human-readable explanation; `:details` often contains validation problems with `:shape-id`, `:page-id`, `:args`, etc.
- `:uri`: API endpoint that returned the error, e.g. `/api/main/methods/update-file-v2`.
- `:app.main.errors/instance`: underlying JS Error object.
- `:app.main.errors/trace`: JS stack trace string, usually response-handling path rather than the dispatch site that produced the bad change.

```clojure
(let [ex (:exception @app.main.store/state)]
  (select-keys ex [:type :code :status :hint :details :uri]))
```

For backend validation errors (`:type :validation`), `:details` is usually the most informative field; it identifies the shape and invariant that failed.

## Recover and continue testing

Simplest path when `cljs_repl` is still live (usually true after a crash): reload via repl with `(.reload js/location)` — see `mem:frontend/cljs-repl`. Alternatively via Playwright: find the workspace tab (URL contains `/#/workspace`, title ends `- Penpot`), select it if not current, then `playwright:browser_navigate` to that same URL. Either way, confirm recovery with `(some? (:exception @app.main.store/state))` returning `false`.

HTTP 200 with `:rejected` means the backend kept the accepted prefix and the editor rolls back the rejected commits; reload can still show saved edits. A request schema error (`:params-validation`, including malformed undo) writes nothing, but a transport failure does not tell you whether the server saved the batch. Check which case occurred before retrying; reload can discard edits still queued in the browser.
