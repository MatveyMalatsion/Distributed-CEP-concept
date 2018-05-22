import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.PatternSelectFunction;
import org.apache.flink.cep.PatternStream;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.IngestionTimeExtractor;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer09;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer09;
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

    private ArrayList<Pattern<JSONObject, JSONObject>> parsedPatterns = new ArrayList<>();
    private JSONObject configuration;

    ScriptEngine engine;
    StreamExecutionEnvironment _env;

    HashMap<Pattern<JSONObject, JSONObject>, String> transformMatcher = new HashMap<>();

    public UnifiedCEPLayer(JSONObject configuration, StreamExecutionEnvironment env) throws Exception{
        this.configuration = configuration;
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
        UnifiedPatternSelectFunction.invoker = (Invocable)engine;

        JSONArray conditions = (JSONArray) configuration.get("conditions");

        Iterator<String> iterator = conditions.iterator();

        while (iterator.hasNext()){
            engine.eval(iterator.next());
        }

        // extract patterns
        JSONArray patterns = (JSONArray)configuration.get("patterns");

        Iterator<JSONObject> objectIterator = patterns.iterator();

        while (objectIterator.hasNext()){

            JSONObject pattern = objectIterator.next();
            JSONArray patternParts = (JSONArray) (pattern.getOrDefault("pattern", null));
            if (patternParts != null) {

                Pattern<JSONObject, JSONObject> parsedPattern = patternFromJSON(patternParts);

                String transformFunctor = (String) pattern.getOrDefault("transform_functor", null);

                if (transformFunctor != null){
                    transformMatcher.put(parsedPattern, transformFunctor);
                }

                parsedPatterns.add(parsedPattern);
            }
        }
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
            case "_timesOrMore":
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

    public void startObserving(){
        //configure input
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", this._kafkaInputServer);

        System.out.println("STARTED!");

        //creating all messages stream
        DataStream<JSONObject> messageStream = _env.addSource(new FlinkKafkaConsumer09<JSONObject>(_kafkaInputTopic,
                new UnifiedDeserializationSchema(),
                properties
        )).assignTimestampsAndWatermarks(new IngestionTimeExtractor<>());


        //configure partition rule
        String partitionKey = (String) configuration.getOrDefault("partitionKey", null);
        if(partitionKey != null) {
            KeySelector<JSONObject, String> partitionRule = new KeySelector<JSONObject, String>() {
                @Override
                public String getKey(JSONObject value) throws Exception {
                    return (String) value.getOrDefault(partitionKey, null);
                }
            };
            //creating partiton stream
            messageStream = messageStream.keyBy(partitionRule);
        }
        ArrayList<PatternStream<JSONObject>> streams = new ArrayList<PatternStream<JSONObject>>();

        DataStream<JSONObject> finalMessageStream = messageStream;
        parsedPatterns.forEach(tPattern -> streams.add(CEP.pattern(finalMessageStream, tPattern)));

        ArrayList<DataStream<JSONObject>> warnings = new ArrayList<>();

        //detecting patterns
        streams.forEach(stream -> {
            Pattern<JSONObject, ?> pattern = stream.getPattern();
            String transformFunctor = this.transformMatcher.getOrDefault(pattern, null);
            warnings.add(stream.select(new UnifiedPatternSelectFunction(transformFunctor)));
        });

        //print alarms
        warnings.forEach(w -> w.map( warning ->{
            return warning;
        }).print());

        FlinkKafkaProducer09 producer = new FlinkKafkaProducer09<JSONObject>(_kafkaOutputServer, _kafkaOutputTopic, new UnifiedSerializationSchema());

        warnings.forEach(w -> w.map( warning ->{
            return warning;
        }).addSink(producer));

    }

}

class UnifiedPatternSelectFunction implements PatternSelectFunction<JSONObject, JSONObject>{
    static Invocable invoker;
    String functor;

    public UnifiedPatternSelectFunction(String functorName){
        functor = functorName;
    }

    @Override
    public JSONObject select(Map<String, List<JSONObject>> map) throws Exception {

        JSONObject obj = new JSONObject();
        obj.putAll(map);

        if(functor != null){
            Map result = (Map)(invoker.invokeFunction(functor, obj));
            JSONObject json = new JSONObject();
            json.putAll(result);
            return json;
        }

        return obj;
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
