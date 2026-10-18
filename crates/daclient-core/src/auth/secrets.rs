//! Per-user encryption of stored tokens via Windows DPAPI.
//! Only the logged-in Windows user can decrypt `secrets.dat`.

use crate::Result;

#[cfg(windows)]
pub fn protect(data: &[u8]) -> Result<Vec<u8>> {
    dpapi(data, true)
}

#[cfg(windows)]
pub fn unprotect(data: &[u8]) -> Result<Vec<u8>> {
    dpapi(data, false)
}

#[cfg(windows)]
fn dpapi(data: &[u8], encrypt: bool) -> Result<Vec<u8>> {
    use std::ptr::{null, null_mut};
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
        },
    };

    let input = CRYPT_INTEGER_BLOB {
        cbData: data.len() as u32,
        pbData: data.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: null_mut(),
    };
    // SAFETY: `input` points to `data`, which outlives the call; `output` is
    // allocated by Windows and released with LocalFree after copying.
    unsafe {
        let ok = if encrypt {
            CryptProtectData(&input, null(), null(), null(), null(), CRYPTPROTECT_UI_FORBIDDEN, &mut output)
        } else {
            CryptUnprotectData(&input, null_mut(), null(), null(), null(), CRYPTPROTECT_UI_FORBIDDEN, &mut output)
        };
        if ok == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let bytes = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData as _);
        Ok(bytes)
    }
}

#[cfg(not(windows))]
pub fn protect(data: &[u8]) -> Result<Vec<u8>> {
    Ok(data.to_vec())
}

#[cfg(not(windows))]
pub fn unprotect(data: &[u8]) -> Result<Vec<u8>> {
    Ok(data.to_vec())
}

#[cfg(test)]
mod tests {
    #[test]
    fn roundtrip() {
        let secret = b"refresh-token";
        let sealed = super::protect(secret).unwrap();
        assert_ne!(sealed, secret);
        assert_eq!(super::unprotect(&sealed).unwrap(), secret);
    }
}
