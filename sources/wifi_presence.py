import sys
import subprocess
from time import sleep
import socket
import json
import re
from kafka import KafkaProducer
import nmap


mac_regex = r'([0-9a-f]{2}(?::[0-9a-f]{2}){5})'

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

def arp_scan_strategy(producer, topic, macs_to_track):
    while True:
        regexer = re.compile(mac_regex, re.IGNORECASE)
        command = "arp -lan"  # the shell command
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=None, shell=True)
        output = str(process.communicate()[0])
        all_macs = set(map(lambda x: x.upper(), regexer.findall(output)))
        print('MACs in LAN : ' + str(all_macs))
        results = all_macs.intersection(set(map(lambda x: x.upper(), macs_to_track)))
        print("MACs presented: " + str(results))
        producer.send(topic, {'type' : 'NetworkConnections', 'availableHosts' : list(results)})
        sleep(10)

def nmap_scan_strategy(producer, topic, macs_to_track):
    nm = nmap.PortScanner()
    cidr2 = get_local_ip() + '/24'

    while True:
        a = nm.scan(hosts=cidr2, arguments='-sP')
        all_macs = []
        for k, v in a['scan'].items():
            if str(v['status']['state']) == 'up':
                try:
                    print(v['addresses']['mac'])
                    all_macs.append(v['addresses']['mac'])
                except:
                    print("error to parse line")

        all_macs = set(map(lambda x: x.upper(), all_macs))
        print('MACs in LAN : ' + str(all_macs))
        results = all_macs.intersection(set(map(lambda x: x.upper(), macs_to_track)))
        print("MACs presented: " + str(results))
        producer.send(topic, {'type': 'NetworkConnections', 'availableHosts': list(results)})
        sleep(10)



def main():
    server      = sys.argv[1] #kafka server
    topic       = sys.argv[2] #kafka topic
    config_path = sys.argv[3] #mac's list

    producer = KafkaProducer(value_serializer=lambda v: json.dumps(v).encode('utf-8'),
                             bootstrap_servers=server)

    with open(config_path) as f:
        macs_to_track = set(f.read().splitlines())

    #arp_scan_strategy(producer, topic, macs_to_track)
    nmap_scan_strategy(producer, topic, macs_to_track)

if __name__ == "__main__":
    main()
