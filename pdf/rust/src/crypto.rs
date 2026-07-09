//! Standard security handler (RC4, revisions 2 and 3) for the safe PDF stack.
//!
//! Implements the classic PDF password algorithms (PDF 1.7 §7.6.3): key
//! derivation, user/owner password entries, per-object keys, and RC4. Enough to
//! open RC4-encrypted PDFs (empty or supplied password), remove a password
//! (decrypt then save unencrypted), and set a password (encrypt on save).
//! AES (V>=4) is intentionally not handled here.

use md5::{Digest, Md5};

/// The 32-byte password padding string (PDF §7.6.3.3).
const PAD: [u8; 32] = [
    0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
    0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
];

/// RC4 stream cipher (symmetric: same call encrypts and decrypts).
pub fn rc4(key: &[u8], data: &[u8]) -> Vec<u8> {
    if key.is_empty() {
        return data.to_vec();
    }
    let mut s: [u8; 256] = [0; 256];
    for (i, v) in s.iter_mut().enumerate() {
        *v = i as u8;
    }
    let mut j = 0usize;
    for i in 0..256 {
        j = (j + s[i] as usize + key[i % key.len()] as usize) & 0xff;
        s.swap(i, j);
    }
    let mut out = Vec::with_capacity(data.len());
    let (mut a, mut b) = (0usize, 0usize);
    for &byte in data {
        a = (a + 1) & 0xff;
        b = (b + s[a] as usize) & 0xff;
        s.swap(a, b);
        let k = s[(s[a] as usize + s[b] as usize) & 0xff];
        out.push(byte ^ k);
    }
    out
}

fn md5(data: &[u8]) -> [u8; 16] {
    let mut h = Md5::new();
    h.update(data);
    h.finalize().into()
}

fn pad_pw(pw: &[u8]) -> [u8; 32] {
    let mut out = [0u8; 32];
    let n = pw.len().min(32);
    out[..n].copy_from_slice(&pw[..n]);
    out[n..].copy_from_slice(&PAD[..32 - n]);
    out
}

/// Algorithm 2: the file encryption key from the user password.
pub fn compute_key(pw: &[u8], o: &[u8], p: i32, id0: &[u8], n: usize, rev: u8) -> Vec<u8> {
    let mut input = Vec::new();
    input.extend_from_slice(&pad_pw(pw));
    let mut o32 = [0u8; 32];
    let m = o.len().min(32);
    o32[..m].copy_from_slice(&o[..m]);
    input.extend_from_slice(&o32);
    input.extend_from_slice(&(p as u32).to_le_bytes());
    input.extend_from_slice(id0);
    let mut hash = md5(&input);
    if rev >= 3 {
        for _ in 0..50 {
            hash = md5(&hash[..n]);
        }
    }
    hash[..n].to_vec()
}

/// Algorithm 4/5: the `/U` entry (first 16 bytes are the validation salt).
pub fn compute_u(key: &[u8], id0: &[u8], rev: u8) -> Vec<u8> {
    if rev == 2 {
        rc4(key, &PAD)
    } else {
        let mut input = Vec::new();
        input.extend_from_slice(&PAD);
        input.extend_from_slice(id0);
        let hash = md5(&input);
        let mut data = rc4(key, &hash);
        for i in 1u8..=19 {
            let k: Vec<u8> = key.iter().map(|b| b ^ i).collect();
            data = rc4(&k, &data);
        }
        data.resize(32, 0);
        data
    }
}

/// Algorithm 3: the `/O` (owner) entry.
pub fn compute_o(owner_pw: &[u8], user_pw: &[u8], n: usize, rev: u8) -> Vec<u8> {
    let mut hash = md5(&pad_pw(owner_pw));
    if rev >= 3 {
        for _ in 0..50 {
            hash = md5(&hash[..n]);
        }
    }
    let okey = hash[..n].to_vec();
    let mut data = rc4(&okey, &pad_pw(user_pw)).to_vec();
    if rev >= 3 {
        for i in 1u8..=19 {
            let k: Vec<u8> = okey.iter().map(|b| b ^ i).collect();
            data = rc4(&k, &data);
        }
    }
    data
}

/// Algorithm 1: the per-object RC4 key for object (`num`,`gen`).
pub fn object_key(key: &[u8], num: u32, gen: u16, n: usize) -> Vec<u8> {
    let mut input = key.to_vec();
    input.extend_from_slice(&num.to_le_bytes()[..3]);
    input.extend_from_slice(&gen.to_le_bytes()[..2]);
    let hash = md5(&input);
    let len = (n + 5).min(16);
    hash[..len].to_vec()
}

/// Verify a password: returns the file key if it matches the `/U` entry.
pub fn authenticate(
    pw: &[u8],
    o: &[u8],
    u: &[u8],
    p: i32,
    id0: &[u8],
    n: usize,
    rev: u8,
) -> Option<Vec<u8>> {
    let key = compute_key(pw, o, p, id0, n, rev);
    let computed = compute_u(&key, id0, rev);
    let cmp_len = if rev == 2 { 32 } else { 16 };
    if computed.len() >= cmp_len && u.len() >= cmp_len && computed[..cmp_len] == u[..cmp_len] {
        Some(key)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rc4_roundtrip() {
        let key = b"SecretKey";
        let data = b"hello world, this is a test";
        let enc = rc4(key, data);
        assert_ne!(enc, data);
        assert_eq!(rc4(key, &enc), data);
    }

    #[test]
    fn user_password_roundtrip() {
        // Build O/U for user="", owner="owner" then authenticate empty user pw.
        let id0 = b"0123456789abcdef";
        let p: i32 = -44;
        let n = 16; // 128-bit
        let rev = 3u8;
        let o = compute_o(b"owner", b"", n, rev);
        let key = compute_key(b"", &o, p, id0, n, rev);
        let u = compute_u(&key, id0, rev);
        let got = authenticate(b"", &o, &u, p, id0, n, rev).expect("empty user pw authenticates");
        assert_eq!(got, key);
        // Wrong password fails.
        assert!(authenticate(b"wrong", &o, &u, p, id0, n, rev).is_none());
    }
}
