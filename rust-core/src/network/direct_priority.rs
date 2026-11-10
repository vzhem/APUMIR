//! Bounded per-peer scheduler: interactive frames pass waiting file data,
//! but both classes retain FIFO order and file data gets a bounded fair turn.
//! Only one consumer executes jobs, so connection creation stays single-flight.

use tokio::sync::mpsc::{self, error::TrySendError, Receiver, Sender};

const MAX_INTERACTIVE_BURST: usize = 8;

pub(crate) struct PrioritySender<T> {
    interactive: Sender<T>,
    file: Sender<T>,
}

pub(crate) struct PriorityReceiver<T> {
    interactive: Receiver<T>,
    file: Receiver<T>,
    interactive_open: bool,
    file_open: bool,
    interactive_burst: usize,
}

pub(crate) fn channel<T>(interactive_capacity: usize, file_capacity: usize)
    -> (PrioritySender<T>, PriorityReceiver<T>)
{
    let (interactive_tx, interactive_rx) = mpsc::channel(interactive_capacity);
    let (file_tx, file_rx) = mpsc::channel(file_capacity);
    (
        PrioritySender { interactive: interactive_tx, file: file_tx },
        PriorityReceiver {
            interactive: interactive_rx,
            file: file_rx,
            interactive_open: true,
            file_open: true,
            interactive_burst: 0,
        },
    )
}

impl<T> PrioritySender<T> {
    pub(crate) fn try_send(&self, interactive: bool, item: T) -> Result<(), TrySendError<T>> {
        if interactive { self.interactive.try_send(item) } else { self.file.try_send(item) }
    }
}

impl<T> PriorityReceiver<T> {
    pub(crate) async fn recv(&mut self) -> Option<T> {
        loop {
            if !self.interactive_open && !self.file_open { return None; }
            let (interactive, item) = if self.interactive_burst < MAX_INTERACTIVE_BURST {
                tokio::select! {
                    biased;
                    item = self.interactive.recv(), if self.interactive_open => (true, item),
                    item = self.file.recv(), if self.file_open => (false, item),
                }
            } else {
                tokio::select! {
                    biased;
                    item = self.file.recv(), if self.file_open => (false, item),
                    item = self.interactive.recv(), if self.interactive_open => (true, item),
                }
            };
            match item {
                Some(item) => {
                    self.interactive_burst = if interactive {
                        (self.interactive_burst + 1).min(MAX_INTERACTIVE_BURST)
                    } else { 0 };
                    return Some(item);
                }
                None if interactive => self.interactive_open = false,
                None => self.file_open = false,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[tokio::test]
    async fn interactive_overtakes_queued_file_data_without_reordering_its_own_class() {
        let (tx, mut rx) = channel(8, 8);
        tx.try_send(false, "file-1").unwrap();
        tx.try_send(true, "text-1").unwrap();
        tx.try_send(false, "file-2").unwrap();
        tx.try_send(true, "text-2").unwrap();
        for expected in ["text-1", "text-2", "file-1", "file-2"] {
            assert_eq!(rx.recv().await, Some(expected));
        }
        drop(tx);
        assert_eq!(rx.recv().await, None);
    }

    #[tokio::test]
    async fn waiting_file_gets_a_turn_after_a_bounded_interactive_burst() {
        let (tx, mut rx) = channel(16, 2);
        for i in 0..10 { tx.try_send(true, i).unwrap(); }
        tx.try_send(false, 99).unwrap();
        for i in 0..MAX_INTERACTIVE_BURST {
            assert_eq!(rx.recv().await, Some(i));
        }
        assert_eq!(rx.recv().await, Some(99));
        assert_eq!(rx.recv().await, Some(8));
        assert_eq!(rx.recv().await, Some(9));
    }

    #[tokio::test]
    async fn a_full_file_queue_cannot_consume_interactive_capacity() {
        let (tx, mut rx) = channel(1, 1);
        tx.try_send(false, "file").unwrap();
        assert!(matches!(tx.try_send(false, "extra"), Err(TrySendError::Full("extra"))));
        tx.try_send(true, "text").unwrap();
        assert_eq!(rx.recv().await, Some("text"));
        assert_eq!(rx.recv().await, Some("file"));
    }

    #[tokio::test]
    async fn messages_keep_flowing_if_the_file_class_is_empty() {
        let (tx, mut rx) = channel(16, 1);
        for i in 0..12 { tx.try_send(true, i).unwrap(); }
        for i in 0..12 { assert_eq!(rx.recv().await, Some(i)); }
        drop(tx);
        assert_eq!(rx.recv().await, None);
    }

    #[tokio::test]
    async fn sleeping_consumer_wakes_when_either_class_arrives() {
        let (tx, mut rx) = channel(1, 1);
        let sender = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(30)).await;
            tx.try_send(true, "text").unwrap();
        });
        assert_eq!(tokio::time::timeout(Duration::from_secs(2), rx.recv()).await.unwrap(), Some("text"));
        sender.await.unwrap();
        assert_eq!(rx.recv().await, None);
    }
}
