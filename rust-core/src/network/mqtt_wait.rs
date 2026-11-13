//! Cancellation-safe wait for the MQTT core-owned inbox, control channel and
//! outbound command. Handling an extracted control notification (notably
//! ConnAck + subscription) must happen *after* this wait, without selecting it
//! against a later command.

use std::future::Future;
use std::time::Duration;
use tokio::sync::mpsc;

use super::mqtt_overflow::LossIntolerantInbox;

pub(crate) enum MqttWait<E, N, W> {
    Delivery(E, usize),
    Notification(Option<N>),
    Wake(W),
    Timeout,
    Spurious,
}

pub(crate) async fn wait<E, N, W>(
    inbox: &LossIntolerantInbox<E>,
    notifications: &mut mpsc::Receiver<N>,
    wake: impl Future<Output = W>,
    timeout: Duration,
) -> MqttWait<E, N, W> {
    if let Some((event, remaining)) = inbox.pop() {
        return MqttWait::Delivery(event, remaining);
    }
    tokio::select! {
        _ = inbox.notified() => match inbox.pop() {
            Some((event, remaining)) => MqttWait::Delivery(event, remaining),
            // A previous fast-path pop can leave a stored notification permit.
            // It produces at most one empty turn, not an unbounded busy loop.
            None => MqttWait::Spurious,
        },
        notification = notifications.recv() => MqttWait::Notification(notification),
        value = wake => MqttWait::Wake(value),
        _ = tokio::time::sleep(timeout) => MqttWait::Timeout,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[tokio::test]
    async fn a_delivery_wakes_a_waiting_consumer_and_is_not_lost() {
        let inbox = Arc::new(LossIntolerantInbox::new(4));
        let writer = Arc::clone(&inbox);
        let (_tx, mut notifications) = mpsc::channel::<()>(1);
        let task = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(30)).await;
            writer.push_owned_bounded("sealed").await;
        });
        let result = tokio::time::timeout(Duration::from_millis(700), wait(
            &inbox, &mut notifications, std::future::pending::<()>(), Duration::from_secs(2),
        )).await.expect("delivery must wake before the timeout");
        assert!(matches!(result, MqttWait::Delivery("sealed", 0)));
        task.await.unwrap();
    }

    #[tokio::test]
    async fn a_command_and_notification_remain_owned_when_the_other_wins() {
        let inbox = LossIntolerantInbox::<()>::new(1);
        let (notify_tx, mut notifications) = mpsc::channel(1);
        let (command_tx, mut commands) = mpsc::channel(1);
        notify_tx.send("connack").await.unwrap();
        command_tx.send("send").await.unwrap();
        let result = wait(&inbox, &mut notifications, commands.recv(), Duration::from_secs(1)).await;
        match result {
            MqttWait::Notification(Some("connack")) => assert_eq!(commands.try_recv(), Ok("send")),
            MqttWait::Wake(Some("send")) => assert_eq!(notifications.try_recv(), Ok("connack")),
            _ => panic!("both queued values must remain owned"),
        }
    }

    #[tokio::test]
    async fn notify_permit_between_empty_check_and_sleep_is_not_missed() {
        let inbox = LossIntolerantInbox::new(2);
        let (_tx, mut notifications) = mpsc::channel::<()>(1);
        inbox.push_owned("before-wait");
        let result = wait(&inbox, &mut notifications, std::future::pending::<()>(), Duration::from_secs(1)).await;
        assert!(matches!(result, MqttWait::Delivery("before-wait", 0)));
        // A fast-path pop leaves at most one stored permit. It can be consumed
        // as Spurious, then a later delivery must still wake normally.
        let result = wait(&inbox, &mut notifications, std::future::pending::<()>(), Duration::from_secs(1)).await;
        assert!(matches!(result, MqttWait::Spurious));
        inbox.push_owned("next");
        assert!(matches!(wait(&inbox, &mut notifications, std::future::pending::<()>(), Duration::from_secs(1)).await,
            MqttWait::Delivery("next", 0)));
    }

    #[tokio::test]
    async fn idle_wait_is_bounded() {
        let inbox = LossIntolerantInbox::<()>::new(1);
        let (_tx, mut notifications) = mpsc::channel::<()>(1);
        assert!(matches!(wait(&inbox, &mut notifications, std::future::pending::<()>(), Duration::from_millis(10)).await,
            MqttWait::Timeout));
    }
}
