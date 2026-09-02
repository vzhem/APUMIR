#!/usr/bin/env python3
"""Разовая уборка «мёртвых» объявлений о присутствии на публичных брокерах.

Присутствие публикуется с retain=true в p2pm2/presence/<node_id>. Такая запись
живёт на брокере, пока её не сотрут. Удалённая установка приложения больше
никогда не подключится и не сотрёт свою - её «я онлайн» остаётся навсегда, и
живые телефоны считают призрака собеседником.

Штатно это лечит Last Will (см. mqtt_transport.rs): брокер сам стирает запись
при обрыве. Но записи, осиротевшие ДО появления Last Will, надо снять руками -
для этого и нужен этот скрипт.

    python3 purge_presence.py                # только показать, ничего не трогая
    python3 purge_presence.py --purge        # стереть найденные записи
    python3 purge_presence.py --keep pk_abc  # не трогать живой узел

Требуется paho-mqtt: pip install paho-mqtt
"""
import argparse
import time

import paho.mqtt.client as mqtt

BROKERS = [("broker.hivemq.com", 1883), ("broker.emqx.io", 1883)]
TOPIC = "p2pm2/presence/#"


def sweep(host, port, purge, keep, listen_secs):
    found = {}

    def on_connect(client, _u, _f, _rc):
        client.subscribe(TOPIC, qos=1)

    def on_message(_c, _u, msg):
        # Ретейн-флаг стоит только у записей, отданных брокером из архива.
        # Живой пульс, пришедший прямо сейчас, придёт без него - его не трогаем.
        if msg.retain and msg.payload:
            found[msg.topic] = msg.payload.decode("utf-8", "replace")

    client = mqtt.Client(client_id="apu_presence_sweep_%d" % time.time(),
                         clean_session=True)
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(host, port, keepalive=30)
    client.loop_start()
    time.sleep(listen_secs)

    print("%s: сохранённых объявлений о присутствии - %d" % (host, len(found)))
    stale = []
    for topic in sorted(found):
        node_id = topic.rsplit("/", 1)[-1]
        alive = node_id in keep
        print("    %-24s %s%s" % (node_id[:24], found[topic][:60],
                                  "   [живой, оставляю]" if alive else ""))
        if not alive:
            stale.append(topic)

    if purge and stale:
        for topic in stale:
            client.publish(topic, payload=b"", qos=1, retain=True)
        time.sleep(8)
        print("    стёрто: %d" % len(stale))

    client.loop_stop()
    client.disconnect()
    return len(stale)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--purge", action="store_true",
                        help="стереть найденные записи (без флага - только показ)")
    parser.add_argument("--keep", nargs="*", default=[],
                        help="node_id, которые трогать нельзя")
    parser.add_argument("--listen", type=int, default=20,
                        help="сколько секунд слушать брокер, по умолчанию 20")
    args = parser.parse_args()

    total = 0
    for host, port in BROKERS:
        total += sweep(host, port, args.purge, set(args.keep), args.listen)
    if not args.purge and total:
        print("\nЭто был показ. Чтобы стереть, повторите с --purge")


if __name__ == "__main__":
    main()
