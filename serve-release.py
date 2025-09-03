#!/usr/bin/env python3
"""
Simple HTTP server to serve RoadWatch release APKs
"""

import http.server
import socketserver
import os
from urllib.parse import unquote
import mimetypes

class APKHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/':
            self.serve_index()
        elif self.path.startswith('/releases/'):
            self.serve_apk()
        else:
            self.send_error(404, "File not found")

    def serve_index(self):
        self.send_response(200)
        self.send_header('Content-type', 'text/html')
        self.end_headers()

        html = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>RoadWatch Release APKs</title>
            <style>
                body { font-family: Arial, sans-serif; margin: 40px; }
                .apk-link { display: block; margin: 10px 0; padding: 15px; background: #f0f0f0; border-radius: 5px; text-decoration: none; color: #333; }
                .apk-link:hover { background: #e0e0e0; }
                .filename { font-weight: bold; }
                .size { color: #666; font-size: 0.9em; }
            </style>
        </head>
        <body>
            <h1>🚗 RoadWatch Release APKs</h1>
            <p>Download the latest signed release builds:</p>
        """

        # List all APK files in releases directory
        releases_dir = "releases"
        if os.path.exists(releases_dir):
            for root, dirs, files in os.walk(releases_dir):
                for file in sorted(files):
                    if file.endswith('.apk'):
                        filepath = os.path.join(root, file)
                        size = os.path.getsize(filepath)
                        size_mb = size / (1024 * 1024)
                        relative_path = os.path.relpath(filepath)

                        html += f"""
                        <a href="/{relative_path}" class="apk-link">
                            <div class="filename">{file}</div>
                            <div class="size">{size_mb:.1f} MB</div>
                        </a>
                        """

        html += """
        </body>
        </html>
        """

        self.wfile.write(html.encode())

    def serve_apk(self):
        try:
            filepath = unquote(self.path[1:])  # Remove leading slash

            if not os.path.exists(filepath):
                self.send_error(404, "APK file not found")
                return

            if not filepath.endswith('.apk'):
                self.send_error(403, "Access denied")
                return

            # Get file size
            size = os.path.getsize(filepath)

            self.send_response(200)
            self.send_header('Content-type', 'application/vnd.android.package-archive')
            self.send_header('Content-Disposition', f'attachment; filename="{os.path.basename(filepath)}"')
            self.send_header('Content-Length', str(size))
            self.end_headers()

            # Stream the file
            with open(filepath, 'rb') as f:
                while True:
                    data = f.read(8192)
                    if not data:
                        break
                    self.wfile.write(data)

        except Exception as e:
            self.send_error(500, f"Error serving file: {str(e)}")

    def log_message(self, format, *args):
        # Reduce server noise
        pass

def run_server(port=8082):
    print("🚀 Starting RoadWatch Release Server...")
    print(f"📱 Download page: http://localhost:{port}")
    print("Press Ctrl+C to stop\n")

    with socketserver.TCPServer(("", port), APKHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n👋 Shutting down release server...")

if __name__ == '__main__':
    run_server()
