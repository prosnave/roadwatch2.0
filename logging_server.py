#!/usr/bin/env python3
"""
RoadWatch Logging Server
Collects and displays logs from the Android app for debugging
"""

import json
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse
import threading
import os

class LoggingHandler(BaseHTTPRequestHandler):
    def __init__(self, *args, log_storage=None, **kwargs):
        self.log_storage = log_storage or []
        super().__init__(*args, **kwargs)

    def do_POST(self):
        if self.path == '/log':
            self.handle_log()
        else:
            self.send_error(404, "Endpoint not found")

    def do_GET(self):
        if self.path == '/':
            self.handle_dashboard()
        elif self.path == '/logs':
            self.handle_logs()
        elif self.path == '/clear':
            self.handle_clear()
        else:
            self.send_error(404, "Endpoint not found")

    def handle_log(self):
        try:
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            log_data = json.loads(post_data.decode('utf-8'))

            # Add timestamp and IP
            log_data['server_timestamp'] = datetime.now().isoformat()
            log_data['client_ip'] = self.client_address[0]

            # Store log
            self.log_storage.append(log_data)

            # Keep only last 1000 logs
            if len(self.log_storage) > 1000:
                self.log_storage.pop(0)

            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'status': 'ok'}).encode())

            print(f"📝 [{log_data.get('timestamp', 'N/A')}] {log_data.get('level', 'INFO')}: {log_data.get('tag', 'Unknown')} - {log_data.get('message', 'No message')}")

        except Exception as e:
            print(f"❌ Error processing log: {e}")
            self.send_error(500, f"Error processing log: {str(e)}")

    def handle_dashboard(self):
        try:
            self.send_response(200)
            self.send_header('Content-type', 'text/html')
            self.end_headers()

            html = f"""
            <!DOCTYPE html>
            <html>
            <head>
                <title>RoadWatch Debug Dashboard</title>
                <meta http-equiv="refresh" content="5">
                <style>
                    body {{ font-family: Arial, sans-serif; margin: 20px; }}
                    .log-entry {{ border: 1px solid #ddd; margin: 5px 0; padding: 10px; border-radius: 5px; }}
                    .ERROR {{ background-color: #ffe6e6; border-color: #ff6b6b; }}
                    .WARN {{ background-color: #fff3cd; border-color: #ffc107; }}
                    .INFO {{ background-color: #d1ecf1; border-color: #17a2b8; }}
                    .DEBUG {{ background-color: #f8f9fa; border-color: #6c757d; }}
                    .header {{ background-color: #343a40; color: white; padding: 15px; border-radius: 5px; margin-bottom: 20px; }}
                    .stats {{ display: flex; gap: 20px; margin-bottom: 20px; }}
                    .stat {{ background: #f8f9fa; padding: 10px; border-radius: 5px; border: 1px solid #dee2e6; }}
                    .clear-btn {{ background: #dc3545; color: white; border: none; padding: 10px 20px; border-radius: 5px; cursor: pointer; }}
                    .clear-btn:hover {{ background: #c82333; }}
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>🚗 RoadWatch Debug Dashboard</h1>
                    <p>Real-time logging from Android app</p>
                </div>

                <div class="stats">
                    <div class="stat">
                        <strong>Total Logs:</strong> {len(self.log_storage)}
                    </div>
                    <div class="stat">
                        <strong>Last Update:</strong> {datetime.now().strftime('%H:%M:%S')}
                    </div>
                    <div class="stat">
                        <a href="/clear" class="clear-btn">🗑️ Clear Logs</a>
                    </div>
                </div>

                <h2>📋 Recent Logs</h2>
            """

            # Show last 50 logs in reverse order (newest first)
            for log in reversed(self.log_storage[-50:]):
                level = log.get('level', 'INFO')
                timestamp = log.get('timestamp', 'N/A')
                tag = log.get('tag', 'Unknown')
                message = log.get('message', 'No message')
                client_ip = log.get('client_ip', 'Unknown')

                html += f"""
                <div class="log-entry {level}">
                    <strong>[{timestamp}] {level}</strong> - <code>{tag}</code> ({client_ip})<br>
                    {message}
                </div>
                """

            html += """
            </body>
            </html>
            """

            self.wfile.write(html.encode())
        except BrokenPipeError:
            # Client disconnected, ignore
            pass

    def handle_logs(self):
        try:
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(self.log_storage).encode())
        except BrokenPipeError:
            # Client disconnected, ignore
            pass

    def handle_clear(self):
        self.log_storage.clear()
        self.send_response(302)
        self.send_header('Location', '/')
        self.end_headers()

    def log_message(self, format, *args):
        # Override to reduce server noise
        pass

def create_logging_handler(log_storage):
    def handler_class(*args, **kwargs):
        return LoggingHandler(*args, log_storage=log_storage, **kwargs)
    return handler_class

def run_server(port=8081):
    log_storage = []

    print("🚀 Starting RoadWatch Logging Server...")
    print(f"📊 Dashboard: http://localhost:{port}")
    print(f"📝 Log endpoint: http://localhost:{port}/log")
    print(f"📋 JSON logs: http://localhost:{port}/logs")
    print("🗑️  Clear logs: http://localhost:{port}/clear")
    print("Press Ctrl+C to stop\n")

    handler_class = create_logging_handler(log_storage)
    server = HTTPServer(('0.0.0.0', port), handler_class)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n👋 Shutting down logging server...")
        server.shutdown()

if __name__ == '__main__':
    run_server()
