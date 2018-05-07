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

        _kafkaOutputServer = (String) inputConfig.get("server");
        _kafkaOutputTopic  = (String) inputConfig.get("topic");

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