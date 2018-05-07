import sys
import urllib
import socket
import json
import base64
from subprocess import Popen, PIPE
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

    consul_index = None
    cached_modify_index = -1
    java_bot = Popen(['pwd'], stdout=sys.stdout, stderr=sys.stderr, stdin=PIPE, shell=True)
    java_bot.wait()
    java_bot.terminate()



    try:
        while True:
            url = "http://localhost:" + consulPort + "/v1/kv/" + get_local_ip()

            if consul_index is not None:
                url += "?index=" + str(consul_index)

            request = Request(url)
            print("Starting pulling configuration from url: " + url)

            try:
                response = urlopen(request)
                headers = response.info()
                print(headers)
                consul_index = headers.get('X-Consul-Index', None)
                print(str(consul_index))
                jsonString = response.read().decode()
                print("Recived configuration from consul: " + jsonString)
                _json = json.loads(jsonString)


                poll = java_bot.poll()
                if consul_index != cached_modify_index or poll is not None:
                    print("Config was modified! Restarting process")
                    java_bot.terminate()
                    java_bot = restartProcessWithConfig(_json[0]["Value"], jarPath)
                else:
                    print("Config wasn't modified. Pending.")

                cached_modify_index = consul_index

            except urllib.error.HTTPError as e:
                if e.code == 404:
                    print("Haven't configuration for your machine in Consul yet. Pending")
                else:
                    print(e)

    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
