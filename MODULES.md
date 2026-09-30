# Compile-time modules

Both apps (`:ajiriwa`, `:psbill`) are built from the modules ticked in
[`modules.properties`](modules.properties). Everything starts ticked; untick a
module and its screens, background services, manifest permissions and extra
libraries are **not compiled** into the APK. All apps still talk to the one
CVPAP API, and the partner's enabled modules on the server decide what a
signed-in user sees among the modules that were compiled in.

## Choosing modules

```bash
python3 tools/select_modules.py              # interactive: pick app, toggle numbers, s = save
python3 tools/select_modules.py ajiriwa --only dashboard,sms,properties
python3 tools/select_modules.py ajiriwa --all
python3 tools/select_modules.py --list
```

or edit `modules.properties` by hand (`ajiriwa.sms=false`), then build:

```bash
./gradlew :ajiriwa:assembleRelease
```

One-off builds without touching the file (CI):

```bash
./gradlew :ajiriwa:assembleRelease -Pajiriwa.modules=dashboard,sms,properties
```

The build prints what it compiled, e.g.
`[ajiriwa] compiling modules: dashboard, sms, properties  (skipped: orders, …)`.

## How it works

* `gradle/feature-modules.gradle.kts` reads the selection, generates
  `CompiledModules.kt` (the registry the app navigates from) and merges the
  `AndroidManifest.xml` fragments of the enabled modules.
* Ajiriwa modules live in `ajiriwa/src/module/<name>/` (code in `java/`,
  optional `res/` and `AndroidManifest.xml`) and expose
  `com.example.psbill.modules.<name>.<Name>Feature`.
* PSBill tabs live in `psbill/src/module/<name>/` and expose
  `com.example.psbill.customer.modules.<name>.<Name>Feature`.
* Adding a module: create the folder + `<Name>Feature` object, add the name to
  the catalogue list in the app's `build.gradle.kts` and a line in
  `modules.properties`.

`stock` (Stock & tanks) is a regular module. `wifi` and `arcade` in Ajiriwa still live inside `MainActivity`: unticking them
removes them from navigation and stops their background polling, but their code
is still compiled until it is split out of `MainActivity`.

`CompiledModules.kt` and the merged module manifest are generated under `build/`; the
`generateFeatureModules` task (run before `preBuild`) writes them again, so Clean /
Rebuild in Android Studio no longer ends in "Unresolved reference 'CompiledModules'".

## Phone agent (always compiled, Ajiriwa)

`DeliveryTrackingService` (core, started after sign-in and on boot) tracks location all
day into `LocationOutbox` (offline-safe, uploaded in batches) and runs `DeviceAgent`:
a heartbeat every ~60 s (`/api/v1/mobile/heartbeat`) that reports what is waiting to
upload and picks up sync requests from the web / app. Modules add sync streams through
`FeatureModule.syncStreams` (the SMS module adds SMS, M-Pesa, calls, contacts). UI:
drawer → **Sync & tracking** (this phone, all phones, synced data, movement).

## Builds on the server

Signed APKs with any module selection are built on the server by
`tools/server-build/builder_agent.py` when the super-admin asks (admin → App builds) —
see `tools/server-build/README.md`. Local builds stay unsigned with versionCode 1.
