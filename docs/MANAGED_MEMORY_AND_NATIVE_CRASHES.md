# Managed memory and native crash diagnostics

## Launcher memory is authoritative by default

The legacy Java process receives the launcher's configured heap settings. When lwjgl3ify transfers Minecraft to the managed Java 21 runtime, the child now preserves the explicit heap sizing arguments supplied by CurseForge, Prism, or another launcher.

Examples:

```text
Parent: -Xms256m -Xmx8192m
Child:  -Xms256m -Xmx8192m
```

The supported memory policies in `config/lwjgl3ify-relauncher.json` are:

```json
"memoryMode": "INHERIT_LAUNCHER"
```

This is the default and recommended mode. The configured `minMemoryMB` and `maxMemoryMB` values are used only when the launcher did not supply explicit heap arguments.

```json
"memoryMode": "CUSTOM"
```

This deliberately ignores launcher heap arguments and uses `minMemoryMB` and `maxMemoryMB` instead.

Legacy configuration migration is conservative:

- The old untouched `512` / `4096` defaults migrate to launcher inheritance.
- Non-default legacy memory values remain custom values.
- Heap flags placed in `customOptions` are removed to prevent hidden duplicate `-Xms` or `-Xmx` arguments. Use `memoryMode` instead.

Every hidden-child launch records the effective heap arguments near the beginning of:

```text
logs/lwjgl3ify-java21-child.log
```

## Native process failures

The relauncher reports both the signed decimal process result and the Windows NTSTATUS value when applicable. Known statuses include:

- `0xC000041D` — `STATUS_FATAL_USER_CALLBACK_EXCEPTION`
- `0xC0000005` — `STATUS_ACCESS_VIOLATION`
- `0xC0000017` — `STATUS_NO_MEMORY`
- Unix result `134` — native abort / `SIGABRT`

These statuses identify the class of native failure, not the responsible mod or driver. The diagnostic dialog links the newest `hs_err_pid*.log` when one exists. Windows callback failures may bypass HotSpot's fatal-error writer; in that case collect the latest Event Viewer entry from **Windows Logs > Application**, including the faulting application, faulting module, exception code, and fault offset.

## Required regression checks

```bash
./gradlew --no-daemon -p buildSrc test
./gradlew --no-daemon clean verifyRepository test build
```

Runtime acceptance must also verify the effective child heap line in `logs/lwjgl3ify-java21-child.log` for both CurseForge and Prism.
