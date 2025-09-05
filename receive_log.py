import socket
import os

HOST = '0.0.0.0'
PORT = 8084
UPLOAD_DIR = "crash_logs"

def receive_file():
    if not os.path.exists(UPLOAD_DIR):
        os.makedirs(UPLOAD_DIR)

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind((HOST, PORT))
        s.listen()
        print(f"Listening for file on port {PORT}...")
        conn, addr = s.accept()
        with conn:
            print(f"Connected by {addr}")
            with open(os.path.join(UPLOAD_DIR, "crash_log.txt"), "wb") as f:
                while True:
                    data = conn.recv(1024)
                    if not data:
                        break
                    f.write(data)
            print("File received successfully.")

if __name__ == '__main__':
    receive_file()
