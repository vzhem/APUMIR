//! Engine module — главный движок P2P мессенджера

pub mod core;
pub mod events;
pub(crate) mod send_gates;
mod mesh_delivery;
mod mesh_cleanup;
mod mqtt_cadence;
