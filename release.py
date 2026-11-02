#!/usr/bin/env python3
"""
CrossAIStudio 1-Click Release Automation Tool
Automatically increments version, compiles signed APK, updates version.json, tags git, and triggers auto-release.
"""

import sys
import os
import re
import json
import shutil
import subprocess

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
BUILD_GRADLE_PATH = os.path.join(PROJECT_DIR, "app", "build.gradle")
VERSION_JSON_PATH = os.path.join(PROJECT_DIR, "version.json")
OUTPUT_APK_PATH = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", "release", "app-release.apk")
ROOT_APK_PATH = os.path.join(PROJECT_DIR, "CrossAIStudio-release.apk")

def read_current_version():
    with open(BUILD_GRADLE_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    
    code_match = re.search(r"versionCode\s+(\d+)", content)
    name_match = re.search(r'versionName\s+"([^"]+)"', content)
    
    current_code = int(code_match.group(1)) if code_match else 1
    current_name = name_match.group(1) if name_match else "1.0.0"
    return current_code, current_name

def compute_next_version(current_name):
    parts = current_name.split(".")
    try:
        if len(parts) >= 3:
            parts[-1] = str(int(parts[-1]) + 1)
            return ".".join(parts)
        elif len(parts) == 2:
            return f"{parts[0]}.{int(parts[1]) + 1}.0"
    except Exception:
        pass
    return f"{current_name}.1"

def update_build_gradle(new_code, new_name):
    with open(BUILD_GRADLE_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    
    content = re.sub(r"versionCode\s+\d+", f"versionCode {new_code}", content, count=1)
    content = re.sub(r'versionName\s+"[^"]+"', f'versionName "{new_name}"', content, count=1)
    
    with open(BUILD_GRADLE_PATH, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"âœ… Updated app/build.gradle: versionCode {new_code}, versionName '{new_name}'")

def update_version_json(new_code, new_name, changelog):
    data = {
        "versionCode": new_code,
        "versionName": new_name,
        "changelog": changelog,
        "downloadUrl": f"https://github.com/rahmatullah-21/CrossAIStudio/releases/download/v{new_name}/CrossAIStudio-release.apk",
        "forceUpdate": False
    }
    with open(VERSION_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    print(f"âœ… Updated version.json for v{new_name}")

def build_apk():
    print("\nðŸ”¨ Building signed release APK (gradlew assembleRelease)...")
    gradle_cmd = "gradlew.bat" if os.name == "nt" else "./gradlew"
    cmd = [os.path.join(PROJECT_DIR, gradle_cmd), "assembleRelease"]
    
    res = subprocess.run(cmd, cwd=PROJECT_DIR)
    if res.returncode != 0:
        print("âŒ Gradle build failed! Please check error output.")
        sys.exit(1)
    
    if os.path.exists(OUTPUT_APK_PATH):
        shutil.copy2(OUTPUT_APK_PATH, ROOT_APK_PATH)
        size_mb = os.path.getsize(ROOT_APK_PATH) / (1024.0 * 1024.0)
        print(f"âœ… Release APK generated: CrossAIStudio-release.apk ({size_mb:.2f} MB)")
    else:
        print("âŒ Output APK not found at:", OUTPUT_APK_PATH)
        sys.exit(1)

def git_commit_and_tag(new_name, auto_push=False):
    tag_name = f"v{new_name}"
    print(f"\nðŸ“¦ Committing version bump and creating tag '{tag_name}'...")
    
    subprocess.run(["git", "add", "app/build.gradle", "version.json"], cwd=PROJECT_DIR, check=True)
    subprocess.run(["git", "commit", "-m", f"release: {tag_name}"], cwd=PROJECT_DIR, check=True)
    subprocess.run(["git", "tag", "-a", tag_name, "-m", f"Release {tag_name}"], cwd=PROJECT_DIR, check=True)
    print(f"âœ… Git tag '{tag_name}' created.")
    
    should_push = auto_push
    if not should_push and sys.stdin.isatty():
        choice = input(f"\nðŸš€ Push commit and tag '{tag_name}' to GitHub to trigger cloud Auto-Release? [Y/n]: ").strip().lower()
        should_push = choice in ("", "y", "yes")
    
    if should_push:
        print("ðŸ“¤ Pushing to origin main and tags...")
        subprocess.run(["git", "push", "origin", "main", "--tags"], cwd=PROJECT_DIR, check=True)
        print("\nâœ¨ Successfully pushed! GitHub Actions is now automatically building & publishing the release.")
        print(f"ðŸ“– View progress at: https://github.com/rahmatullah-21/CrossAIStudio/actions")
        print(f"ðŸŽ‰ Download page: https://github.com/rahmatullah-21/CrossAIStudio/releases/tag/{tag_name}")
    else:
        print(f"\nâ„¹ï¸ Push manually when ready: git push origin main --tags")

def main():
    print("=" * 60)
    print("       CrossAIStudio 1-Click Auto-Release Tool")
    print("=" * 60)
    
    current_code, current_name = read_current_version()
    print(f"Current Version: v{current_name} (Build {current_code})")
    
    next_code = current_code + 1
    suggested_name = compute_next_version(current_name)
    
    # CLI arguments support: python release.py [version] [changelog] [--push]
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    auto_push = "--push" in sys.argv
    
    if len(args) >= 1:
        new_name = args[0].lstrip("v")
    elif not sys.stdin.isatty():
        new_name = suggested_name
    else:
        user_input = input(f"Enter new version name [{suggested_name}]: ").strip()
        new_name = user_input if user_input else suggested_name
    
    if len(args) >= 2:
        changelog = args[1]
    elif not sys.stdin.isatty():
        changelog = f"Release v{new_name} with bug fixes and performance improvements."
    else:
        user_notes = input("Enter release changelog [Bug fixes & performance improvements]: ").strip()
        changelog = user_notes if user_notes else f"Release v{new_name} with bug fixes and performance improvements."
    
    print(f"\nTarget Version: v{new_name} (Build {next_code})")
    print(f"Changelog: {changelog}")
    
    update_build_gradle(next_code, new_name)
    update_version_json(next_code, new_name, changelog)
    build_apk()
    git_commit_and_tag(new_name, auto_push=auto_push)
    
    print("\n" + "=" * 60)
    print(f"  âœ… Auto-Release process for v{new_name} complete!")
    print("=" * 60)

if __name__ == "__main__":
    main()
