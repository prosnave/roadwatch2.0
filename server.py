#!/usr/bin/env python3
"""
RoadWatch APK Server
Serves the built APK files for testing and distribution.
"""

import http.server
import socketserver
import os
import sys
import argparse
from pathlib import Path

class APKHandler(http.server.SimpleHTTPRequestHandler):
    """Custom handler for APK files with proper MIME types."""

    def end_headers(self):
        # Set proper MIME type for APK files
        if self.path.endswith('.apk'):
            self.send_header('Content-Type', 'application/vnd.android.package-archive')
        super().end_headers()

    def log_message(self, format, *args):
        # Custom logging with emojis
        if self.path.endswith('.apk'):
            print(f"📱 {self.address_string()} - {self.log_date_time_string()} - APK Download: {self.path}")
        else:
            print(f"🌐 {self.address_string()} - {self.log_date_time_string()} - {format % args}")

def find_apk_files():
    """Find all APK files in the project."""
    apk_files = []
    apk_dirs = [
        "app/build/outputs/apk/public/debug",
        "app/build/outputs/apk/public/release",
        "app/build/outputs/apk/admin/debug",
        "app/build/outputs/apk/admin/release"
    ]

    for apk_dir in apk_dirs:
        if os.path.exists(apk_dir):
            for file in Path(apk_dir).rglob("*.apk"):
                apk_files.append(str(file))

    return apk_files

def print_server_info(port, apk_files):
    """Print server information and available APKs."""
    print("🚀 RoadWatch APK Server Started!")
    print(f"📡 Server running on: http://localhost:{port}")
    print(f"🏠 Serving directory: {os.getcwd()}")
    print()

    if apk_files:
        print("📦 Available APK files:")
        for apk in apk_files:
            size_mb = os.path.getsize(apk) / (1024 * 1024)
            print(".1f")
        print()
        print("💡 Access URLs:")
        for apk in apk_files:
            filename = os.path.basename(apk)
            print(f"   http://localhost:{port}/{apk}")
    else:
        print("⚠️  No APK files found. Build the app first:")
        print("   ./gradlew assemblePublicDebug")
        print("   ./gradlew assembleAdminDebug")

    print()
    print("🔄 Server will continue running... Press Ctrl+C to stop")

def main():
    parser = argparse.ArgumentParser(description='Serve RoadWatch APK files')
    parser.add_argument('port', type=int, nargs='?', default=8080,
                       help='Port to serve on (default: 8080)')
    parser.add_argument('--directory', '-d', default='.',
                       help='Directory to serve (default: current directory)')

    args = parser.parse_args()

    # Change to the specified directory
    os.chdir(args.directory)

    # Find APK files
    apk_files = find_apk_files()

    # Print server information
    print_server_info(args.port, apk_files)

    # Start server
    try:
        with socketserver.TCPServer(("", args.port), APKHandler) as httpd:
            httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n👋 Server stopped by user")
    except Exception as e:
        print(f"❌ Server error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
