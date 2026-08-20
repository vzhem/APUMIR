//! Authenticated X25519 wrapping of one 32-byte file key for one pinned recipient.
use chacha20poly1305::{aead::{Aead, KeyInit, Payload}, XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use rand::{rngs::OsRng, RngCore};
use sha2::{Digest, Sha256};
use crate::crypto::keys::{Ed25519KeyPair, X25519KeyPair};
use crate::crypto::signing_identity::{identity_binding_matches_installed, installed_signing_identity, SignedFileExchangeBindingV1};

const VERSION:u8=1; const DOMAIN:&[u8]=b"apu-file-key-envelope-v1\0"; const KDF_INFO:&[u8]=b"apu-file-key-wrap-kdf-v1\0";
const MAX_BINDING:usize=512; const MAX_MANIFEST:usize=2048; const CIPHERTEXT:usize=48;

#[derive(Debug,Clone,PartialEq,Eq,thiserror::Error)]
pub enum FileKeyEnvelopeError {
 #[error("malformed file key envelope")] Malformed,
 #[error("sender file exchange identity mismatch")] SenderMismatch,
 #[error("recipient file exchange identity mismatch")] RecipientMismatch,
 #[error("invalid file key envelope signature")] InvalidSignature,
 #[error("file key envelope decryption failed")] DecryptionFailed,
}
#[derive(Debug,Clone,PartialEq,Eq)]
pub struct FileKeyEnvelopeV1 { pub sender_exchange_binding:Vec<u8>, pub recipient_hash:[u8;32], pub manifest_hash:[u8;32], pub nonce:[u8;24], pub ciphertext:Vec<u8>, pub signature:Vec<u8> }
impl FileKeyEnvelopeV1 {
 pub fn canonical_bytes(&self)->Result<Vec<u8>,FileKeyEnvelopeError>{
  if self.sender_exchange_binding.is_empty()||self.sender_exchange_binding.len()>MAX_BINDING||self.nonce.iter().all(|b|*b==0)||self.ciphertext.len()!=CIPHERTEXT{return Err(FileKeyEnvelopeError::Malformed)}
  let n=u16::try_from(self.sender_exchange_binding.len()).map_err(|_|FileKeyEnvelopeError::Malformed)?;
  let mut o=Vec::with_capacity(DOMAIN.len()+139+self.sender_exchange_binding.len());o.extend_from_slice(DOMAIN);o.push(VERSION);o.extend_from_slice(&n.to_be_bytes());o.extend_from_slice(&self.sender_exchange_binding);o.extend_from_slice(&self.recipient_hash);o.extend_from_slice(&self.manifest_hash);o.extend_from_slice(&self.nonce);o.extend_from_slice(&self.ciphertext);Ok(o)
 }
 pub fn to_bytes(&self)->Result<Vec<u8>,FileKeyEnvelopeError>{self.canonical_bytes()?;if self.signature.len()!=64{return Err(FileKeyEnvelopeError::Malformed)};let mut o=Vec::with_capacity(203+self.sender_exchange_binding.len());o.push(VERSION);o.extend_from_slice(&(self.sender_exchange_binding.len() as u16).to_be_bytes());o.extend_from_slice(&self.sender_exchange_binding);o.extend_from_slice(&self.recipient_hash);o.extend_from_slice(&self.manifest_hash);o.extend_from_slice(&self.nonce);o.extend_from_slice(&self.ciphertext);o.extend_from_slice(&self.signature);Ok(o)}
 pub fn from_bytes(b:&[u8])->Result<Self,FileKeyEnvelopeError>{const T:usize=32+32+24+48+64;if b.len()<3+T||b[0]!=VERSION{return Err(FileKeyEnvelopeError::Malformed)};let n=u16::from_be_bytes([b[1],b[2]])as usize;let e=3usize.checked_add(n).ok_or(FileKeyEnvelopeError::Malformed)?;if n==0||n>MAX_BINDING||b.len()!=e+T{return Err(FileKeyEnvelopeError::Malformed)};let r=e+32;let m=r+32;let no=m+24;let c=no+48;let x=Self{sender_exchange_binding:b[3..e].to_vec(),recipient_hash:b[e..r].try_into().map_err(|_|FileKeyEnvelopeError::Malformed)?,manifest_hash:b[r..m].try_into().map_err(|_|FileKeyEnvelopeError::Malformed)?,nonce:b[m..no].try_into().map_err(|_|FileKeyEnvelopeError::Malformed)?,ciphertext:b[no..c].to_vec(),signature:b[c..].to_vec()};x.canonical_bytes()?;Ok(x)}
 pub fn verify_sender(&self)->Result<SignedFileExchangeBindingV1,FileKeyEnvelopeError>{let s=SignedFileExchangeBindingV1::from_bytes(&self.sender_exchange_binding).map_err(|_|FileKeyEnvelopeError::Malformed)?;s.verify().map_err(|_|FileKeyEnvelopeError::InvalidSignature)?;Ed25519KeyPair::verify(&s.identity_binding.signing_public_key,&self.canonical_bytes()?,&self.signature).map_err(|_|FileKeyEnvelopeError::InvalidSignature)?;Ok(s)}
}

pub fn create_file_key_envelope(sender_bytes:&[u8],recipient_bytes:&[u8],sender_secret:&[u8],manifest:&[u8],file_key:&[u8])->Result<Vec<u8>,FileKeyEnvelopeError>{
 if manifest.is_empty()||manifest.len()>MAX_MANIFEST||file_key.len()!=32{return Err(FileKeyEnvelopeError::Malformed)}
 let identity=installed_signing_identity().ok_or(FileKeyEnvelopeError::SenderMismatch)?;
 let sender=SignedFileExchangeBindingV1::from_bytes(sender_bytes).map_err(|_|FileKeyEnvelopeError::Malformed)?;sender.verify().map_err(|_|FileKeyEnvelopeError::InvalidSignature)?;
 let sender_identity=sender.identity_binding.to_bytes().map_err(|_|FileKeyEnvelopeError::Malformed)?;if !identity_binding_matches_installed(&sender_identity){return Err(FileKeyEnvelopeError::SenderMismatch)}
 let sx=X25519KeyPair::from_secret_bytes(sender_secret).map_err(|_|FileKeyEnvelopeError::SenderMismatch)?;if sx.public_key().0!=sender.x25519_public_key{return Err(FileKeyEnvelopeError::SenderMismatch)}
 let recipient=SignedFileExchangeBindingV1::from_bytes(recipient_bytes).map_err(|_|FileKeyEnvelopeError::Malformed)?;recipient.verify().map_err(|_|FileKeyEnvelopeError::InvalidSignature)?;
 let mut shared=sx.diffie_hellman(&recipient.x25519_public_key).map_err(|_|FileKeyEnvelopeError::RecipientMismatch)?;let mh: [u8;32]=Sha256::digest(manifest).into();let rh:[u8;32]=Sha256::digest(recipient_bytes).into();let mut key=derive(&shared,&mh,sender.legacy_routing_node_id().as_bytes(),recipient.legacy_routing_node_id().as_bytes())?;shared.fill(0);
 let mut nonce=[0u8;24];let mut rng=OsRng;rng.fill_bytes(&mut nonce);let mut env=FileKeyEnvelopeV1{sender_exchange_binding:sender_bytes.to_vec(),recipient_hash:rh,manifest_hash:mh,nonce,ciphertext:vec![0;48],signature:Vec::new()};let aad=header_aad(&env)?;env.ciphertext=XChaCha20Poly1305::new_from_slice(&key).map_err(|_|FileKeyEnvelopeError::Malformed)?.encrypt(XNonce::from_slice(&nonce),Payload{msg:file_key,aad:&aad}).map_err(|_|FileKeyEnvelopeError::Malformed)?;key.fill(0);env.signature=identity.sign_security_payload(&env.canonical_bytes()?);env.to_bytes()
}
pub fn open_file_key_envelope(envelope_bytes:&[u8],recipient_bytes:&[u8],recipient_secret:&[u8],manifest:&[u8])->Result<Vec<u8>,FileKeyEnvelopeError>{
 let env=FileKeyEnvelopeV1::from_bytes(envelope_bytes)?;let sender=env.verify_sender()?;let recipient=SignedFileExchangeBindingV1::from_bytes(recipient_bytes).map_err(|_|FileKeyEnvelopeError::Malformed)?;recipient.verify().map_err(|_|FileKeyEnvelopeError::InvalidSignature)?;
 let ib=recipient.identity_binding.to_bytes().map_err(|_|FileKeyEnvelopeError::Malformed)?;let rh:[u8;32]=Sha256::digest(recipient_bytes).into();let mh:[u8;32]=Sha256::digest(manifest).into();if rh!=env.recipient_hash||mh!=env.manifest_hash||!identity_binding_matches_installed(&ib){return Err(FileKeyEnvelopeError::RecipientMismatch)}
 let rx=X25519KeyPair::from_secret_bytes(recipient_secret).map_err(|_|FileKeyEnvelopeError::RecipientMismatch)?;if rx.public_key().0!=recipient.x25519_public_key{return Err(FileKeyEnvelopeError::RecipientMismatch)}
 let mut shared=rx.diffie_hellman(&sender.x25519_public_key).map_err(|_|FileKeyEnvelopeError::RecipientMismatch)?;let mut key=derive(&shared,&env.manifest_hash,sender.legacy_routing_node_id().as_bytes(),recipient.legacy_routing_node_id().as_bytes())?;shared.fill(0);let aad=header_aad(&env)?;let out=XChaCha20Poly1305::new_from_slice(&key).map_err(|_|FileKeyEnvelopeError::Malformed)?.decrypt(XNonce::from_slice(&env.nonce),Payload{msg:&env.ciphertext,aad:&aad}).map_err(|_|FileKeyEnvelopeError::DecryptionFailed);key.fill(0);out
}
fn header_aad(e:&FileKeyEnvelopeV1)->Result<Vec<u8>,FileKeyEnvelopeError>{let mut c=e.clone();c.ciphertext=vec![0;48];c.signature.clear();c.canonical_bytes()}
fn derive(shared:&[u8],mh:&[u8;32],sender:&[u8],recipient:&[u8])->Result<Vec<u8>,FileKeyEnvelopeError>{let h=Hkdf::<Sha256>::new(Some(mh),shared);let mut info=Vec::new();info.extend_from_slice(KDF_INFO);info.extend_from_slice(sender);info.push(0);info.extend_from_slice(recipient);let mut out=vec![0u8;32];h.expand(&info,&mut out).map_err(|_|FileKeyEnvelopeError::Malformed)?;Ok(out)}

#[cfg(test)] mod tests { use super::*; use crate::crypto::signing_identity::{clear_signing_identity,install_signing_identity,InstalledSigningIdentity}; fn n(c:char)->String{format!("pk_{}",c.to_string().repeat(32))}
 #[test] fn round_trip_and_tamper(){clear_signing_identity();let s=install_signing_identity(1,n('1'),&[1;32]).unwrap();let sb=s.create_file_exchange_binding(s.create_binding(1).unwrap(),&[3;32],2).unwrap().to_bytes().unwrap();let r=InstalledSigningIdentity::from_seed(1,n('2'),&[2;32]).unwrap();let rb=r.create_file_exchange_binding(r.create_binding(1).unwrap(),&[4;32],2).unwrap().to_bytes().unwrap();let mut e=create_file_key_envelope(&sb,&rb,&[3;32],b"manifest",&[9;32]).unwrap();install_signing_identity(1,n('2'),&[2;32]).unwrap();assert_eq!(open_file_key_envelope(&e,&rb,&[4;32],b"manifest").unwrap(),vec![9;32]);assert!(open_file_key_envelope(&e,&rb,&[4;32],b"changed").is_err());let z=e.len()-1;e[z]^=1;assert!(open_file_key_envelope(&e,&rb,&[4;32],b"manifest").is_err());clear_signing_identity()}
}
