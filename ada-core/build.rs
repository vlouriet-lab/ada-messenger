fn main() {
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

