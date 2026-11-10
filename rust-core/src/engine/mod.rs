//! Engine module — главный движок P2P мессенджера

pub mod core;
pub mod events;
pub(crate) mod send_gates;
mod mqtt_cadence;
