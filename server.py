from flask import Flask, request, send_from_directory
import os

app = Flask(__name__)
UPLOAD_DIR = "crash_logs"

@app.route('/upload', methods=['POST'])
def upload_file():
    if 'file' not in request.files:
        return 'No file part', 400
    file = request.files['file']
    if file.filename == '':
        return 'No selected file', 400
    if file:
        if not os.path.exists(UPLOAD_DIR):
            os.makedirs(UPLOAD_DIR)
        file.save(os.path.join(UPLOAD_DIR, "crash_log.txt"))
        return 'File uploaded successfully', 200

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8083)
