//! Concurrency contracts for the UniFFI handle. No real network is required.

use crate::{create_engine, P2PCoreHandle};
use crate::engine::core::P2PCore;
use crate::engine::events::CoreEvent;
use std::sync::{mpsc, Arc};
use std::time::Duration;

#[test]
fn core_and_handle_are_send_and_sync() {
    fn require_send_sync<T: Send + Sync>() {}
    require_send_sync::<P2PCore>();
    require_send_sync::<P2PCoreHandle>();
}

#[test]
fn event_bus_remains_readable_while_engine_lifecycle_is_exclusive() {
    let handle = create_engine("event-test".into());
    handle.events.emit(CoreEvent::MessageReceived {
        message_id: "m1".into(), chat_id: "chat".into(), sender_id: "peer".into(),
        text: "sealed".into(), timestamp: 1,
    });
    let engine_guard = handle.inner.write().unwrap();
    let reader = Arc::clone(&handle);
    let (send, receive) = mpsc::channel();
    let worker = std::thread::spawn(move || {
        let ready = reader.await_main_event(100);
        let id = reader.poll_main_event(true).and_then(|event| event.message_id);
        send.send((ready, id)).unwrap();
    });
    let result = receive.recv_timeout(Duration::from_secs(1));
    drop(engine_guard);
    worker.join().unwrap();
    assert_eq!(result.unwrap(), (true, Some("m1".to_string())));
}

#[test]
fn active_readers_can_overlap_but_stop_waits_for_them() {
    let handle = create_engine("lifecycle-test".into());
    let first = handle.inner.read().unwrap();
    // An unrelated operation can join while a network operation is in flight.
    assert!(handle.inner.try_read().is_ok());
    let stopper = Arc::clone(&handle);
    let (started, ready) = mpsc::channel();
    let (done, completed) = mpsc::channel();
    let worker = std::thread::spawn(move || {
        started.send(()).unwrap();
        stopper.stop();
        done.send(()).unwrap();
    });
    ready.recv().unwrap();
    assert!(completed.recv_timeout(Duration::from_millis(50)).is_err());
    drop(first);
    completed.recv_timeout(Duration::from_secs(2)).unwrap();
    worker.join().unwrap();
    assert!(!handle.is_running());
}
