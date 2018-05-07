import java.io.BufferedReader;
import java.io.FileReader;

import org.apache.commons.cli.MissingArgumentException;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class flink_cep_processor {
    public static void main(String[] args) throws Exception {

        System.out.println("UNIFIED CEP LAYER -------- MATVEY MALATSION");

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment();

        ParameterTool parameterTool = ParameterTool.fromArgs(args);

        String configRaw = parameterTool.get("configJson");
        if(configRaw == null) {

            String configFilePath = parameterTool.get("configPath");

            if(configFilePath == null) {
                throw new MissingArgumentException("You must provide configuration as argument with --configJson or --configPath flags");
            }

            try (BufferedReader br = new BufferedReader(new FileReader(configFilePath))) {
                StringBuilder sb = new StringBuilder();
                String line = br.readLine();

                while (line != null) {
                    sb.append(line);
                    sb.append(System.lineSeparator());
                    line = br.readLine();
                }
                configRaw = sb.toString();
            }
        }


        System.out.println("Provided configuration " + configRaw);


        JSONParser parser = new JSONParser();
        JSONObject config = (JSONObject)parser.parse(configRaw);

        UnifiedCEPLayer layer = new UnifiedCEPLayer(config, env).startObserving();

        env.execute();
    }
}
