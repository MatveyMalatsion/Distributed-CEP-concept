from kafka import KafkaProducer
from time import sleep
import json
import random

server = "localhost:9092"

TEMP_NORM_MIN = 17
TEMP_NORM_MAX = 25
TEMP_MID_MIN = 26
TEMP_MID_MAX = 40
TEMP_HIGH_MIN = 41
TEMP_HIGH_MAX = 60
TEMP_CRIT_MIN = 61
TEMP_CRIT_MAX = 999

GAS_NORM_MIN = 0
GAS_NORM_MAX = 0.1
GAS_MID_MIN = 0.11
GAS_MID_MAX = 0.4
GAS_HIGH_MIN = 0.41
GAS_HIGH_MAX = 0.7
GAS_CRIT_MIN = 0.71
GAS_CRIT_MAX = 1

SMOKE_NORM_MIN = 0
SMOKE_NORM_MAX = 0.3
SMOKE_MID_MIN = 0.31
SMOKE_MID_MAX = 0.6
SMOKE_HIGH_MIN = 0.61
SMOKE_HIGH_MAX = 0.7
SMOKE_CRIT_MIN = 0.7
SMOKE_CRIT_MAX = 1

# Scenario object
# flow - array of states in time
#   minValue - minimumValue
#   maxValue - maximumValue
#   duration - duration of state
# looped - sending or not signals or not after compleations of all states

regularScenarioTEMP     = {"flow": [{"minValue": TEMP_NORM_MIN, "maxValue": TEMP_NORM_MAX, "duration": 100}], "looped": 1}
regularScenarioGAS      = {"flow": [{"minValue": GAS_NORM_MIN, "maxValue": GAS_NORM_MAX, "duration": 100}], "looped": 1}
regularScenarioSMOKE    = {"flow": [{"minValue": SMOKE_NORM_MIN, "maxValue": SMOKE_NORM_MAX, "duration": 100}], "looped": 1}

riskyScenarioGAS        = {"flow": [{"minValue": GAS_NORM_MIN, "maxValue": GAS_NORM_MAX, "duration": 25},
                                    {"minValue": GAS_MID_MIN, "maxValue": GAS_MID_MAX, "duration": 5},
                                    {"minValue": GAS_HIGH_MIN, "maxValue": GAS_HIGH_MAX, "duration": 2},
                                    {"minValue": GAS_MID_MIN, "maxValue": GAS_MID_MAX, "duration": 5}], "looped": 1}

gasExplosionScenarioGAS  = {"flow": [{"minValue": GAS_NORM_MIN, "maxValue": GAS_NORM_MAX, "duration": 30},
                                    {"minValue": GAS_MID_MIN, "maxValue": GAS_MID_MAX, "duration": 5},
                                    {"minValue": GAS_HIGH_MIN, "maxValue": GAS_HIGH_MAX, "duration": 5},
                                    {"minValue": GAS_CRIT_MIN, "maxValue": GAS_CRIT_MAX, "duration": 5}], "looped": 0}

gasExplosionScenarioTEMP = {"flow": [{"minValue": TEMP_NORM_MIN, "maxValue": TEMP_NORM_MAX, "duration": 35},
                                    {"minValue": TEMP_MID_MIN, "maxValue": TEMP_MID_MAX, "duration": 2},
                                    {"minValue": TEMP_HIGH_MIN, "maxValue": TEMP_HIGH_MAX, "duration": 2},
                                    {"minValue": TEMP_CRIT_MIN, "maxValue": TEMP_CRIT_MAX, "duration": 1}], "looped": 0}

fireScenarioTemp         = {"flow": [{"minValue": TEMP_NORM_MIN, "maxValue": TEMP_NORM_MAX, "duration": 20},
                                    {"minValue": TEMP_MID_MIN, "maxValue": TEMP_MID_MAX, "duration": 10},
                                    {"minValue": TEMP_HIGH_MIN, "maxValue": TEMP_HIGH_MAX, "duration": 10},
                                    {"minValue": TEMP_CRIT_MIN, "maxValue": TEMP_CRIT_MAX, "duration": 10}], "looped": 0}

fireScenarioSmoke        = {"flow": [{"minValue": SMOKE_NORM_MIN, "maxValue" :SMOKE_NORM_MAX, "duration": 35},
                                     {"minValue": SMOKE_MID_MIN, "maxValue"  :SMOKE_MID_MAX, "duration": 2},
                                     {"minValue": SMOKE_HIGH_MIN, "maxValue" :SMOKE_HIGH_MAX, "duration": 2},
                                     {"minValue": SMOKE_CRIT_MIN, "maxValue" :SMOKE_CRIT_MAX, "duration": 1}], "looped": 0}

def handleScenario(scenario, t):
    step = scenario["step"]
    if step >= len(scenario["scen"]["flow"]):
        if scenario["scen"]["looped"] == 1:
            step = 0
            scenario["step"] = 0
        else:
            step = len(scenario["scen"]["flow"]) - 1
            scenario["step"] = step

    state = scenario["scen"]["flow"][step]
    min_v = state["minValue"]
    max_v = state["maxValue"]

    current_v = random.uniform(min_v, max_v)

    if scenario["lastChecked"] + state["duration"] <= t:
        scenario["lastChecked"] = t
        step += 1
        scenario["step"] = step

    return current_v

def main():
    topic = "test"

    producer = KafkaProducer(value_serializer=lambda v: json.dumps(v).encode('utf-8'),
                             bootstrap_servers=server)
    print("*** Starting measurements stream on " + server + ", topic : " + topic)

    apartments_count = 100

    scenario_indexes   = random.sample(range(1, apartments_count), 3)

    print("Risky GAS Scenario will be in %d" % scenario_indexes[0], "apartment")
    print("Gas explosion scenario will be in %d" % scenario_indexes[1], "apartment")
    print("Fire scenario will be in %d" % scenario_indexes[2], "apartment")

    scenarios = []

    for i in range(0, apartments_count):
        scenarios.append({
            "gas"   : { "scen" :regularScenarioGAS, "step": 0, "lastChecked" : 0 },
            "smoke" : { "scen" :regularScenarioSMOKE, "step": 0, "lastChecked" : 0 },
            "temp"  : { "scen" :regularScenarioTEMP, "step": 0, "lastChecked" : 0 }
        })

    scenarios[scenario_indexes[0]] = {
        "gas": {"scen": riskyScenarioGAS, "step": 0, "lastChecked": 0},
        "smoke": {"scen": regularScenarioSMOKE, "step": 0, "lastChecked": 0},
        "temp": {"scen": regularScenarioTEMP, "step": 0, "lastChecked": 0}
    }

    scenarios[scenario_indexes[1]] = {
        "gas": {"scen": gasExplosionScenarioGAS , "step": 0, "lastChecked": 0},
        "smoke": {"scen": regularScenarioSMOKE, "step": 0, "lastChecked": 0},
        "temp": {"scen": gasExplosionScenarioTEMP, "step": 0, "lastChecked": 0}
    }

    scenarios[scenario_indexes[2]] = {
        "gas": {"scen": riskyScenarioGAS, "step": 0, "lastChecked": 0},
        "smoke": {"scen": fireScenarioSmoke, "step": 0, "lastChecked": 0},
        "temp": {"scen": fireScenarioTemp, "step": 0, "lastChecked": 0}
    }

    counter = 0

    try:
        while True:
            for apartamentId in range(0, apartments_count):
                scenario = scenarios[apartamentId]

                temp  = format(handleScenario(scenario["temp"], counter), '.2f')
                gas   = format(handleScenario(scenario["gas"], counter), '.2f')
                smoke = format(handleScenario(scenario["smoke"], counter),'.2f')


                measureTemp = {"appId" : "%d" % apartamentId, "type" : "TEMP", "timestamp" : "%d" % counter, "value":  temp}
                measureGas = {"appId": "%d" % apartamentId, "type": "GAS", "timestamp": "%d" % counter,
                               "value": gas}
                measureSmoke = {"appId": "%d" % apartamentId, "type": "SMOKE", "timestamp": "%d" % counter,
                               "value": smoke}

                producer.send(topic, measureTemp, key=str(apartamentId))
                producer.send(topic, measureGas, key=str(apartamentId))
                producer.send(topic, measureSmoke, key=str(apartamentId))

                print("Sending TEMP   : %s" % (json.dumps(measureTemp).encode('utf-8')))
                print("Sending GAS    : %s" % (json.dumps(measureGas).encode('utf-8')))
                print("Sending SMOKE  : %s" % (json.dumps(measureSmoke).encode('utf-8')))

            counter+=1
            sleep(1)

    except KeyboardInterrupt:
        pass





if __name__ == "__main__":
    main()



