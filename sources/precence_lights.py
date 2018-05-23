import sys
from time import sleep
import json
import random
from kafka import KafkaProducer


def main():
    server = sys.argv[1]  # kafka server
    topic = sys.argv[2]  # kafka topic

    producer = KafkaProducer(value_serializer=lambda v: json.dumps(v).encode('utf-8'),
                             bootstrap_servers=server)

    while True:
        producer.send(topic, {
            'type': 'lighting', 'sources': [
                    {
                        "location": "kitchen",
                        "status": False #bool(random.getrandbits(1))
                    },
                    {
                        "location": "bathroom",
                        "status": False #bool(random.getrandbits(1))
                    },
                    {
                        "location": "bedroom",
                        "status": False  #bool(random.getrandbits(1))
                    }
                ]
            }
        )
        sleep(10)


if __name__ == "__main__":
    main()
