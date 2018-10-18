# hierarch_cep_concept

# CEP rules Distribution system: implementation concept
### Motivation
There is a need for a system that would allow to connect computing devices in hierarchical structure to build on it a multi-layer complex event processing.

### System Requirements
The system should allow the user to:
- Flexibly provide CEP rules specific to each node or set of nodes (without recompiling processing software);
- Set location from where the node will receive data for implementation of the CEP and where the flow of data from detected events will be directed;
- Flexibly change topology of the hierarchical structure (redirect output data streams, add / remove computing nodes);
- To monitor the lifecycle of the system (detect failed nodes).

From these requirements a number of technical subtasks follow:
- Identify protocols and technologies for data transfer between nodes;
- Implement a program that will handle complex events. It must accept input configuration, which contains:
    * Serialized CEP rules (also known as CEP patterns) - a formal description of rules, using which the program will detect complex events from incoming data stream;
    * The configuration data required to read incoming data stream and output of generated data stream;
- Implement a device hierarchy management system that would allow to change topology of the system, monitor its lifecycle, distribute configurations between different nodes of the system.

## Highlevel concept
[![pic 1](https://github.com/MatveyMalatsion/Distributed-CEP-concept/blob/develop/2.png?raw=true)](https://www.dropbox.com/s/0vi52t91ukkwy3u/2.png)

(the picture is clickable)

#### Highlevel description

In a distributed system, there is a set of computing nodes. By abstracting from their purpose (they can be either embedded devices, servers or cloud platforms), the main idea is that they all have a UNIX-like environment.

On each node, a local Apache Flink instance of the incoming data stream is deployed.

For each device, the Consul.io agent is installed as a client. The Consul.io server is deployed separately. Device agents register themselves and their services (Kafka and others) on the Consul-server. From now on, they form a cluster with a distributed key / value database. When registering, the server receives their network coordinates, information about the services and gets the opportunity to check their availability. Nodes can freely register and leave the Consul server.

#### Usage of Consul

>Consul is a tool for configuring and discovering services in distributed infrastructures. 
>It provides features such as Service Discovery, Health Checkking, Distributed Key / Value store.

Consul agents can work in two modes: server mode and client mode. Together, they form a cluster, which is coordinated using the gossip protocol.

One of the key features of Consul is a distributed database, using which you can distribute necessary information between distributed services. In this particular case, this will be the configuration and CEP rules. The administrator saves the configuration using a key, which is network coordinates (IP-address) of device, for which this configuration is intended.

On each device, the Consul agent provides HTTP-api, which is available at localhost on port, on which the agent is running. In particular, this API provides methods for reading a distributed database. In response, Consul returns a JSON such as the following:
```sh
$ curl \
http://localhost:<consul-port>/v1/kv/<ip>
```
```json
[
  {
    "CreateIndex": 100,
    "ModifyIndex": 200,
    "LockIndex": 200,
    "Key": <ip>,
    "Flags": 0,
    "Value": "dGVzdA==",
    "Session": "adf4238a-882b-9ddc-4a9d-5b6758e4159e"
  }
]
```

Several fields are important here:

- "ModifyIndex" : This is a number that changes every time the value of the key "Key" changes from the outside. By tracking this field, one can determine whether the data has changed.
- "Value" : Base64 encoded information

#### Consul observer
Consul observer is a daemon script that performs several important functions:

- It periodically polls the local Consul HTTP-api to obtain a configuration from the distributed database.
- It can start and end processes, in which the CEP program is running with the configuration received by the script.
- By changing the "ModifyIndex" field in Consul response, it can understand that administrators have changed the configuration for this device. In this case, it terminates the process on the OS, in which the CEP program is running, and then starts a new one with the same configuration.
- It's possible to use Long Polling technology to track "ModifyIndex", by passing it to request as parameter. Script makes request to HTTP-api, but server responses only when data for key will be modified. With long polling, it us possible to reduce the number of requests to a minimum, while still receiving real-time updates.

[![pic 3](https://github.com/MatveyMalatsion/Distributed-CEP-concept/blob/develop/3.png?raw=true)](https://www.dropbox.com/s/acambknkmu0m4bo/3.png?dl=0)

#### Implementation of Consul Observer

Below is a Python implementation of Consul Observer: (also available as [Gist](https://gist.github.com/MatveyMalatsion/c11481d52dfe7a8853eb131721ad6b31))

```py
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
```

##### Configuration format

The configuration is in JSON format and consists of the following fields:

```json
{
    "readFromKafka" : {
        "server" : "localhost:9092",
        "topic"  : "uniPhysInputTopic"
    },
    "writeToKafka" : {
        "server" : "localhost:9092",
        "topic"  : "physOutputTopic"
    },
    "patterns" : [
        [
            ["_begin", "first"],
            ["_where", "js_func_typeIsTemp"],
            ["_where", "js_func_tempIsInNormalRange"],
            ["_next", "second"],
            ["_where", "js_func_typeIsTemp"],
            ["_where", "js_func_tempIsInMiddleRange"]
        ]
    ],
    "partitionKey" : "appId", 
    "conditions" : [
        "function js_func_tempIsInNormalRange(value){return value.value <= 25}",
        "function js_func_tempIsInMiddleRange(value){return value.value >= 26 && value.value <= 40}",
        "function js_func_typeIsTemp(value){return value.type === \"TEMP\" }" 
    ]
```

- "readFromKafka", "writeToKafka" : location and topic of input/output kafka instances;
- "patterns" - an array of CEP rules, which handles CEP on input stream;
- "partitionKey" - a field, by which CEP program splits main message stream on partitions;
- "conditions" - an array of JS functors, which are used by patterns.

##### CEP program

The CEP program is the main component of the computing node. It is the component that implements Complex Event Processing on the incoming data stream. On nodes, it is provided as a compiled jar file.

As input parameters, it takes two keys:
- --configJson - a string in JSON format with the configuration;
- --configPath - the path to file that contains the configuration string.

One of these parameters must be provided.

Example of starting the program from the terminal:

```sh
$ java -jar <path to jar> --jsonPath <path to json>
```

##### СEP program workflow:
- reading configuration;
- parsing serialized patterns to Apache Flink patterns;
- generating general dataStream from kafka input;
- generating partitional dataStreams by splitting general dataSrteam on partitions;
- running events detection using patterns;
- generating warnings data stream and sending it to kafka output.

##### Parsing patterns:

The mechanism for describing the pattern in Apache Flink involves creating a java object. The whole logic of finding the pattern is constructed using conseсutive calls of various methods of the builder.

An example of constructing an apache flink cep pattern:
```java
Pattern<TemperatureWarning, ?> alertPattern = Pattern.<TemperatureWarning>begin("First Event")
    .next("Second Event")
    .within(Time.seconds(20));
```

Since all methods of the builder are called sequentially, it is possible to represent any pattern as a sequence of method calls to an instance of the Pattern class. This makes it possible to simply parse patterns from JSON in Java.

| FLINK API BUILDER METHODS | JSON representation |
| ------ | ------ |
|where(condition)|["_where", "<conditionKey>"]|
|or(condition)|["_or", "<conditionKey>"]|
|until(condition)|["_until", "<conditionKey>"]|
|oneOrMore()|["_oneOrMore", null]|
|timesOrMore(#times)|["_timesOrMode", 3]|
|times(#ofTimes)|["_times", 3]|
|times(#fromTimes, #toTimes)|["_timesRange", [1, 4]]|
|optional()|["_optional", null]|
|greedy()|["_greedy", null]|
|consecutive()|["_consecutive", null]|
|allowCombinations()|["_allowCombinations", null]|
|begin(#name)|["_begin", "<name>"]|
|next(#name)| ["_next", "<name>"]|
|followedBy(#name)|["_followedBy", "<name>"]|
|followedByAny(#name)|["_followedByAny", "<name>"]|
|notNext()|["_notNext", "<name>"]|
|notFollowedBy()|["_notFollowedBy", "<name>"]|
|within(time)|["_within", 5000]|

Java realization (available as [Gist](https://gist.github.com/MatveyMalatsion/d3e8905ef530c7bec76d5e2dd391dcdd)):


### Deployment tips

(this section will be supplemented)

- Start the Consul Server outside of topology;
- Prepare nodes:
    - Install and run Zookeper, Kafka server and topic;
    - Download consul_observer.py, cep_processor.jar;
    - Run consul agent with registered Kafka as a service;
    - Join node to the cluster;
    - Run consul_ovserver.py.
- Write configurations to distributed K/V Consul database on the Consul Server;
- Enjoy.
    




