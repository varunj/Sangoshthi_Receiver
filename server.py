import socket
import sys
'''
usage:
python .\server.py play
python .\server.py pause
python .\server.py seek:1
'''

devicesList = ['10.0.0.3', '10.0.0.6']
TCP_PORT = 6000
BUFFER_SIZE = 1024
MESSAGE = sys.argv[1]

for TCP_IP in devicesList:
	s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
	s.connect((TCP_IP, TCP_PORT))
	s.send(MESSAGE)
	s.close()