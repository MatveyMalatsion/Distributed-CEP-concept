# hierarch_cep_concept

# CEP rules Distribution system: implementation concept
### Motivation
A system is needed that allows to connect the computing devices in a hierarchical structure to build a multi-layer compex event processing on it.

### System Requirements
The system should allow the user:
- Flexibly providing CEP rules is specific to each node or set of nodes (without recompiling software processing software)
- Provide from where the node will receive data for the implementation of the CEP and where the flow of data from the detected events will be directed.
- Permit the ability to flexibly change the topology of the hierarchical structure (redirect output data streams, add / remove compute nodes)
- Provide an opportunity to monitor the life cycle of the system (detect failed nodes)

From these requirements a number of technical subtasks follow:
- Identify the protocols and technologies for data transfer between nodes
- Implement a program that will handle complex events. It must accept in the input configuration, which contains:
    * Serialized CEP rules (also known as CEP patterns) - which formally describe the rules by which the program will detect complex events from the incoming data stream.
    * The configuration data required to read the incoming data stream and the output of the generated data stream
* Implement a device hierarchy management system that would allow to change the topology of the system, monitor its life cycle, distribute the configuration between different nodes of the system.

## Highlevel concept

[![pic 1](https://github.com/MatveyMalatsion/hierarch_cep_concept/blob/master/2.png?raw=true)](https://www.dropbox.com/s/0vi52t91ukkwy3u/2.png)

(picture clickable)

#### Highlevel description

In a distributed system, there is some set of computing nodes. By abstracting from their purpose (they can be either embedded devices, or servers or cloud platforms), the main thing is that they have a UNIX-like environment.

On each node, a local Apache Flink instance of the incoming data stream is deployed.

For each device, the Consul.io agent is installed as a client. The Consul.io server is deployed separately. Device-agents register themselves and their services (Kafka and others) in the Consul-server. From now on, they form a cluster with a distributed key / value database. When registering, the server receives their network coordinates, information about the services and gets the opportunity to check their operability. Nodes can freely register and leave the Consul server.

#### Consule usage

>Consul is a tool for configuring and discovering services in distributed infrastructures. 
>It provides features such as Service Discovery, Health Checkking, Distributed Key / Value store.

Consul-agents can work in two modes: in server mode and in client mode. Together they form a cluster, which is coordinated using the gossip protocol.

One of the key features of Consul is a distributed database, with which you can distribute the necessary information between distributed services. In the case of this system, this will be the configuration and CEP rules. The administrator saves the configuration with a key, which is the network coordinate (IP-adress) of the device for which this configuration is intended.

On each device, the Consul agent provides HTTP-api, which is available at localhost on the port on which the agent is running. In particular, this API provides methods for reading a distributed database. In response, Consul returns a similar JSON:
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

- "ModifyIndex" : This is the number that changes every time the value of the key "Key" changes from the outside. By tracking this field, we can determine whether the data has changed.
- "Value" : Base64 encoded information

#### Consul observer
Consul observer is a deamon script that performs several important functions:

- The script periodically polls the local Consul HTTP Api to obtain a configuration from the distributed database.
- The script can start and end the process in which the CEP program is spinning with the configuration received by the script.
- By changing the "ModifyIndex" field in the response from Consul, the script understands that administrators have changed the configuration for this device. In this case, it terminates the process on the OS in which the CEP program is running, and then starts a new one, with the same configuration.
- 
[![pic 1](https://github.com/MatveyMalatsion/hierarch_cep_concept/blob/master/1.png?raw=true)](https://www.dropbox.com/s/acambknkmu0m4bo/3.png?dl=0)

#### Implementation of Consul Observer

There is Python implementation of Consul Observer: (also available as [Gist](https://gist.github.com/MatveyMalatsion/c11481d52dfe7a8853eb131721ad6b31))

```py
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
```

##### Configuration format

The configuration will be in JSON format and will consist of the following fields

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

- "readFromKafka", "writeToKafka" : location and topic of input/output kafka instances
- "patterns" - array of CEP rules, witch will handle CEP on input stream
- "partitionKey" - a field by witch CEP program will split main message stream on partitions
- "conditions" - array of JS functors, usable by patterns

##### CEP program

The CEP program is the main component of the computing node. It is this component that implements Complex Event Processing on the incoming data stream. On nodes, it is postponed as a compiled jar file.

As input parameters, it takes two keys:
- --configJson - string in JSON format with configuration
- --configPath - the path to the file that contains the configuration string

One of these parameters must be provided.

Example of starting the program from the terminal

```sh
$ java -jar <path to jar> --jsonPath <path to json>
```

##### СEP program workflow:
- reading configuration
- parse serialized patterns to Apache Flink patterns
- generate general dataStrem from kafka input
- generate partitional dataStreams by splitted
- run events detection using patterns
- generate warnings data stream and push in to kafka output

##### Patterns parsing

The mechanism for describing the pattern in Apache Flink involves creating a java object. The whole logic of finding the pattern is constructed using successive calls of various methods of the builder.

An example of constructing an apache flink cep pattern:
```java
Pattern<TemperatureWarning, ?> alertPattern = Pattern.<TemperatureWarning>begin("First Event")
    .next("Second Event")
    .within(Time.seconds(20));
```

Since all the methods of the builder are called sequentially, it is possible to represent any pattern as a sequence of method calls to an instance of the Pattern class. This makes it possible to simply parse patterns from JSON in Java.

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

```java
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.PatternSelectFunction;
import org.apache.flink.cep.PatternStream;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.IngestionTimeExtractor;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer09;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.apache.flink.cep.pattern.Pattern;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.*;

public class UnifiedCEPLayer {

    private String _kafkaInputServer;
    private String _kafkaInputTopic;
    private String _kafkaOutputServer;
    private String _kafkaOutputTopic;



    ScriptEngine engine;
    StreamExecutionEnvironment _env;

    public UnifiedCEPLayer(JSONObject configuration, StreamExecutionEnvironment env) throws Exception{

        _env = env;
        // extract input and output
        JSONObject inputConfig = (JSONObject) configuration.get("readFromKafka");
        JSONObject outputConfig = (JSONObject) configuration.get("writeToKafka");

        _kafkaInputServer = (String) inputConfig.get("server");
        _kafkaInputTopic  = (String) inputConfig.get("topic");

        _kafkaOutputServer = (String) outputConfig.get("server");
        _kafkaOutputTopic  = (String) outputConfig.get("topic");

        // extract conditions
        ScriptEngineManager manager = new ScriptEngineManager();
        engine = manager.getEngineByName("JavaScript");
        UnifiedCondition.invoker = (Invocable)engine;

        JSONArray conditions = (JSONArray) configuration.get("conditions");

        Iterator<String> iterator = conditions.iterator();

        while (iterator.hasNext()){
            engine.eval(iterator.next());
        }

        // extract patterns
        JSONArray patterns = (JSONArray)configuration.get("patterns");
        ArrayList<Pattern<JSONObject, JSONObject>> parsedPatterns = new ArrayList<>();
        Iterator<JSONArray> objectIterator = patterns.iterator();

        while (objectIterator.hasNext()){
            parsedPatterns.add(patternFromJSON((JSONArray) objectIterator.next()));
        }

        //configure input
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", this._kafkaInputServer);

        //configure partition rule
        String partitionKey = (String) configuration.get("partitionKey");

        KeySelector<JSONObject, String> partitionRule = new KeySelector<JSONObject, String>() {
            @Override
            public String getKey(JSONObject value) throws Exception {
                return (String) value.get(partitionKey);
            }
        };

        //creating all messages stream
        DataStream<JSONObject> messageStream = _env.addSource(new FlinkKafkaConsumer09<JSONObject>(this._kafkaInputTopic,
                new UnifiedDeserializationSchema(),
                properties
        )).assignTimestampsAndWatermarks(new IngestionTimeExtractor<>());

        //creating partiton stream
        DataStream<JSONObject> partitionedStream = messageStream.keyBy(partitionRule);

        ArrayList<PatternStream<JSONObject>> streams = new ArrayList<PatternStream<JSONObject>>();

        parsedPatterns.forEach(tPattern -> streams.add(CEP.pattern(partitionedStream, tPattern)));

        ArrayList<DataStream<JSONObject>> warnings = new ArrayList<>();

        //detecting patterns
        streams.forEach(stream -> {
            warnings.add(stream.select(new PatternSelectFunction<JSONObject, JSONObject>() {
                @Override
                public JSONObject select(Map<String, List<JSONObject>> map) throws Exception {
                    JSONObject obj = new JSONObject();
                    obj.putAll(map);
                    return obj;
                }
            }));
        });

        //print alarms
        warnings.forEach(w -> w.map( warning ->{
            return warning;
        }).print());

    }

    private Pattern<JSONObject, JSONObject> patternFromJSON(JSONArray pattern) throws Exception{
        Pattern<JSONObject, JSONObject> parsedPattern = null;

        Iterator<JSONArray> iterator = pattern.iterator();

        while (iterator.hasNext()){
            parsedPattern = appendToPattern(parsedPattern, iterator.next());
        }

        return parsedPattern;
    }

    private Pattern<JSONObject, JSONObject> appendToPattern(Pattern<JSONObject, JSONObject> pattern, JSONArray appender){
        String action = (String) appender.get(0);

        switch (action){
            case "_begin":
                if (pattern == null){
                    pattern = Pattern.begin((String)appender.get(1));
                }
                break;
            case "_next":
                pattern = pattern.next((String)appender.get(1));
                break;
            case "_followedBy":
                pattern = pattern.followedBy((String)appender.get(1));
                break;
            case "_followedByAny":
                pattern = pattern.followedByAny((String)appender.get(1));
                break;
            case "_notNext":
                pattern = pattern.notNext((String)appender.get(1));
                break;
            case "_notFollowedBy":
                pattern = pattern.notFollowedBy((String)appender.get(1));
                break;
            case "_where":
                pattern = pattern.where(new UnifiedCondition((String)appender.get(1)));
                break;
            case "_or":
                pattern = pattern.where(new UnifiedCondition((String)appender.get(1)));
                break;
            case "_until":
                pattern = pattern.where(new UnifiedCondition((String)appender.get(1)));
                break;
            case "_oneOrMore":
                pattern = pattern.oneOrMore();
                break;
            case "_timesOrMode":
                pattern = pattern.timesOrMore((Integer)appender.get(1));
                break;
            case "_times":
                pattern = pattern.times((Integer)appender.get(1));
                break;
            case "_timesRange":
                JSONArray range = ((JSONArray) appender.get(1));
                pattern = pattern.times((Integer)range.get(0), (Integer)range.get(1));
                break;
            case "_optional":
                pattern = pattern.optional();
                break;
            case "_greedy":
                pattern = pattern.greedy();
                break;
            case "_consecutive":
                pattern = pattern.consecutive();
                break;
            case "_allowCombinations":
                pattern = pattern.allowCombinations();
                break;
        }

        return pattern;
    }

    UnifiedCEPLayer startObserving(){
        return this;
    }

}

class UnifiedCondition extends SimpleCondition<JSONObject>{

    static Invocable invoker;
    String functor;

    public UnifiedCondition(String functionName){
        this.functor = functionName;
    }

    @Override
    public boolean filter(JSONObject jsonObject) throws Exception {
        Boolean result = (Boolean) (invoker.invokeFunction(this.functor, jsonObject));
        return result;
    }
}
```

### Deployment tips

(this section will be supplemented)

- Raise Consul Server outside of topology
- Prepare nodes:
    - Install and run Zookeper, Kafka server and topic
    - Download consul_observer.py, cep_processor.jar
    - Raise consul agent with registered Kafka as a service
    - Join to cluster
    - Run consul_ovserver.py
- Write configurations to distributed K/V Consul database at Consul Server.
- Enjoy
    




