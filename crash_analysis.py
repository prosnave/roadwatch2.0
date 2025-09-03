#!/usr/bin/env python3
"""
RoadWatch Crash Analysis Tool
Systematic analysis of potential crash causes based on Android crash test suite
"""

import json
import subprocess
import time
from datetime import datetime

class CrashAnalyzer:
    def __init__(self):
        self.findings = []
        self.test_results = {}

    def analyze_manifest(self):
        """TC_002: Analyze AndroidManifest.xml for configuration issues"""
        print("🔍 Analyzing AndroidManifest.xml...")

        with open('app/src/main/AndroidManifest.xml', 'r') as f:
            manifest_content = f.read()

        issues = []

        # Check for required permissions
        required_permissions = [
            'android.permission.ACCESS_FINE_LOCATION',
            'android.permission.ACCESS_COARSE_LOCATION',
            'android.permission.FOREGROUND_SERVICE'
        ]

        for perm in required_permissions:
            if perm not in manifest_content:
                issues.append(f"Missing required permission: {perm}")

        # Check for activity declaration
        if 'android:name=".app.MainActivity"' not in manifest_content:
            issues.append("MainActivity not properly declared in manifest")

        # Check for LAUNCHER intent filter
        if 'android.intent.action.MAIN' not in manifest_content:
            issues.append("Missing LAUNCHER intent filter")

        # Check for Google Maps API key
        if '${MAPS_API_KEY}' in manifest_content and 'MAPS_API_KEY' not in manifest_content:
            issues.append("Google Maps API key placeholder not resolved")

        self.test_results['TC_002_Manifest'] = {
            'status': 'PASS' if not issues else 'FAIL',
            'issues': issues,
            'details': 'Manifest configuration analysis completed'
        }

        return issues

    def analyze_dependencies(self):
        """TC_004: Analyze dependencies for corruption/missing issues"""
        print("🔍 Analyzing dependencies...")

        with open('app/build.gradle.kts', 'r') as f:
            gradle_content = f.read()

        issues = []

        # Check for required dependencies
        required_deps = [
            'play-services-maps',
            'play-services-location',
            'androidx.appcompat:appcompat'
        ]

        for dep in required_deps:
            if dep not in gradle_content:
                issues.append(f"Missing required dependency: {dep}")

        # Check for Kotlin compatibility issues
        if 'kotlinCompilerExtensionVersion = "1.5.3"' in gradle_content:
            issues.append("Potential Kotlin compiler version mismatch")

        self.test_results['TC_004_Dependencies'] = {
            'status': 'PASS' if not issues else 'FAIL',
            'issues': issues,
            'details': 'Dependency analysis completed'
        }

        return issues

    def analyze_code_for_npe(self):
        """TC_003: Analyze code for potential NullPointerException sources"""
        print("🔍 Analyzing code for NullPointerException risks...")

        issues = []

        # Check MainActivity for lateinit vars
        with open('app/src/main/java/com/roadwatch/app/MainActivity.kt', 'r') as f:
            main_activity = f.read()

        if 'lateinit var' in main_activity:
            issues.append("MainActivity contains lateinit variables that may cause NPE")

        # Check for null safety issues
        if '!!' in main_activity:
            issues.append("MainActivity uses non-null assertion operator which can cause NPE")

        # Check Application class
        with open('app/src/main/java/com/roadwatch/RoadWatchApplication.kt', 'r') as f:
            application = f.read()

        if 'lateinit' in application:
            issues.append("Application class contains lateinit variables")

        self.test_results['TC_003_NPE'] = {
            'status': 'PASS' if not issues else 'WARN',
            'issues': issues,
            'details': 'NullPointerException analysis completed'
        }

        return issues

    def analyze_permissions(self):
        """TC_001: Analyze permission-related crash potential"""
        print("🔍 Analyzing permission configuration...")

        issues = []

        with open('app/src/main/AndroidManifest.xml', 'r') as f:
            manifest = f.read()

        # Check for dangerous permissions that need runtime requests
        dangerous_permissions = [
            'android.permission.ACCESS_FINE_LOCATION',
            'android.permission.ACCESS_COARSE_LOCATION',
            'android.permission.POST_NOTIFICATIONS'
        ]

        for perm in dangerous_permissions:
            if perm in manifest:
                issues.append(f"Dangerous permission declared: {perm} - ensure runtime request")

        # Check for foreground service permissions
        if 'android.permission.FOREGROUND_SERVICE' in manifest:
            if 'android:foregroundServiceType="location"' not in manifest:
                issues.append("FOREGROUND_SERVICE declared but foregroundServiceType not specified")

        self.test_results['TC_001_Permissions'] = {
            'status': 'INFO',
            'issues': issues,
            'details': 'Permission analysis completed - manual verification needed'
        }

        return issues

    def generate_report(self):
        """Generate comprehensive crash analysis report"""
        print("\n" + "="*60)
        print("🚨 ROADWATCH CRASH ANALYSIS REPORT")
        print("="*60)
        print(f"Analysis Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        print()

        all_issues = []

        for test_id, result in self.test_results.items():
            status = result['status']
            issues = result['issues']
            details = result['details']

            status_icon = {
                'PASS': '✅',
                'FAIL': '❌',
                'WARN': '⚠️',
                'INFO': 'ℹ️'
            }.get(status, '❓')

            print(f"{status_icon} {test_id}: {status}")
            print(f"   {details}")

            if issues:
                for issue in issues:
                    print(f"   • {issue}")
                    all_issues.append(f"{test_id}: {issue}")

            print()

        print("="*60)
        print("🎯 RECOMMENDED FIXES")
        print("="*60)

        if all_issues:
            for i, issue in enumerate(all_issues, 1):
                print(f"{i}. {issue}")
        else:
            print("✅ No critical issues found in static analysis")

        print()
        print("🔧 NEXT STEPS:")
        print("1. Install the APK on a test device")
        print("2. Start the logging server: python3 logging_server.py")
        print("3. Launch the app and monitor logs at http://localhost:8081")
        print("4. Check logcat for runtime errors not caught by static analysis")

        return all_issues

    def run_full_analysis(self):
        """Run complete crash analysis suite"""
        print("🚀 Starting RoadWatch Crash Analysis...")
        print("This will analyze the most common causes of Android app crashes")
        print()

        self.analyze_manifest()
        self.analyze_dependencies()
        self.analyze_code_for_npe()
        self.analyze_permissions()

        return self.generate_report()

if __name__ == '__main__':
    analyzer = CrashAnalyzer()
    analyzer.run_full_analysis()
