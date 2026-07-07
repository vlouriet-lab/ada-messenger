fn main() {
    // ── Patch audiopus_sys for CMake 4.x compatibility ─────────────────────
    // CMake 4.x removed backward-compat with cmake_minimum_required < 3.5.
    // We locate the vendored Opus CMakeLists.txt in the Cargo registry cache
    // and prepend the policy minimum so the build succeeds on VS 2026 runners.
    patch_audiopus_cmake();

    // Generate Rust code from .proto files (optional — requires protoc to be installed).
    // Proto types are used for wire serialisation; if protoc is absent we skip generation
    // and rely on the hand-rolled types in the source tree.
    #[cfg(feature = "proto")]
    {
        if let Err(e) = prost_build::compile_protos(&["proto/ada.proto"], &["proto/"]) {
            // Non-fatal: the generated proto code is only used when explicitly imported.
            // During development without protoc the build still succeeds.
            eprintln!(
                "cargo:warning=prost-build skipped (protoc not found): {}",
                e
            );
        }
    }

    // Generate C headers for the mobile FFI layer (requires cbindgen config).
    #[cfg(feature = "ffi")]
    {
        let crate_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap();
        // Wrap in catch_unwind so cbindgen internal panics (e.g. on complex generic
        // types it cannot mangle) do not abort the build.  Header failures are
        // non-fatal — the .h file is only needed for iOS Swift bindings, not for
        // Android JNI builds.
        match std::panic::catch_unwind(move || cbindgen::generate(&crate_dir)) {
            Ok(Ok(bindings)) => {
                bindings.write_to_file("ada_core.h");
            }
            Ok(Err(e)) => {
                eprintln!("cargo:warning=cbindgen error: {}", e);
            }
            Err(_) => {
                eprintln!("cargo:warning=cbindgen panicked — ada_core.h not regenerated");
            }
        }
    }

    println!("cargo:rerun-if-changed=proto/");
    println!("cargo:rerun-if-changed=src/ffi.rs");
}

/// Patches the bundled Opus `CMakeLists.txt` inside the Cargo registry so that
/// CMake 4.x (which removed compat with `cmake_minimum_required(VERSION < 3.5)`)
/// does not abort. The patch is idempotent — it checks for the marker before
/// writing, so repeated builds are fast and won't corrupt the file.
fn patch_audiopus_cmake() {
    // Resolve CARGO_HOME (defaults to ~/.cargo)
    let cargo_home = std::env::var("CARGO_HOME")
        .or_else(|_| std::env::var("HOME").map(|h| format!("{h}/.cargo")))
        .or_else(|_| std::env::var("USERPROFILE").map(|h| format!("{h}\\.cargo")))
        .unwrap_or_else(|_| String::from(".cargo"));

    let registry = std::path::PathBuf::from(cargo_home)
        .join("registry")
        .join("src");

    // Walk registry looking for audiopus_sys-*/opus/CMakeLists.txt
    let cmake_lists: Vec<_> = if let Ok(rd) = std::fs::read_dir(&registry) {
        rd.filter_map(|e| e.ok())
            .flat_map(|host| {
                let path = host.path().join("audiopus_sys-0.2.2").join("opus").join("CMakeLists.txt");
                if path.exists() { Some(path) } else { None }
            })
            .collect()
    } else {
        vec![]
    };

    const MARKER: &str = "# ADA-PATCH: cmake4-compat";
    const PATCH: &str = "# ADA-PATCH: cmake4-compat\ncmake_minimum_required(VERSION 3.5)\n";

    for cmake_path in &cmake_lists {
        let content = match std::fs::read_to_string(cmake_path) {
            Ok(c) => c,
            Err(_) => continue,
        };
        if content.contains(MARKER) {
            // Already patched — skip.
            continue;
        }
        // Prepend the compat block and rewrite.
        let patched = format!("{PATCH}{content}");
        if let Err(e) = std::fs::write(cmake_path, &patched) {
            // Non-fatal: warn but don't abort the build.
            eprintln!("cargo:warning=audiopus_sys cmake patch failed ({cmake_path:?}): {e}");
        } else {
            println!("cargo:warning=Patched audiopus_sys CMakeLists.txt for CMake 4.x compat: {cmake_path:?}");
        }
    }

    if cmake_lists.is_empty() {
        eprintln!("cargo:warning=audiopus_sys not found in Cargo registry — cmake patch skipped");
    }
}
