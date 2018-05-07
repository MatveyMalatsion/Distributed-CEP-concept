import jdk.internal.util.xml.impl.ReaderUTF8;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.util.serialization.KeyedDeserializationSchema;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;

import static org.apache.flink.api.java.typeutils.TypeExtractor.getForClass;

public class UnifiedDeserializationSchema  implements KeyedDeserializationSchema<JSONObject> {

    private JSONParser parser;

    @Override
    public JSONObject deserialize(byte[] messageKey, byte[] message, String s, int i, long l) throws IOException {

        if (parser == null) {
            parser = new JSONParser();
        }

        try {
            String json = new String(message);
            return  (JSONObject)parser.parse(json);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean isEndOfStream(JSONObject jsonObject) {
        return false;
    }

    @Override
    public TypeInformation<JSONObject> getProducedType() {
        return getForClass(JSONObject.class);
    }
}
