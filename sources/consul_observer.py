import sys
import urllib
import socket
import json
import base64
from subprocess import Popen, PIPE
from time import sleep
from urllib.request import Request, urlopen


def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # doesn't even have to be reachable
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP


def quote_for_posix(string):
    return "\\'".join("'" + p + "'" for p in string.split("'"))


def restartProcessWithConfig(b64string, jarPath):
    pure_json = base64.b64decode(b64string).decode("utf-8")
    pure_json = quote_for_posix(pure_json).replace('\n', '')
    command = 'java -jar ' + jarPath + ' --configJson ' + pure_json
    return Popen([command], stdout=sys.stdout, stderr=sys.stderr,
                 stdin=sys.stdin, shell=True)


def main():
    jarPath = sys.argv[1]
    consulPort = sys.argv[2]

    long_polling_interval = 10  # seconds
    cached_modify_index = -1

    java_bot = Popen(['pwd'], stdout=sys.stdout, stderr=sys.stderr, stdin=PIPE, shell=True)
    java_bot.wait()
    java_bot.terminate()

    try:
        while True:
            url = "http://localhost:" + consulPort + "/v1/kv/" + get_local_ip()
            request = Request(url)
            print("Starting pulling configuration from url: " + url)

            try:
                jsonString = urlopen(request).read().decode()
                print("RECIVED CONFIGURATION FROM CONSUL: " + jsonString)

                _json = json.loads(jsonString)

                modify_index = _json[0]["ModifyIndex"]

                poll = java_bot.poll()
                if modify_index != cached_modify_index or poll is not None:
                    print("CONFIG WAS MODIFIED! RESTARTING PROCESS")
                    java_bot.terminate()
                    java_bot = restartProcessWithConfig(_json[0]["Value"], jarPath)
                else:
                    print("CONFIG WASN'T MODIFIED. PENDING")

                cached_modify_index = modify_index

            except urllib.error.HTTPError as e:
                if e.code == 404:
                    print("Haven't configuration for your machine in Consul yet. Pending")
                else:
                    print(e)

            sleep(long_polling_interval)

    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
