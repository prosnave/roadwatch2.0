import socket
import sys

def send_file(file_path, host, port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.connect((host, port))
        with open(file_path, "rb") as f:
            while True:
                bytes_read = f.read(1024)
                if not bytes_read:
                    break
                s.sendall(bytes_read)
    print("File sent successfully.")

if __name__ == '__main__':
    if len(sys.argv) != 4:
        print("Usage: python send_log.py <file_path> <host> <port>")
        sys.exit(1)
    
    file_path = sys.argv[1]
    host = sys.argv[2]
    port = int(sys.argv[3])
    send_file(file_path, host, port)
