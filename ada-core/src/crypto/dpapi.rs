//! Windows Data Protection API (DPAPI) integration
//! Provides user-bound encryption for device anchors and master secrets on Windows.

use crate::error::{ADAError, Result};

#[cfg(target_os = "windows")]
mod win32 {
    use super::*;

    #[repr(C)]
    #[allow(non_snake_case)]
    struct DATA_BLOB {
        cbData: u32,
        pbData: *mut u8,
    }

    const CRYPTPROTECT_UI_FORBIDDEN: u32 = 0x1;

    #[link(name = "crypt32")]
    extern "system" {
        fn CryptProtectData(
            pDataIn: *const DATA_BLOB,
            szDataDescr: *const u16,
            pOptionalEntropy: *const DATA_BLOB,
            pvReserved: *mut std::ffi::c_void,
            pPromptStruct: *mut std::ffi::c_void,
            dwFlags: u32,
            pDataOut: *mut DATA_BLOB,
        ) -> i32;

        fn CryptUnprotectData(
            pDataIn: *const DATA_BLOB,
            ppszDataDescr: *mut *mut u16,
            pOptionalEntropy: *const DATA_BLOB,
            pvReserved: *mut std::ffi::c_void,
            pPromptStruct: *mut std::ffi::c_void,
            dwFlags: u32,
            pDataOut: *mut DATA_BLOB,
        ) -> i32;
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn LocalFree(hMem: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
    }

    pub fn protect(plaintext: &[u8]) -> Result<Vec<u8>> {
        if plaintext.is_empty() {
            return Ok(Vec::new());
        }

        let in_blob = DATA_BLOB {
            cbData: plaintext.len() as u32,
            pbData: plaintext.as_ptr() as *mut u8,
        };
        let mut out_blob = DATA_BLOB {
            cbData: 0,
            pbData: std::ptr::null_mut(),
        };

        let ok = unsafe {
            CryptProtectData(
                &in_blob,
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut out_blob,
            )
        };

        if ok == 0 {
            let err = std::io::Error::last_os_error();
            return Err(ADAError::Crypto(format!("CryptProtectData failed: {err}")));
        }

        let slice = unsafe {
            std::slice::from_raw_parts(out_blob.pbData, out_blob.cbData as usize)
        };
        let result = slice.to_vec();

        unsafe {
            LocalFree(out_blob.pbData as *mut std::ffi::c_void);
        }

        Ok(result)
    }

    pub fn unprotect(ciphertext: &[u8]) -> Result<Vec<u8>> {
        if ciphertext.is_empty() {
            return Ok(Vec::new());
        }

        let in_blob = DATA_BLOB {
            cbData: ciphertext.len() as u32,
            pbData: ciphertext.as_ptr() as *mut u8,
        };
        let mut out_blob = DATA_BLOB {
            cbData: 0,
            pbData: std::ptr::null_mut(),
        };

        let ok = unsafe {
            CryptUnprotectData(
                &in_blob,
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut out_blob,
            )
        };

        if ok == 0 {
            let err = std::io::Error::last_os_error();
            return Err(ADAError::Crypto(format!("CryptUnprotectData failed: {err}")));
        }

        let slice = unsafe {
            std::slice::from_raw_parts(out_blob.pbData, out_blob.cbData as usize)
        };
        let result = slice.to_vec();

        unsafe {
            LocalFree(out_blob.pbData as *mut std::ffi::c_void);
        }

        Ok(result)
    }
}

/// Protect data using Windows DPAPI. On non-Windows platforms returns an error.
pub fn dpapi_protect(plaintext: &[u8]) -> Result<Vec<u8>> {
    #[cfg(target_os = "windows")]
    {
        win32::protect(plaintext)
    }
    #[cfg(not(target_os = "windows"))]
    {
        Err(ADAError::Crypto("DPAPI is only supported on Windows".into()))
    }
}

/// Unprotect data using Windows DPAPI. On non-Windows platforms returns an error.
pub fn dpapi_unprotect(ciphertext: &[u8]) -> Result<Vec<u8>> {
    #[cfg(target_os = "windows")]
    {
        win32::unprotect(ciphertext)
    }
    #[cfg(not(target_os = "windows"))]
    {
        Err(ADAError::Crypto("DPAPI is only supported on Windows".into()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[cfg(target_os = "windows")]
    fn test_dpapi_roundtrip() {
        let secret = b"my-super-secret-device-anchor-32b";
        let encrypted = dpapi_protect(secret).expect("protect should succeed");
        assert_ne!(&encrypted[..], secret);
        let decrypted = dpapi_unprotect(&encrypted).expect("unprotect should succeed");
        assert_eq!(&decrypted[..], secret);
    }
}

