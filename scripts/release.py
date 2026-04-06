#!/usr/bin/env python3
"""
Voxtral OTA release script.
Usage: python3 release.py <apk_path> <version_int> <notes>
Example: python3 release.py /tmp/voxtral-v1.2.apk 2 "Nouvelle fonctionnalité X"
"""
import sys, json
from google.oauth2 import service_account
import google.auth.transport.requests
import requests

KEY_FILE = "/home/dl/.secrets/firebase-adminsdk.json"
PROJECT = "messaging-app-71a13"
BUCKET = "messaging-app-71a13.firebasestorage.app"

def get_token(scopes):
    creds = service_account.Credentials.from_service_account_file(KEY_FILE, scopes=scopes)
    creds.refresh(google.auth.transport.requests.Request())
    return creds.token

def upload_apk(apk_path, version):
    gcs_name = f"apks/voxtral-v{version}.apk"
    token = get_token(["https://www.googleapis.com/auth/devstorage.full_control"])
    url = f"https://storage.googleapis.com/upload/storage/v1/b/{BUCKET}/o?uploadType=media&name={gcs_name}"
    with open(apk_path, "rb") as f:
        r = requests.post(url, headers={"Authorization": f"Bearer {token}", "Content-Type": "application/octet-stream"}, data=f.read())
    if r.status_code not in (200, 201):
        raise Exception(f"Upload failed: {r.status_code} {r.text[:200]}")
    # Set public
    encoded = gcs_name.replace("/", "%2F")
    requests.post(f"https://storage.googleapis.com/storage/v1/b/{BUCKET}/o/{encoded}/acl",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        json={"entity": "allUsers", "role": "READER"})
    dl_url = f"https://storage.googleapis.com/{BUCKET}/{gcs_name}"
    print(f"Uploaded: {dl_url}")
    return dl_url

def update_firestore(version, url, notes):
    token = get_token(["https://www.googleapis.com/auth/datastore"])
    fs_url = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents/config/voxtral_update"
    r = requests.patch(fs_url, headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        json={"fields": {
            "version": {"integerValue": str(version)},
            "url": {"stringValue": url},
            "notes": {"stringValue": notes}
        }})
    print(f"Firestore updated: {r.status_code}")

if __name__ == "__main__":
    if len(sys.argv) < 4:
        print("Usage: python3 release.py <apk_path> <version_int> <notes>")
        sys.exit(1)
    apk_path, version, notes = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    dl_url = upload_apk(apk_path, version)
    update_firestore(version, dl_url, notes)
    print(f"Release v{version} deployed!")
