# Staging Reset Implementation (ActivityWatch/aw-android#296)

## Overview

This PR implements an automatic one-shot reset of the phone's staging database to remove duplicates from pre-ActivityWatch/aw-server-rust#713 sync data.

## Architecture

### Kotlin Side (aw-android, this PR)
- ✅ Added JNI function declaration: `resetStaging(hostname: String): String`
- ✅ Implemented one-shot logic with SharedPreferences flag per device
- ✅ Integrated into sync flow: runs before first push after upgrade
- ✅ Runs under existing `syncInFlight` guard (no concurrent access)
- ✅ Added unit tests for one-shot behavior

### Rust Side (aw-server-rust, requires separate PR)

The Rust implementation needs to be added to `aw-sync/src/android.rs`. This file contains JNI bindings for the native sync library.

#### JNI Function Signature

```rust
#[no_mangle]
pub unsafe extern "C" fn Java_net_activitywatch_android_SyncInterface_resetStaging(
    env: JNIEnv,
    _class: JClass,
    hostname: JString,
) -> JString {
    let hostname: String = match env.get_string(&hostname) {
        Ok(h) => h.into(),
        Err(_) => return env.new_string(r#"{"success": false, "error": "invalid hostname"}"#).unwrap(),
    };
    
    // Implementation here
}
```

#### Implementation Requirements

1. **Delete staging database files**
   - Delete `{sync_dir}/{hostname}/{device_id}/test.db`
   - Delete `{sync_dir}/{hostname}/{device_id}/test.db-wal` (if present)
   - Delete `{sync_dir}/{hostname}/{device_id}/test.db-shm` (if present)
   - Resolve paths using the same `get_sync_dir()` + hostname/device_id pattern as push
   - **Path confinement**: use path canonicalization to prevent directory traversal; refuse paths outside `{sync_dir}/{hostname}`

2. **Preserve peer data**
   - Only delete files in the own device directory
   - Refuse to touch peer device directories under other hostnames
   - Verify target path is strictly: `{sync_dir}/{hostname}/{device_id}/*`

3. **Response format**
   - Return JSON: `{"success": true}` on success
   - Return JSON: `{"success": false, "error": "message"}` on failure
   - Error cases: invalid hostname, path traversal attempt, I/O failure, device ID resolution failure

4. **Error handling**
   - Log all operations
   - If any WAL file fails to delete, don't proceed to the main DB
   - If main DB fails to delete, return error
   - If device already has the fix (#713), this is a no-op (test.db doesn't exist)

#### Integration Points

- Verify the target Android build carries fix ActivityWatch/aw-server-rust#713
- This function is safe to call multiple times (subsequent calls will find nothing to delete)
- Must handle the case where staging files don't exist (not an error)

#### Testing

Unit tests should verify:
1. **Ownership**: only deletes own device's staging files
2. **Path confinement**: rejects paths outside the allowed directory with error (no filesystem escape)
3. **Peer preservation**: coexisting peer databases are untouched
4. **Idempotency**: calling twice doesn't error on second call
5. **Serialization**: when called under `syncInFlight` guard, no races with concurrent push/pull

Example test data structure:
```
sync_dir/
  my-phone/
    abc123-device-id/     ← own device
      test.db
      test.db-wal
      test.db-shm
    def456-device-id/     ← peer (untouched)
      test.db
  other-host/             ← peer host (untouched)
    ghi789-device-id/
      test.db
```

After reset, only `my-phone/abc123-device-id/*.db*` files are deleted.

## Verification Steps

Before merging this PR:

1. Verify local `android-test` / `android-unlock` source buckets on the test device are NOT duplicated
   - If they are duplicated, this PR alone won't help; the local buckets need cleaning first
   
2. After implementing Rust side:
   - Run the reset on a test device with pre-#713 staging data
   - Verify staging files are deleted
   - Verify peer and other-host data survives
   
3. Subsequent desktop pull should add zero events:
   - Staging is now empty, so the next push rebuilds it from the clean local data
   - Desktop pull should recognize the new records as duplicates (via end_time + data fingerprint from #713)
   - Zero new events should be imported

## Related Issues

- ActivityWatch/aw-server-rust#713 - boundary duplicate fix (duplicates now identifiable)
- ActivityWatch/aw-server-rust#718 - desktop dedupe (ships before this)
- ActivityWatch/aw-server-rust#717 - original epic (item 1: desktop, item 2: this PR)
- ActivityWatch/aw-android#295 - bump to commit with #713
- ActivityWatch/aw-android#296 - this issue
