# NeoData M1 foundation — work in progress

This document describes the investigation, not an implemented controller. See [the repository audit](M1-audit.md).

## Coordination problem

Each Bukkit plugin has its own classloader. A service interface bundled independently into two plugins does not automatically have the same Java class identity, so a Bukkit service lookup using the client's private copy is not a reliable shared API. Static singleton fields likewise cannot enforce one host across plugin classloaders. Any implementation must identify the host using an actually shared server/JDK-loaded boundary, version the protocol, reject duplicates, and define what happens to clients when the host shuts down. It must not silently fall back to independent writers.

## Legacy data constraint

ConfigManager retains a loaded configuration and serializes it, while RegionManager reconstructs its region document. Those are different compatibility contracts; replacing either writer with a generic model serializer could discard unknown keys. Config and regions must remain under their existing writer and guard until a specific integration can preserve their semantics, reload barrier, and failure behavior. No migration or storage format change has been made.

## Status

An experimental standalone `NeoDataStore` now provides asynchronous string get, put and serialized read-modify-write operations, per-namespace files and a directory file lock. Tests cover concurrent updates, namespace isolation, restart persistence, invalid paths and duplicate lock acquisition. It is not yet started or used by NeoTab. A successful write means atomic replacement succeeded; filesystem sync and power-loss durability are not guaranteed. Filesystems without atomic replacement reject writes. No existing NeoTab YAML is migrated or changed by this store.

Controller registration, cross-classloader discovery, subscriptions, integration, legacy fixture tests and full failure/recovery testing remain unimplemented. The file lock only coordinates processes using the same directory and locking protocol; it is not yet server-wide host discovery. The example configurations are test inputs, not proof of legacy compatibility. No separate NeoData service is required or installed.
