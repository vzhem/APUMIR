import json
import os
import socket
import sys
import threading
from datetime import datetime, timezone

import paho.mqtt.client as mqtt


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def load_json(path):
    with open(path, "r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def save_json(path, value):
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=True, indent=2)
        handle.write("\n")
    os.replace(temporary, path)


def result_code(value):
    try:
        return int(value)
    except Exception:
        raw = getattr(value, "value", None)
        if raw is not None:
            return int(raw)
        return 0 if str(value).lower() in ("0", "success") else -1


def main():
    if len(sys.argv) == 2 and sys.argv[1] == "--check":
        print("paho-ok")
        return 0
    if len(sys.argv) != 3:
        raise SystemExit("usage: v111613_publish_once.py REQUEST_JSON RESULT_JSON")

    request_path = sys.argv[1]
    result_path = sys.argv[2]
    if os.path.exists(result_path):
        raise SystemExit("result path already exists; refusing repeated publish")

    request = load_json(request_path)
    result = {
        "schema": 1,
        "status": "STARTED",
        "startedUtc": utc_now(),
        "completedUtc": None,
        "broker": request["broker"],
        "testId": request["testId"],
        "qos": 1,
        "retained": False,
        "connected": False,
        "publishCalled": False,
        "publishMid": None,
        "publishConfirmed": False,
        "error": None,
    }
    save_json(result_path, result)

    connected = threading.Event()
    published = threading.Event()
    client = None

    try:
        try:
            client = mqtt.Client(
                mqtt.CallbackAPIVersion.VERSION1,
                client_id="apu_v111613_" + request["testId"][-10:],
                clean_session=True,
            )
        except (AttributeError, TypeError):
            client = mqtt.Client(
                client_id="apu_v111613_" + request["testId"][-10:],
                clean_session=True,
            )

        def on_connect(client_instance, userdata, flags, reason_code, *extra):
            if result_code(reason_code) == 0:
                connected.set()

        def on_publish(client_instance, userdata, message_id, *extra):
            published.set()

        client.on_connect = on_connect
        client.on_publish = on_publish
        socket.setdefaulttimeout(10.0)

        connect_result = client.connect(
            request["broker"]["host"],
            int(request["broker"]["port"]),
            30,
        )
        if int(connect_result) != 0:
            raise RuntimeError("connect returned %s" % connect_result)

        client.loop_start()
        if not connected.wait(12.0):
            raise TimeoutError("broker ConnAck was not received before publish")

        result["connected"] = True
        result["status"] = "CONNACK_RECEIVED"
        save_json(result_path, result)

        result["publishCalled"] = True
        result["status"] = "PUBLISH_CALLED_ONCE"
        save_json(result_path, result)

        info = client.publish(
            request["topic"],
            request["envelope"].encode("utf-8"),
            qos=1,
            retain=False,
        )
        result["publishMid"] = int(info.mid)
        save_json(result_path, result)

        if int(info.rc) != int(mqtt.MQTT_ERR_SUCCESS):
            raise RuntimeError("publish returned rc=%s" % info.rc)

        try:
            info.wait_for_publish(timeout=12.0)
        except TypeError:
            info.wait_for_publish()

        if not (published.wait(1.0) or info.is_published()):
            raise TimeoutError("publish called but PUBACK was not confirmed")

        result["publishConfirmed"] = True
        result["status"] = "PUBACK_CONFIRMED"
        result["completedUtc"] = utc_now()
        save_json(result_path, result)
        print(json.dumps({"publishConfirmed": True, "mid": int(info.mid)}, ensure_ascii=True))
        return 0
    except Exception as error:
        result["status"] = "INCOMPLETE_DO_NOT_RETRY_AUTOMATICALLY"
        result["error"] = "%s: %s" % (type(error).__name__, error)
        result["completedUtc"] = utc_now()
        save_json(result_path, result)
        print(json.dumps({"publishConfirmed": False, "error": result["error"]}, ensure_ascii=True))
        return 2
    finally:
        if client is not None:
            try:
                client.disconnect()
            except Exception:
                pass
            try:
                client.loop_stop()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
