import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.util.serialization.KeyedDeserializationSchema;
import org.apache.flink.streaming.util.serialization.KeyedSerializationSchema;
import org.json.simple.JSONObject;

import java.io.IOException;

public class UnifiedSerializationSchema implements SerializationSchema<JSONObject> {

    @Override
    public byte[] serialize(JSONObject jsonObject) {
        return jsonObject.toJSONString().getBytes();
    }
}
